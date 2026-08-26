package tw.dky.rokidfiles;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DiagnosticsTest {
    @Test
    public void diagnosticRingBufferNeverExceedsTwoHundredEvents() {
        for (int index = 0; index < 205; index++) {
            Diagnostics.info("event-" + index);
        }

        assertTrue(Diagnostics.size() == 200);
    }

    @Test
    public void diagnosticEventRedactsPathsAndCredentials() {
        String sanitized = Diagnostics.sanitizeEvent(
                "read /sdcard/DCIM/private.jpg token=abc123 PIN=778899 cookie=session");

        assertFalse(sanitized.contains("/sdcard"));
        assertFalse(sanitized.contains("abc123"));
        assertFalse(sanitized.contains("778899"));
        assertFalse(sanitized.contains("session"));
        assertTrue(sanitized.contains("[路徑省略]"));
        assertTrue(sanitized.contains("[敏感值省略]"));
    }

    @Test
    public void diagnosticEventRemovesControlCharacters() {
        String sanitized = Diagnostics.sanitizeEvent("first\nsecond\u0000third");

        assertFalse(sanitized.contains("\n"));
        assertFalse(sanitized.contains("\u0000"));
    }

    @Test
    public void safeNameNeverReturnsParentDirectories() {
        assertTrue("diagnostics.txt".equals(
                Diagnostics.safeName("/data/private/diagnostics.txt")));
        assertTrue("diagnostics.txt".equals(
                Diagnostics.safeName("C:\\private\\diagnostics.txt")));
    }
}
