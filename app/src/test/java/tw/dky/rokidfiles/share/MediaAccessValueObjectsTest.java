package tw.dky.rokidfiles.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Collections;

import tw.dky.rokidfiles.share.MediaAccess.DuplicateGroup;
import tw.dky.rokidfiles.share.MediaAccess.Kind;
import tw.dky.rokidfiles.share.MediaAccess.MediaItem;
import tw.dky.rokidfiles.share.MediaAccess.ReadResource;

/** 驗證分享層值物件在資料進入伺服器前會維持安全的不變條件。 */
public final class MediaAccessValueObjectsTest {
    @Test
    public void mediaItemRejectsMissingIdentifiersAndClampsNegativeMetadata() {
        assertThrows(NullPointerException.class, () -> MediaItem.basic(
                null, "photo.jpg", "image/jpeg", Kind.PHOTO, 1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> MediaItem.basic(
                "", "photo.jpg", "image/jpeg", Kind.PHOTO, 1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> MediaItem.basic(
                "repo-1", "", "image/jpeg", Kind.PHOTO, 1L, 1L));

        MediaItem item = new MediaItem(
                "repo-1", "photo.jpg", null, null,
                -1L, -2L, -3L, -4, -5
        );
        assertEquals("application/octet-stream", item.getMimeType());
        assertEquals(Kind.OTHER, item.getKind());
        assertEquals(0L, item.getSize());
        assertEquals(0L, item.getModifiedEpochMillis());
        assertEquals(0L, item.getDurationMillis());
        assertEquals(0, item.getWidth());
        assertEquals(0, item.getHeight());
        assertFalse(item.canRename());
        assertFalse(item.canTrash());
        assertFalse(item.canRestore());
        assertFalse(item.canFavorite());
        assertFalse(item.canProtect());
    }

    @Test
    public void mediaItemCarriesOnlyExplicitlyGrantedCapabilities() {
        MediaItem item = new MediaItem(
                "repo-1", "photo.jpg", "image/jpeg", Kind.PHOTO,
                10L, 20L, 0L, 100, 80,
                true, false, false,
                true, false, false, true, true
        );

        assertTrue(item.canRename());
        assertFalse(item.canTrash());
        assertFalse(item.canRestore());
        assertTrue(item.canFavorite());
        assertTrue(item.canProtect());
    }

    @Test
    public void readResourceRejectsImpossibleLengths() {
        assertThrows(IllegalArgumentException.class, () -> new ReadResource(
                new ByteArrayInputStream(new byte[0]), "image/jpeg", -1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new ReadResource(
                new ByteArrayInputStream(new byte[0]), "image/jpeg", 2L, 1L));
    }

    @Test
    public void duplicateGroupRequiresAtLeastTwoItems() {
        MediaItem item = MediaItem.basic(
                "repo-1", "photo.jpg", "image/jpeg", Kind.PHOTO, 1L, 1L);

        assertThrows(IllegalArgumentException.class, () -> new DuplicateGroup(
                "sha256:value", Collections.singletonList(item), 0L));
    }
}
