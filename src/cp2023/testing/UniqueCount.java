package cp2023.testing;

import cp2023.base.ComponentId;

import java.util.HashSet;
import java.util.Set;

public class UniqueCount implements CallbackInterface {

    private final Set<ComponentId> prepared, performed;
    boolean valid = true;

    @Override
    public synchronized void call(ComponentId transfer, boolean isSecondPhase) {
        if(isSecondPhase){
            if(performed.contains(transfer) || !prepared.contains(transfer))
                valid = false;
            performed.add(transfer);
        }
        else{
            if(performed.contains(transfer) || prepared.contains(transfer))
                valid = false;
            prepared.add(transfer);
        }
    }

    public synchronized int result(){
        if(!valid) return -1;
        //System.err.println(prepared.size() + ", " + performed.size());
        return prepared.size() + performed.size();
    }

    public UniqueCount(){
        prepared = new HashSet<>();
        performed = new HashSet<>();
    }
}
