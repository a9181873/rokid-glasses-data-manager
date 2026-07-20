package tw.dky.rokidfiles.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class MediaAllowlistTest {
    @Test
    public void acceptsOnlyExactRootsAndTheirDescendants() {
        assertTrue(MediaAllowlist.containsRelativePath("DCIM/Camera/"));
        assertTrue(MediaAllowlist.containsRelativePath("DCIM/Camera/trip/photo.jpg"));
        assertTrue(MediaAllowlist.containsRelativePath("DCIM/album/photo.jpg"));
        assertTrue(MediaAllowlist.containsRelativePath("Pictures/saved/photo.png"));
        assertTrue(MediaAllowlist.containsRelativePath("Movies/video.mp4"));

        assertFalse(MediaAllowlist.containsRelativePath("DCIM/CameraBackup/photo.jpg"));
        assertFalse(MediaAllowlist.containsRelativePath("DCIM/camera/photo.jpg"));
        assertFalse(MediaAllowlist.containsRelativePath("Download/photo.jpg"));
        assertFalse(MediaAllowlist.containsRelativePath("Pictures/../Download/photo.jpg"));
        assertFalse(MediaAllowlist.containsRelativePath("/Pictures/photo.jpg"));
        assertFalse(MediaAllowlist.containsRelativePath("Pictures\\photo.jpg"));
        assertFalse(MediaAllowlist.containsRelativePath(null));
    }

    @Test
    public void validatesLegacyAbsolutePathAndReturnsParent() throws Exception {
        Path external = Files.createTempDirectory("media-allowlist-test");
        Path outside = Files.createTempDirectory("media-allowlist-outside");
        try {
            Path camera = Files.createDirectories(external.resolve("DCIM/Camera"));
            Path media = Files.createFile(camera.resolve("photo.jpg"));
            assertEquals("DCIM/Camera/", MediaAllowlist.legacyParentRelativePath(
                    external.toFile(), media.toString()));

            Path download = Files.createDirectories(external.resolve("Download"));
            Path rejected = Files.createFile(download.resolve("photo.jpg"));
            assertNull(MediaAllowlist.legacyParentRelativePath(
                    external.toFile(), rejected.toString()));

            Path outsideMedia = Files.createFile(outside.resolve("private.jpg"));
            Path pictures = Files.createDirectories(external.resolve("Pictures"));
            Path indirect = Files.createSymbolicLink(
                    pictures.resolve("indirect.jpg"), outsideMedia);
            assertNull(MediaAllowlist.legacyParentRelativePath(
                    external.toFile(), indirect.toString()));
        } finally {
            deleteTree(external);
            deleteTree(outside);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException failure) {
                    throw new RuntimeException(failure);
                }
            });
        }
    }
}
