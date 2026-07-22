package tw.dky.rokidfiles.storage;

/** 建立跨 MediaStore 重複資料列仍保持一致的邏輯檔案識別。 */
public final class MediaIdentity {
    private MediaIdentity() {
    }

    public static String logicalKey(MediaItem item) {
        return logicalKey(item.getRelativePath(), item.getDisplayName(), item.getId());
    }

    static String logicalKey(String relativePath, String displayName, String fallbackId) {
        String path = relativePath;
        if (path == null || path.isEmpty()) return "id:" + fallbackId;
        path = path.replace('\\', '/');
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String name = displayName;
        if (name != null && !name.isEmpty() && !path.endsWith("/" + name)
                && !path.equals(name)) {
            path += "/" + name;
        }
        return "path:" + path;
    }
}
