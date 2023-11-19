package cp2023.testing;

import cp2023.base.ComponentId;
import cp2023.base.DeviceId;

public class HaltTransfer extends SimpleTransfer {
    public HaltTransfer(ComponentId componentId, DeviceId source, DeviceId target) {
        super(componentId, source, target);
    }

    @Override
    public void perform() {
        while (true) {
        }
    }

}
