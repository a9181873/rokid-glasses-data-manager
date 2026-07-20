package tw.dky.rokidfiles.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Shared media-path policy used by both direct access and the read-only MediaStore fallback. */
final class MediaAllowlist {
    static final List<String> RELATIVE_ROOTS = Collections.unmodifiableList(Arrays.asList(
            "DCIM/Camera",
            "DCIM/album",
            "Pictures",
            "Movies"
    ));

    private MediaAllowlist() {
    }

    /** Accepts an allowlisted directory or any descendant, using case-sensitive path segments. */
    static boolean containsRelativePath(String value) {
        if (value == null || value.isEmpty() || value.charAt(0) == '/'
                || value.indexOf('\\') >= 0 || value.indexOf('\u0000') >= 0) {
            return false;
        }
        String normalized = value;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return false;
        }
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        for (String root : RELATIVE_ROOTS) {
            if (normalized.equals(root) || normalized.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates a legacy MediaStore DATA value lexically and canonically, then returns its parent
     * path relative to shared storage. A null return means the row is outside the allowlist.
     */
    static String legacyParentRelativePath(File externalRoot, String dataPath) throws IOException {
        if (externalRoot == null || dataPath == null || dataPath.isEmpty()) {
            return null;
        }
        Path external = externalRoot.toPath().toAbsolutePath().normalize();
        Path candidate = new File(dataPath).toPath().toAbsolutePath().normalize();
        if (!candidate.startsWith(external)) {
            return null;
        }
        String relative = external.relativize(candidate).toString()
                .replace(File.separatorChar, '/');
        if (!containsRelativePath(relative)) {
            return null;
        }

        String canonicalCandidate = candidate.toFile().getCanonicalPath();
        boolean canonicalAllowed = false;
        for (String root : RELATIVE_ROOTS) {
            String canonicalRoot = new File(externalRoot, root).getCanonicalPath();
            if (canonicalCandidate.startsWith(canonicalRoot + File.separator)) {
                canonicalAllowed = true;
                break;
            }
        }
        if (!canonicalAllowed) {
            return null;
        }

        int separator = relative.lastIndexOf('/');
        return separator < 0 ? null : relative.substring(0, separator + 1);
    }
}
