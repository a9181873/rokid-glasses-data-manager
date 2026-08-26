package tw.dky.rokidfiles.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class GatewayMediaAccessTest {
    @Test
    public void shareLayerCollectorStopsAtTheItemLimit() {
        List<Integer> items = new ArrayList<>();
        boolean accepted = true;
        for (int index = 0; index < 10_001; index++) {
            accepted = GatewayMediaAccess.addIfRoom(items, index, 10_000);
        }

        assertEquals(10_000, items.size());
        assertFalse(accepted);
    }
}
