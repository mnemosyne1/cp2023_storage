package cp2023.solution;

import cp2023.base.*;
import cp2023.exceptions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class StorageSystemInstance implements StorageSystem {
    private final ConcurrentMap<DeviceId, AtomicInteger> deviceFreeSlots;
    private final ConcurrentMap<ComponentId, DeviceId> componentPlacement;
    private final ConcurrentMap<ComponentId, Semaphore> mutexComponentOperation;
    private final ConcurrentMap<ComponentTransfer, Semaphore> transferSleep;
    private final Semaphore mutexGraph;
    private final ConcurrentMap<ComponentId, Thread> componentsOperatedOn;
    private final Map<DeviceId, List<ComponentTransfer>> awaitingTransfers;
    private final Map<DeviceId, List<ComponentTransfer>> preparingFreeTransfers;
    private final ConcurrentMap<ComponentTransfer, ComponentTransfer> transferIDependOn;
    private final Map<ComponentTransfer, ComponentTransfer> transferTakingMyPlace;

    public StorageSystemInstance(Map<DeviceId, Integer> deviceTotalSlots,
                                 Map<ComponentId, DeviceId> componentPlacement) {
        if (deviceTotalSlots.isEmpty())
            throw new IllegalArgumentException("No devices were given");
        this.deviceFreeSlots = new ConcurrentHashMap<>();
        deviceTotalSlots.forEach((devId, capacity) -> {
            if (capacity == 0)
                throw new IllegalArgumentException("Device with ID "
                        + devId + " declared to have capacity = 0");
            else
                deviceFreeSlots.put(devId, new AtomicInteger(capacity));
        });
        this.componentPlacement = new ConcurrentHashMap<>();
        this.mutexComponentOperation = new ConcurrentHashMap<>();
        this.componentsOperatedOn = new ConcurrentHashMap<>();
        this.transferSleep = new ConcurrentHashMap<>();
        componentPlacement.forEach((compId, devId) -> {
            if (deviceDoesNotExist(devId))
                throw new IllegalArgumentException("Device with ID " + devId +
                        " (component: " + compId + ") does not exist");
            if (this.deviceFreeSlots.get(devId).decrementAndGet() < 0)
                throw new IllegalArgumentException("Too many components were " +
                        "assigned to device " + devId);
            this.componentPlacement.put(compId, devId);
        });
        awaitingTransfers = new HashMap<>();
        preparingFreeTransfers = new HashMap<>();
        transferIDependOn = new ConcurrentHashMap<>();
        transferTakingMyPlace = new HashMap<>();
        deviceFreeSlots.forEach((devId, capacity) ->
                awaitingTransfers.put(devId, new LinkedList<>()));
        deviceFreeSlots.forEach((devId, capacity) ->
                preparingFreeTransfers.put(devId, new LinkedList<>()));
        mutexGraph = new Semaphore(1, true);
    }

    @Override
    public void execute(ComponentTransfer transfer) throws TransferException {
        checkTransferCorrectness(transfer);
        transferSleep.putIfAbsent(transfer, new Semaphore(0, true));
        demandTransfer(transfer);
        try {
            transferSleep.get(transfer).acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption", e);
        }
        transfer.prepare();
        preparationFinished(transfer);
        try {
            transferSleep.get(transfer).acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption", e);
        }
        transfer.perform();
        endTransfer(transfer);
    }

    private void checkTransferCorrectness(ComponentTransfer transfer) throws TransferException {
        ComponentId component = transfer.getComponentId();
        mutexComponentOperation.putIfAbsent(component, new Semaphore(1, true));
        // mutex is protecting the set componentsOperatedOn from simultaneous
        // starting and ending the operation on a component (in order to avoid
        // the time-of-check to time-to-use bug)
        try {
            mutexComponentOperation.get(component).acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption", e);
        }
        componentsOperatedOn.putIfAbsent(component, Thread.currentThread());
        try {
            if (!componentsOperatedOn.get(component).equals(Thread.currentThread()))
                throw new ComponentIsBeingOperatedOn(component);
        } finally {
            mutexComponentOperation.get(component).release();
        }

        DeviceId destination = transfer.getDestinationDeviceId();
        DeviceId source = transfer.getSourceDeviceId();
        if (source == null && destination == null)
            throw new IllegalTransferType(transfer.getComponentId());
        if (source != null && deviceDoesNotExist(source))
            throw new DeviceDoesNotExist(source);
        if (destination != null && deviceDoesNotExist(destination))
            throw new DeviceDoesNotExist(destination);
        if (source == null)
            assertComponentIsNew(component);
        else {
            assertComponentExists(component, source);
            if (source.equals(destination))
                throw new ComponentDoesNotNeedTransfer(component, source);
        }
    }

    private boolean deviceDoesNotExist(DeviceId id) {
        return !deviceFreeSlots.containsKey(id);
    }

    private void assertComponentExists(ComponentId component, DeviceId device)
            throws ComponentDoesNotExist {
        // there is only one process operating on a component (checked earlier)
        if (!device.equals(componentPlacement.get(component)))
            throw new ComponentDoesNotExist(component, device);
    }

    private void assertComponentIsNew(ComponentId component) throws ComponentAlreadyExists {
        // there is only one process operating on a component (checked earlier)
        if (componentPlacement.containsKey(component))
            throw new ComponentAlreadyExists(component, componentPlacement.get(component));
    }

    private void endTransfer(ComponentTransfer transfer) {
        ComponentId component = transfer.getComponentId();
        try {
            mutexComponentOperation.get(component).acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption", e);
        }
        componentsOperatedOn.remove(component);
        mutexComponentOperation.get(component).release();
    }

    private void demandTransfer(ComponentTransfer transfer) {
        DeviceId destination = transfer.getDestinationDeviceId();
        DeviceId source = transfer.getSourceDeviceId();
        try {
            mutexGraph.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption", e);
        }
        if (destination == null) {
            freeSpaceOnSource(transfer);
            transferSleep.get(transfer).release();
        }
        // will not be done simultaneously by many processes thanks to the mutex
        else if (deviceFreeSlots.get(destination).get() > 0) {
            // reserve free slot on destination device
            deviceFreeSlots.get(destination).decrementAndGet();
            freeSpaceOnSource(transfer);
            transferSleep.get(transfer).release();
        }
        // state of preparingFreeTransfers will not change thanks to mutex
        else if (!preparingFreeTransfers.get(destination).isEmpty()) {
            // reserve slot on destination device that will be freed by a transfer
            // which is now preparing (so current transfer will have to wait with
            // calling perform() until the other has finished its prepare())
            ComponentTransfer other = preparingFreeTransfers.get(destination).get(0);
            transferIDependOn.put(transfer, other);
            transferTakingMyPlace.put(other, transfer);
            preparingFreeTransfers.get(destination).remove(0);
            freeSpaceOnSource(transfer);
            transferSleep.get(transfer).release();
        }
        else {
            ComponentTransfer dfsResult = dfs(source, transfer, new HashSet<>());
            if (dfsResult == null) {
                // no cycle found, we have to wait before being allowed to prepare
                awaitingTransfers.get(destination).add(transfer);
            }
            else {
                transferIDependOn.put(dfsResult, transfer);
                transferTakingMyPlace.put(transfer, dfsResult);
                transferSleep.get(transfer).release();
            }
        }
        mutexGraph.release();
    }

    private void freeSpaceOnSource(ComponentTransfer transfer) {
        DeviceId source = transfer.getSourceDeviceId();
        if (source != null) {
            if (awaitingTransfers.get(source).isEmpty()) {
                // add to list of transfers that nobody depends on
                preparingFreeTransfers.get(source).add(transfer);
            }
            else {
                ComponentTransfer waiting = awaitingTransfers.get(source).get(0);
                awaitingTransfers.get(source).remove(0);
                transferIDependOn.put(waiting, transfer);
                transferTakingMyPlace.put(transfer, waiting);
                freeSpaceOnSource(waiting);
                transferSleep.get(waiting).release();
            }
        }
    }

    private void preparationFinished(ComponentTransfer transfer) {
        DeviceId source = transfer.getSourceDeviceId();
        DeviceId destination = transfer.getDestinationDeviceId();
        ComponentId component = transfer.getComponentId();
        if (source != null) {
            try {
                mutexGraph.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException("panic: unexpected thread interruption", e);
            }
            if (preparingFreeTransfers.get(source).contains(transfer)) {
                // free slot on the source device
                deviceFreeSlots.get(source).incrementAndGet();
                preparingFreeTransfers.get(source).remove(transfer);
            }
            else {
                // a transfer wants to take the place I've freed, so I allow it
                ComponentTransfer other = transferTakingMyPlace.get(transfer);
                transferTakingMyPlace.remove(transfer);
                transferSleep.get(other).release();
            }
            mutexGraph.release();
        }
        if (!transferIDependOn.containsKey(transfer)) {
            // transfer can be performed
            transferSleep.get(transfer).release();
        }
        else {
            // my transferSleep will be released by the transfer I depend on
            transferIDependOn.remove(transfer);
        }

        if (destination == null)
            componentPlacement.remove(component);
        else
            componentPlacement.put(component, destination);
    }

    private ComponentTransfer dfs(DeviceId device, ComponentTransfer transfer, Set<DeviceId> visited) {
        if (device == null || visited.contains(device))
            return null;
        visited.add(device);
        for (ComponentTransfer t : awaitingTransfers.get(device)) {
            DeviceId source = t.getSourceDeviceId();
            if (source != null) {
                if (source.equals(transfer.getDestinationDeviceId())) {
                    transferIDependOn.put(transfer, t);
                    transferTakingMyPlace.put(t, transfer);
                    transferSleep.get(t).release();
                    awaitingTransfers.get(device).remove(t);
                    return t;
                }
                ComponentTransfer ans = dfs(t.getSourceDeviceId(), transfer, visited);
                if (ans != null) {
                    awaitingTransfers.get(device).remove(t);
                    transferIDependOn.put(ans, t);
                    transferTakingMyPlace.put(t, ans);
                    transferSleep.get(t).release();
                    return t;
                }
            }
        }
        return null;
    }
}
