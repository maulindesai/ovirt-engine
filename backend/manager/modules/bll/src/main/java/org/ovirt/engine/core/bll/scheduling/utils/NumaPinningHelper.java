package org.ovirt.engine.core.bll.scheduling.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.ovirt.engine.core.common.businessentities.HugePage;
import org.ovirt.engine.core.common.businessentities.NumaNode;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VdsNumaNode;
import org.ovirt.engine.core.common.businessentities.VmNumaNode;
import org.ovirt.engine.core.common.utils.HugePageUtils;
import org.ovirt.engine.core.compat.Guid;

public class NumaPinningHelper {

    private List<VmNumaNodeData> vmNumaNodesData;
    private Map<Integer, HostNumaNodeData> hostNumaNodesData;
    private List<Runnable> commandStack;
    private Map<Guid, Integer> currentAssignment;

    private NumaPinningHelper(List<VmNumaNodeData> vmNumaNodesData, Map<Integer, HostNumaNodeData> hostNumaNodesData) {
        this.vmNumaNodesData = vmNumaNodesData;
        this.hostNumaNodesData = hostNumaNodesData;
    }

    public static Map<Integer, List<Integer>> createCpuMap(Collection<? extends NumaNode> nodes) {
        return nodes.stream().collect(Collectors.toMap(NumaNode::getIndex, NumaNode::getCpuIds));
    }

    /**
     * Find one possible assignment of VM NUMA nodes to host NUMA nodes,
     * so that all VM nodes can fit to their assigned host nodes.
     *
     * @param vms List of VMs to check NUMA pinnig
     * @param hostNodes List of host NUMA nodes
     * @return Optional of Map from VM node index to host node index
     */
    public static Optional<Map<Guid, Integer>> findAssignment(List<VM> vms, List<VdsNumaNode> hostNodes, boolean considerCpuPinning) {

        boolean noNodes = vms.stream().allMatch(vm -> vm.getvNumaNodeList().isEmpty());
        if (noNodes) {
            return Optional.empty();
        }

        Map<Integer, HostNumaNodeData> hostNodesData = hostNodes.stream()
                .collect(Collectors.toMap(
                        NumaNode::getIndex,
                        node -> new HostNumaNodeData(
                                    node,
                                    considerCpuPinning)
                ));

        // Check if all VM nodes are pinned to existing host nodes
        boolean allPinnedNodesExist = vms.stream()
                .flatMap(vm -> vm.getvNumaNodeList().stream())
                .flatMap(node -> node.getVdsNumaNodeList().stream())
                .allMatch(hostNodesData::containsKey);

        if (!allPinnedNodesExist) {
            return Optional.empty();
        }

        List<VmNumaNodeData> vmNodesData = vms.stream()
                .flatMap(vm -> {
                    Map<Integer, Collection<Integer>> cpuPinning = considerCpuPinning ?
                            CpuPinningHelper.parseCpuPinning(vm.getCpuPinning()).stream()
                                    .collect(Collectors.toMap(p -> p.getvCpu(), p -> p.getpCpus())) :
                            null;
                    Optional<Integer> hugePageSize = HugePageUtils.getHugePageSize(vm.getStaticData());
                    return vm.getvNumaNodeList().stream().map(node -> new VmNumaNodeData(node, cpuPinning, hugePageSize));
                })
                .collect(Collectors.toList());

        NumaPinningHelper helper = new NumaPinningHelper(vmNodesData, hostNodesData);
        return Optional.ofNullable(helper.fitNodes());
    }

