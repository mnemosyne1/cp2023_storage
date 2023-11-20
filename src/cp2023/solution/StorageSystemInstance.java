package cp2023.solution;

import cp2023.base.ComponentId;
import cp2023.base.ComponentTransfer;
import cp2023.base.DeviceId;
import cp2023.base.StorageSystem;
import cp2023.exceptions.*;

import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class StorageSystemInstance implements StorageSystem {
    private final ConcurrentMap<DeviceId, AtomicInteger> deviceFreeSlots;
    private final ConcurrentMap<ComponentId, DeviceId> componentPlacement;
    private final ConcurrentMap<ComponentId, Semaphore> mutexComponentOperation;
    private final ConcurrentMap<ComponentTransfer, Semaphore> transferSleep;
    private final ConcurrentMap<ComponentId, Thread> componentsOperatedOn;
    private final TransferOrganiser organiser;

    public StorageSystemInstance(Map<DeviceId, Integer> deviceTotalSlots, Map<ComponentId, DeviceId> componentPlacement) {
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
        this.organiser = new TransferOrganiser();
        componentPlacement.forEach((compId, devId) -> {
            if (!deviceExists(devId))
                throw new IllegalArgumentException("Device with ID " + devId +
                        " (component: " + compId + ") does not exist");
            if (this.deviceFreeSlots.get(devId).decrementAndGet() < 0)
                throw new IllegalArgumentException("Too many components were " +
                        "assigned to device " + devId);
            this.componentPlacement.put(compId, devId);
            prepareMutexes(compId);
        });
    }

    @Override
    public void execute(ComponentTransfer transfer) throws TransferException {
        ComponentId component = transfer.getComponentId();
        prepareMutexes(component);
        // mutex is protecting the set componentsOperatedOn from simultaneous
        // starting and ending the operation on a component (in order to avoid
        // the time-of-check to time-to-use bug)
        try {
            mutexComponentOperation.get(component).acquire();
        } catch (InterruptedException e) {
            panic(e);
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
        if (source != null && !deviceExists(source))
            throw new DeviceDoesNotExist(source);
        if (destination != null && !deviceExists(destination))
            throw new DeviceDoesNotExist(destination);
        if (transfer.getSourceDeviceId() == null)
            insert(transfer);
        else if (transfer.getDestinationDeviceId() == null)
            delete(transfer);
        else
            move(transfer);

        transferSleep.putIfAbsent(transfer, new Semaphore(0, true));
        organiser.demandTransfer(transfer);
        //System.err.println("Transfer of " + transfer.getComponentId() + " is sleeping before preparing");
        try {
            transferSleep.get(transfer).acquire();
        } catch (InterruptedException e) {
            panic(e);
        }
        //System.err.println("Transfer of " + transfer.getComponentId() + " is waking up before preparing");
        transfer.prepare();
        organiser.preparationFinished(transfer);
        //System.err.println("Transfer of " + transfer.getComponentId() + " is sleeping before performing");
        try {
            transferSleep.get(transfer).acquire();
        } catch (InterruptedException e) {
            panic(e);
        }
        //System.err.println("Transfer of " + transfer.getComponentId() + " is waking up before performing");
        transfer.perform();
        try {
            mutexComponentOperation.get(component).acquire();
        } catch (InterruptedException e) {
            panic(e);
        }
        componentsOperatedOn.remove(component);
        mutexComponentOperation.get(component).release();
    }

    private void insert(ComponentTransfer transfer) throws TransferException {
        ComponentId component = transfer.getComponentId();
        assertComponentIsNew(component);
    }

    private void move(ComponentTransfer transfer) throws TransferException {
        DeviceId destination = transfer.getDestinationDeviceId();
        DeviceId source = transfer.getSourceDeviceId();
        ComponentId component = transfer.getComponentId();
        assertComponentExists(component, source);
        if (source.equals(destination))
            throw new ComponentDoesNotNeedTransfer(component, destination);
    }

    private void delete(ComponentTransfer transfer) throws TransferException {
        DeviceId source = transfer.getSourceDeviceId();
        ComponentId component = transfer.getComponentId();
        assertComponentExists(component, source);
    }

    private boolean deviceExists(DeviceId id){
        return deviceFreeSlots.containsKey(id);
    }

    private void assertComponentExists(ComponentId compId, DeviceId device) throws ComponentDoesNotExist {
        // no need to close mutex since there can be only one process
        // operating on a component (which is checked earlier)
        if (!componentPlacement.containsKey(compId) || // TODO: is this condition necessary?
                !device.equals(componentPlacement.get(compId))){
            throw new ComponentDoesNotExist(compId, device);
        }
    }
    private void assertComponentIsNew(ComponentId compId) throws ComponentAlreadyExists {
        // no need to close mutex since there can be only one process
        // operating on a component (which is checked earlier)
        if (componentPlacement.containsKey(compId))
            throw new ComponentAlreadyExists(compId, componentPlacement.get(compId));
    }
    private void prepareMutexes(ComponentId compId){
        mutexComponentOperation.putIfAbsent(compId, new Semaphore(1, true));
    }
    private void panic(Exception e){
        throw new RuntimeException("panic: unexpected thread interruption", e);
    }

    private class TransferOrganiser {
        private final Map<DeviceId, List<ComponentTransfer>> awaitingTransfers;
        private final Map<DeviceId, List<ComponentTransfer>> preparingFreeTransfers;
        private final Map<ComponentTransfer, ComponentTransfer> transferIDependOn;
        private final Map<ComponentTransfer, ComponentTransfer> transferTakingMyPlace;
        private final Semaphore mutex;

        private TransferOrganiser() {
            awaitingTransfers = new HashMap<>();
            preparingFreeTransfers = new HashMap<>();
            transferIDependOn = new HashMap<>();
            transferTakingMyPlace = new HashMap<>();
            deviceFreeSlots.forEach((devId, capacity)->
                    awaitingTransfers.put(devId, new LinkedList<>()));
            deviceFreeSlots.forEach((devId, capacity)->
                    preparingFreeTransfers.put(devId, new LinkedList<>()));
            mutex = new Semaphore(1, true);
        }

        private ComponentTransfer dfs(DeviceId device, ComponentTransfer transfer) {
            if (device == null)
                return null;
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
                    ComponentTransfer ans = dfs(t.getSourceDeviceId(), transfer);
                    if (ans != null) {
                        //awaitingTransfers.get(source).remove(ans);
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

        private void demandTransfer(ComponentTransfer transfer) {
            DeviceId destination = transfer.getDestinationDeviceId();
            DeviceId source = transfer.getSourceDeviceId();
            try {
                mutex.acquire();
            } catch (InterruptedException e) {
                panic(e);
            }
            if (destination == null) {
                // TODO: wywalić pustego ifa XD
            }
            // will not be done simultaneously by many processes thanks to the mutex
            else if (deviceFreeSlots.get(destination).get() > 0) {
                //System.err.println("We have free slots for component " + transfer.getComponentId());
                deviceFreeSlots.get(destination).decrementAndGet();
            }
            // state of preparingFreeTransfers will not change thanks to mutex
            else if (!preparingFreeTransfers.get(destination).isEmpty()) {
                ComponentTransfer other = preparingFreeTransfers.get(destination).get(0);
                // current transfer will have to wait with perform()
                // until the other has finished its prepare()
                transferIDependOn.put(transfer, other);
                transferTakingMyPlace.put(other, transfer);
                preparingFreeTransfers.get(destination).remove(0);
            }
            else {
                //System.err.println("Transfer of component " + transfer.getComponentId() + " is now awaiting");
                ComponentTransfer dfsResult = dfs(source, transfer);
                if (dfsResult == null) {
                    awaitingTransfers.get(destination).add(transfer);
                }
                else {
                    transferIDependOn.put(dfsResult, transfer);
                    transferTakingMyPlace.put(transfer, dfsResult);
                    //transferIDependOn.forEach((t1, t2) -> System.err.println(t1.getComponentId() + "<-" + t2.getComponentId()));
                    //transferTakingMyPlace.forEach((t1, t2) -> System.err.println(t1.getComponentId() + "->" + t2.getComponentId()));
                    //System.out.println("Transfer of " + transfer.getComponentId() + " has finished finding cycle");
                    transferSleep.get(transfer).release();
                }
                mutex.release();
                return;
            }

            temporaryName(transfer);
            transferSleep.get(transfer).release();

            mutex.release();
        }
        //FIXME: give name
        private void temporaryName(ComponentTransfer transfer){
            DeviceId source = transfer.getSourceDeviceId();
            if (source != null) {
                if (awaitingTransfers.get(source).isEmpty()) {
                    preparingFreeTransfers.get(source).add(transfer);
                } else {
                    ComponentTransfer waiting = awaitingTransfers.get(source).get(0);
                    //System.err.println("Waking up the transfer of " + waiting.getComponentId());
                    awaitingTransfers.get(source).remove(0);
                    transferIDependOn.put(waiting, transfer);
                    transferTakingMyPlace.put(transfer, waiting);
                    temporaryName(waiting);
                    transferSleep.get(waiting).release();
                }
            }
        }

        private void preparationFinished(ComponentTransfer transfer) {
            // mutex is used to manipulate deviceFreeSlots and preparingFreeTransfers TODO: and?
            // TODO: deviceFreeSlots chyba nie trzeba chronić
            try {
                mutex.acquire();
            } catch (InterruptedException e) {
                panic(e);
            }
            //System.err.println("Transfer of " + transfer.getComponentId() + " has finished preparation");
            DeviceId source = transfer.getSourceDeviceId();
            DeviceId destination = transfer.getDestinationDeviceId();
            ComponentId component = transfer.getComponentId();
            if (source != null) {
                if (preparingFreeTransfers.get(source).contains(transfer)) {
                    // free slot on the source device
                    deviceFreeSlots.get(source).incrementAndGet();
                    preparingFreeTransfers.get(source).remove(transfer);
                }
                else {
                    // a transfer wants to take my place
                    ComponentTransfer other = transferTakingMyPlace.get(transfer);
                    transferTakingMyPlace.remove(transfer);
                    transferSleep.get(other).release();
                }
            }
            if (!transferIDependOn.containsKey(transfer)) {
                // transfer can be performed
                //if (destination != null)
                 //   deviceFreeSlots.get(destination).decrementAndGet();
                transferSleep.get(transfer).release();
            }
            else {
                // transferSleep will be released by the other transfer
                // no slot was publicly freed, so no operating on deviceFreeSlots
                transferIDependOn.remove(transfer);
            }
            mutex.release();

            if (destination == null)
                componentPlacement.remove(component);
            else
                componentPlacement.put(component, destination);
        }
    }

    public void temporaryPrint() {
        componentPlacement.forEach((comp, where) -> System.out.println(comp + "->" + where));
        //organiser.awaitingTransfers.forEach((dev, list) -> System.out.println(dev + "<-" + list.size()));
        deviceFreeSlots.forEach((dev, amount) -> System.out.println(dev + ": " + amount));
    }
}
