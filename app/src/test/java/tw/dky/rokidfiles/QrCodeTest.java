package tw.dky.rokidfiles;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class QrCodeTest {
    @Test
    public void createsExpectedVersionThreeFinderPatterns() {
        QrCode code = QrCode.encodeText("http://192.168.43.20:8765");
        assertTrue(code.get(3, 3));
        assertTrue(code.get(QrCode.SIZE - 4, 3));
        assertTrue(code.get(3, QrCode.SIZE - 4));
        assertFalse(code.get(-1, 0));
        assertFalse(code.get(QrCode.SIZE, 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPayloadBeyondVersionThreeCapacity() {
        QrCode.encodeText("x".repeat(54));
    }
}
