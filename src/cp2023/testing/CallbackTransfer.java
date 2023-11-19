package cp2023.testing;

import cp2023.base.ComponentId;
import cp2023.base.DeviceId;

public class CallbackTransfer extends SimpleTransfer {

    private final CallbackInterface callback;

    @Override
    public void prepare() {
        callback.call(getComponentId(),false);
    }

    @Override
    public void perform() {
        callback.call(getComponentId(),true);
    }
    public CallbackTransfer(ComponentId componentId, DeviceId source, DeviceId target, CallbackInterface callback) {
        super(componentId, source, target);
        this.callback = callback;
    }
}
