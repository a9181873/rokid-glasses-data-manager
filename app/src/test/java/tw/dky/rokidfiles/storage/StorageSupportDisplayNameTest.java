package tw.dky.rokidfiles.storage;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class StorageSupportDisplayNameTest {
    @Test
    public void acceptsNormalUnicodeMediaName() {
        assertTrue(StorageSupport.isSafeDisplayName("沖繩旅行 01.jpg"));
    }

    @Test
    public void rejectsPathTraversalAndSeparators() {
        assertFalse(StorageSupport.isSafeDisplayName("."));
        assertFalse(StorageSupport.isSafeDisplayName(".."));
        assertFalse(StorageSupport.isSafeDisplayName("../secret.jpg"));
        assertFalse(StorageSupport.isSafeDisplayName("DCIM/Camera.jpg"));
        assertFalse(StorageSupport.isSafeDisplayName("DCIM\\Camera.jpg"));
    }

    @Test
    public void rejectsControlCharacters() {
        assertFalse(StorageSupport.isSafeDisplayName("photo\n.jpg"));
        assertFalse(StorageSupport.isSafeDisplayName("photo\u0000.jpg"));
    }

    @Test
    public void enforcesLengthBoundaryAfterTrimming() {
        assertTrue(StorageSupport.isSafeDisplayName(repeat('a', 240)));
        assertFalse(StorageSupport.isSafeDisplayName(repeat('a', 241)));
        assertFalse(StorageSupport.isSafeDisplayName("   "));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