    private Runnable fitNodesRunnable(int vmNumaNodeIndex) {
        return () -> {
            // Stopping condition for recursion
            if (vmNumaNodeIndex >= vmNumaNodesData.size()) {
                // If all nodes fit, clear the commandStack to skip all other commands
                // and return current assignment
                commandStack.clear();
                return;
            }

            VmNumaNode vmNode = vmNumaNodesData.get(vmNumaNodeIndex).getVmNumaNode();
            Map<Integer, Collection<Integer>> cpuPinning = vmNumaNodesData.get(vmNumaNodeIndex).getCpuPinning();

            // If the node is not pinned, skip it.
            //
            // Unpinned nodes will behave according to the default NUMA configuration on the host.
            // Here they are ignored, because we don't know if the host will use strict, interleaved or
            // preferred mode and to which host nodes they can be pinned.
            if (vmNode.getVdsNumaNodeList().isEmpty()) {
                commandStack.add(fitNodesRunnable(vmNumaNodeIndex + 1));
                return;
            }

            // The commands will be executed in reversed order, but it does not matter,
            // because this function is looking for any possible assignment
            for (Integer pinnedIndex: vmNode.getVdsNumaNodeList()) {
                commandStack.add(() -> {
                    if (cpuPinning != null && !vmNodeFitsHostNodeCpuPinning(vmNode,
                            hostNumaNodesData.get(pinnedIndex).getCpuIds(),
                            cpuPinning)) {
                        return;
                    }


                    Optional<Integer> hugePageSize = vmNumaNodesData.get(vmNumaNodeIndex).getHugePageSize();
                    if (!hugePageSize.isPresent()) {
                        long hostFreeMem = hostNumaNodesData.get(pinnedIndex).getMemFree();
                        if (hostFreeMem < vmNode.getMemTotal()) {
                            return;
                        }

                        // The current VM node fits to the host node,
                        hostNumaNodesData.get(pinnedIndex).setMemFree(hostFreeMem - vmNode.getMemTotal());
                        currentAssignment.put(vmNode.getId(), pinnedIndex);

                        // Push revert command to the stack
                        commandStack.add(() -> {
                            currentAssignment.remove(vmNode.getId());
                            hostNumaNodesData.get(pinnedIndex).setMemFree(hostFreeMem);
                        });
                    } else {
                        // NUMA node size must be a multiple of hugePage size - that is ensured
                        // in NumaValidator
                        int hugePageSizeMB = hugePageSize.get() / 1024;
                        int requiredHugePages = (int) vmNode.getMemTotal() / hugePageSizeMB;
                        int hostFreePages = HugePageUtils.hugePagesToMap(hostNumaNodesData.get(pinnedIndex).getHugePages()).get(hugePageSize.get());
                        if (hostFreePages < requiredHugePages) {
                            return;
                        }

                        // The current VM node fits to the host node,
                        HugePageUtils.updateHugePages(hostNumaNodesData.get(pinnedIndex).getHugePages(),
                                hugePageSize.get(),
                                hostFreePages - requiredHugePages);
                        currentAssignment.put(vmNode.getId(), pinnedIndex);

                        // Push revert command to the stack
                        commandStack.add(() -> {
                            currentAssignment.remove(vmNode.getId());
                            HugePageUtils.updateHugePages(hostNumaNodesData.get(pinnedIndex).getHugePages(),
                                    hugePageSize.get(),
                                    hostFreePages);
                        });
                    }

                    // Push recursive call
                    commandStack.add(fitNodesRunnable(vmNumaNodeIndex + 1));
                });
            }
        };
    }

    private Map<Guid, Integer> fitNodes() {
        // Algorithm uses explicit stack, to avoid stack overflow
        currentAssignment = new HashMap<>();
        commandStack = new ArrayList<>();

        // Last command - assignment was not found
        commandStack.add(() -> {
            currentAssignment = null;
        });

        commandStack.add(fitNodesRunnable(0));

        while (!commandStack.isEmpty()) {
            commandStack.remove(commandStack.size() - 1).run();
        }

        return currentAssignment;
    }

    private boolean vmNodeFitsHostNodeCpuPinning(VmNumaNode vmNode,
            List<Integer> hostNodeCpus,
            Map<Integer, Collection<Integer>> cpuPinning) {
        for (Integer vmCpuId: vmNode.getCpuIds()) {
            Collection<Integer> pinnedCpus = cpuPinning.get(vmCpuId);
            if (pinnedCpus == null) {
                continue;
            }

            if (pinnedCpus.stream().anyMatch(hostNodeCpus::contains)) {
                continue;
            }

            return false;
        }

        return true;
    }

    private static class HostNumaNodeData {

        private long memFree;

        private List<Integer> cpuIds = new ArrayList<>();

        private List<HugePage> hugePages;

        public HostNumaNodeData(VdsNumaNode numaNode, boolean considerCpuPinning) {
            setMemFree(numaNode.getNumaNodeStatistics().getMemFree());
            setHugePages(numaNode.getNumaNodeStatistics().getHugePages());
            if (considerCpuPinning) {
                setCpuIds(numaNode.getCpuIds());
            }
        }

        public long getMemFree() {
            return memFree;
        }

        public void setMemFree(long memFree) {
            this.memFree = memFree;
        }

        public List<Integer> getCpuIds() {
            return cpuIds;
        }

        public void setCpuIds(List<Integer> cpuIds) {
            this.cpuIds = new ArrayList<>(cpuIds);
        }

        public List<HugePage> getHugePages() {
            return hugePages;
        }

        public void setHugePages(List<HugePage> hugePages) {
            this.hugePages = hugePages.stream()
                    .map(page -> new HugePage(page.getSizeKB(), page.getAmount()))
                    .collect(Collectors.toList());
        }
    }

    private static class VmNumaNodeData {

        private VmNumaNode vmNumaNode;

        private Map<Integer, Collection<Integer>> cpuPinning;

        private Optional<Integer> hugePageSize;

        public VmNumaNodeData(VmNumaNode vmNumaNode, Map<Integer, Collection<Integer>> cpuPinning, Optional<Integer> hugePageSize) {
            this.vmNumaNode = vmNumaNode;
            this.cpuPinning = cpuPinning;
            this.hugePageSize = hugePageSize;
        }

        public VmNumaNode getVmNumaNode() {
            return vmNumaNode;
        }

        public Map<Integer, Collection<Integer>> getCpuPinning() {
            return cpuPinning;
        }

        public Optional<Integer> getHugePageSize() {
            return hugePageSize;
        }
    }
}
