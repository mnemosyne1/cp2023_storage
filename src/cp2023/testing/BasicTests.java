package cp2023.testing;

import cp2023.base.ComponentTransfer;
import cp2023.base.StorageSystem;
import cp2023.exceptions.TransferException;
import org.junit.jupiter.api.Test;

public class BasicTests extends Generators {

    @Test
        // https://discord.com/channels/999695571575119902/1164480906371813426/1173215886493106296
        // Jak ktoś się o to zapytał to można od razu przetestować
    void Path3() throws InterruptedException {
        StorageSystem system = basicSystem3(2);
        UniqueCount count1 = new UniqueCount();
        UniqueCount count2 = new UniqueCount();
        ComponentTransfer transfer1 = transfer4(101, 1, 2, count1);
        ComponentTransfer transfer2 = transfer4(301, -1, 1, count2);
        ComponentTransfer transfer3 = transfer2(102, 2, -1);

        Thread t1 = new Thread(() -> execTransfer(system, transfer1));
        Thread t2 = new Thread(() -> execTransfer(system, transfer2));
        Thread t3 = new Thread(() -> execTransfer(system, transfer3));

        t1.start();
        Thread.sleep(100);

        t2.start();
        Thread.sleep(100);

        t3.start();
        Thread.sleep(100);

        assert (count1.result() == 1);
        assert (count2.result() == 2);

        // Możemy sobie przerwać wątki i o nich zapomnieć, test już sprawdził, co miał sprawdzić,
        // O ile nie jest to w pełni zgodnie z treścią, wszystkie wątki powinny się wtedy zakończyć.
        t1.interrupt();
        t2.interrupt();
        t3.interrupt();
    }
}
