package cp2023.testing;

import cp2023.base.ComponentId;
import cp2023.base.ComponentTransfer;
import cp2023.base.DeviceId;
import cp2023.base.StorageSystem;
import cp2023.exceptions.TransferException;
import cp2023.solution.StorageSystemFactory;

import java.util.HashMap;
import java.util.Objects;

public class Generators {
    static StorageSystem basicSystem1() {
        DeviceId dev1 = new DeviceId(1);
        DeviceId dev2 = new DeviceId(2);

        ComponentId comp1 = new ComponentId(101);
        ComponentId comp2 = new ComponentId(102);
        ComponentId comp3 = new ComponentId(103);

        HashMap<DeviceId, Integer> deviceCapacities = new HashMap<>(2);
        deviceCapacities.put(dev1, 2);
        deviceCapacities.put(dev2, 2);

        HashMap<ComponentId, DeviceId> initialComponentMapping = new HashMap<>(3);

        initialComponentMapping.put(comp1, dev1);
        initialComponentMapping.put(comp2, dev1);
        initialComponentMapping.put(comp3, dev2);

        return StorageSystemFactory.newSystem(deviceCapacities, initialComponentMapping);
    }

    static StorageSystem basicSystem2() {
        DeviceId dev1 = new DeviceId(1);
        DeviceId dev2 = new DeviceId(2);

        ComponentId comp1 = new ComponentId(101);
        ComponentId comp2 = new ComponentId(102);
        ComponentId comp3 = new ComponentId(103);

        HashMap<DeviceId, Integer> deviceCapacities = new HashMap<>(2);
        deviceCapacities.put(dev1, 2);
        deviceCapacities.put(dev2, 1);

        HashMap<ComponentId, DeviceId> initialComponentMapping = new HashMap<>(3);

        initialComponentMapping.put(comp1, dev1);
        initialComponentMapping.put(comp2, dev2);
        initialComponentMapping.put(comp3, dev1);

        return StorageSystemFactory.newSystem(deviceCapacities, initialComponentMapping);
    }

    static StorageSystem basicSystem3(int size) {
        HashMap<DeviceId, Integer> deviceCapacities = new HashMap<>();
        HashMap<ComponentId, DeviceId> initialComponentMapping = new HashMap<>();

        for (int i = 1; i <= size; i++) {
            DeviceId dev = new DeviceId(i);
            ComponentId comp1 = new ComponentId(100 + i);
            ComponentId comp2 = new ComponentId(200 + i);
            deviceCapacities.put(dev, 2);
            initialComponentMapping.put(comp1, dev);
            initialComponentMapping.put(comp2, dev);
        }

        return StorageSystemFactory.newSystem(deviceCapacities, initialComponentMapping);
    }

    static DeviceId device(int id) {
        return new DeviceId(id);
    }

    static ComponentId component(int id) {
        return new ComponentId(id);
    }

    static ComponentTransfer transfer(int comp, int source, int dest) {
        return new SimpleTransfer(Generators.component(comp),
                (source == -1) ? null : Generators.device(source),
                (dest == -1) ? null : Generators.device(dest));
    }

    static ComponentTransfer transfer2(int comp, int source, int dest) {
        return new HaltPrepare(Generators.component(comp),
                (source == -1) ? null : Generators.device(source),
                (dest == -1) ? null : Generators.device(dest));
    }

    static ComponentTransfer transfer3(int comp, int source, int dest) {
        return new HaltTransfer(Generators.component(comp),
                (source == -1) ? null : Generators.device(source),
                (dest == -1) ? null : Generators.device(dest));
    }

    static CallbackTransfer transfer4(int comp, int source, int dest, CallbackInterface callback) {
        return new CallbackTransfer(Generators.component(comp),
                (source == -1) ? null : Generators.device(source),
                (dest == -1) ? null : Generators.device(dest), callback);
    }

    static void execTransfer(StorageSystem system, ComponentTransfer transfer) {
        try {
            system.execute(transfer);
        } catch (TransferException e) {
            throw new RuntimeException("Unexpected transfer exception: " + e, e);
        } catch (RuntimeException e) {
            if (!Objects.equals(e.getMessage(), "panic: unexpected thread interruption"))
                throw e;
        }
    }
}
