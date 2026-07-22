package tw.dky.rokidfiles.storage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class MediaIdentityTest {
    @Test
    public void mediaStoreRowsAtSamePathShareLogicalIdentity() {
        assertEquals(
                MediaIdentity.logicalKey("DCIM/Camera/", "IMG_1.jpg", "ms.10"),
                MediaIdentity.logicalKey("DCIM/Camera/", "IMG_1.jpg", "ms.11"));
    }

    @Test
    public void directPathDoesNotAppendNameTwice() {
        assertEquals("path:DCIM/Camera/IMG_1.jpg",
                MediaIdentity.logicalKey(
                        "DCIM/Camera/IMG_1.jpg", "IMG_1.jpg", "direct.1"));
    }
}
