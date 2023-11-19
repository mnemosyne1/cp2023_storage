package cp2023.testing;

import cp2023.base.ComponentId;
import cp2023.base.ComponentTransfer;
import cp2023.base.DeviceId;

public class SimpleTransfer implements ComponentTransfer {

    ComponentId componentId;
    DeviceId source, target;

    @Override
    public ComponentId getComponentId() {
        return componentId;
    }

    @Override
    public DeviceId getSourceDeviceId() {
        return source;
    }

    @Override
    public DeviceId getDestinationDeviceId() {
        return target;
    }

    @Override
    public void prepare() {

    }

    @Override
    public void perform() {

    }

    public SimpleTransfer(ComponentId componentId, DeviceId source, DeviceId target){
        this.componentId = componentId;
        this.source = source;
        this.target = target;
    }

}
