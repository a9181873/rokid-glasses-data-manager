package tw.dky.rokidfiles.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** App-private flags. No favorite/protection/duplicate metadata is written into the media file. */
public final class MediaMetadataStore {
    private static final String PREFERENCES = "rokid_files_user_metadata_v1";
    private static final String FAVORITE = "fav.";
    private static final String PROTECTED = "protected.";
    private static final String DUPLICATE = "duplicate.";

    private final SharedPreferences preferences;

    public MediaMetadataStore(Context context) {
        preferences = Objects.requireNonNull(context, "context")
                .getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public MediaItem decorate(MediaItem item) {
        String suffix = suffix(item);
        String duplicate = validatedDuplicateGroup(
                item, preferences.getString(DUPLICATE + suffix, null));
        return item.withUserMetadata(
                preferences.getBoolean(FAVORITE + suffix, false),
                preferences.getBoolean(PROTECTED + suffix, false),
                duplicate);
    }

    public boolean setFavorite(MediaItem item, boolean favorite) {
        return putBoolean(FAVORITE + suffix(item), favorite);
    }

    public boolean setProtected(MediaItem item, boolean protectedItem) {
        return putBoolean(PROTECTED + suffix(item), protectedItem);
    }

    public boolean setDuplicateGroup(MediaItem item, String duplicateGroup) {
        SharedPreferences.Editor editor = preferences.edit();
        String key = DUPLICATE + suffix(item);
        if (duplicateGroup == null || duplicateGroup.isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, duplicateValue(item, duplicateGroup));
        }
        return editor.commit();
    }

    /** Replaces the duplicate index in one durable commit after a complete scan. */
    public synchronized boolean replaceDuplicateGroups(Map<MediaItem, String> groups) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(DUPLICATE)) {
                editor.remove(key);
            }
        }
        for (Map.Entry<MediaItem, String> entry : groups.entrySet()) {
            editor.putString(
                    DUPLICATE + suffix(entry.getKey()),
                    duplicateValue(entry.getKey(), entry.getValue()));
        }
        return editor.commit();
    }

    /** Keeps flags attached when a backend changes an opaque ID during rename/move. */
    public synchronized boolean migrate(MediaItem oldItem, MediaItem newItem) {
        String oldSuffix = suffix(oldItem);
        String newSuffix = suffix(newItem);
        if (oldSuffix.equals(newSuffix)) {
            return true;
        }
        SharedPreferences.Editor editor = preferences.edit();
        migrateBoolean(editor, FAVORITE, oldSuffix, newSuffix);
        migrateBoolean(editor, PROTECTED, oldSuffix, newSuffix);
        String duplicateGroup = preferences.getString(DUPLICATE + oldSuffix, null);
        if (duplicateGroup != null) {
            editor.remove(DUPLICATE + oldSuffix)
                    .putString(DUPLICATE + newSuffix, duplicateGroup);
        }
        return editor.commit();
    }

    private boolean putBoolean(String key, boolean value) {
        SharedPreferences.Editor editor = preferences.edit();
        if (value) {
            editor.putBoolean(key, true);
        } else {
            editor.remove(key);
        }
        return editor.commit();
    }

    private void migrateBoolean(
            SharedPreferences.Editor editor, String prefix, String oldSuffix, String newSuffix) {
        if (preferences.getBoolean(prefix + oldSuffix, false)) {
            editor.remove(prefix + oldSuffix).putBoolean(prefix + newSuffix, true);
        }
    }

    private static String suffix(MediaItem item) {
        String identity = item.getBackend().name() + '\u0000' + item.getId();
        return Base64.encodeToString(
                identity.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String duplicateValue(MediaItem item, String group) {
        return item.getSizeBytes() + ":" + item.getLastModifiedMillis() + ":" + group;
    }

    private static String validatedDuplicateGroup(MediaItem item, String stored) {
        if (stored == null) return null;
        String prefix = item.getSizeBytes() + ":" + item.getLastModifiedMillis() + ":";
        if (!stored.startsWith(prefix) || stored.length() == prefix.length()) {
            return null;
        }
        return stored.substring(prefix.length());
    }

}
