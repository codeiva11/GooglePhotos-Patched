package app.morphe.extension.shared.patches;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;
import app.morphe.extension.shared.Logger;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Bytecode hooks and initializers for default Google Photos backup settings.
 *
 * Guarantees by default on fresh install or upgrade:
 * 1. Daily data cap = Long.MAX_VALUE (Unlimited mobile data)
 * 2. Back up videos over data = true
 * 3. Back up while roaming = true
 * 4. Back up photos over data = true
 * 5. Device Camera folder is checked and automatically included in backup.
 * 6. Preserves any manual changes the user makes in the UI.
 */
public final class BackupSettingsHooks {
    private BackupSettingsHooks() {}

    private static final String BACKUP_PREFS_FILE = "photos.backup.backup_prefs";
    private static final String KEY_APPLIED = "morphe_backup_defaults_applied_v6";

    // Preference keys from APK disassembly (rzs enum)
    private static final String KEY_DAILY_DATA_CAP = "backup_prefs_daily_data_cap";
    private static final String KEY_USE_UNRESTRICTED_DATA = "use_unrestricted_data";
    private static final String KEY_HAS_UNRESTRICTED_DATA_OPTIONS = "has_unrestricted_data_options";
    private static final String KEY_BACKUP_WHEN_ROAMING = "backup_prefs_backup_when_roaming";
    private static final String KEY_USE_DATA_FOR_PHOTOS = "backup_prefs_use_data_for_photos";
    private static final String KEY_USE_DATA_FOR_VIDEOS = "backup_prefs_use_data_for_videos";
    private static final String KEY_LOCAL_BACKUP_FOLDERS = "photos.backup.backup_local_folders";

    // Pre-calculated bucket IDs for common Android /DCIM/Camera paths
    private static final Set<String> KNOWN_CAMERA_BUCKET_IDS = new HashSet<>();
    static {
        KNOWN_CAMERA_BUCKET_IDS.add("-1739773001"); // /storage/emulated/0/dcim/camera
        KNOWN_CAMERA_BUCKET_IDS.add("-1220927529"); // /storage/emulated/0/DCIM/Camera
        KNOWN_CAMERA_BUCKET_IDS.add("866175794");   // /sdcard/DCIM/Camera
        KNOWN_CAMERA_BUCKET_IDS.add("347330322");   // /sdcard/dcim/camera
        KNOWN_CAMERA_BUCKET_IDS.add("571434596");   // /storage/self/primary/DCIM/Camera
        KNOWN_CAMERA_BUCKET_IDS.add("52589124");    // /storage/self/primary/dcim/camera
        KNOWN_CAMERA_BUCKET_IDS.add("-847176570");  // /storage/emulated/legacy/DCIM/Camera
        KNOWN_CAMERA_BUCKET_IDS.add("-1366022042"); // /storage/emulated/legacy/dcim/camera
    }

    /**
     * Resolves all known and device-specific MediaStore bucket IDs for the Camera folder.
     */
    public static Set<String> resolveCameraBucketIds(Context context) {
        Set<String> set = new HashSet<>(KNOWN_CAMERA_BUCKET_IDS);
        if (context == null) return set;

        try {
            File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            if (dcim != null) {
                File cam = new File(dcim, "Camera");
                set.add(String.valueOf(cam.getAbsolutePath().hashCode()));
                set.add(String.valueOf(cam.getAbsolutePath().toLowerCase(Locale.US).hashCode()));
            }
        } catch (Throwable ignored) {}

        try {
            ContentResolver cr = context.getContentResolver();
            if (cr != null) {
                try (Cursor cursor = cr.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        new String[] { MediaStore.Images.Media.BUCKET_ID },
                        MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " = ? COLLATE NOCASE",
                        new String[] { "Camera" },
                        null)) {
                    if (cursor != null) {
                        int col = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID);
                        while (cursor.moveToNext()) {
                            String bId = cursor.getString(col);
                            if (bId != null && !bId.isEmpty()) {
                                set.add(bId);
                            }
                        }
                    }
                }

                try (Cursor cursor = cr.query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        new String[] { MediaStore.Video.Media.BUCKET_ID },
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME + " = ? COLLATE NOCASE",
                        new String[] { "Camera" },
                        null)) {
                    if (cursor != null) {
                        int col = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
                        while (cursor.moveToNext()) {
                            String bId = cursor.getString(col);
                            if (bId != null && !bId.isEmpty()) {
                                set.add(bId);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        return set;
    }

    /**
     * Called in BackupPreferences.Builder.<init>() to set default values for mobile data.
     */
    public static void initBackupPreferencesBuilder(Object builder) {
        if (builder == null) return;
        try {
            Class<?> clazz = builder.getClass();
            setFieldSilently(clazz, builder, "b", boolean.class, true); // has_unrestricted_data_options
            setFieldSilently(clazz, builder, "c", boolean.class, true); // use_unrestricted_data
            setFieldSilently(clazz, builder, "d", boolean.class, true); // use_data_for_photos
            setFieldSilently(clazz, builder, "e", boolean.class, true); // use_data_for_videos
            setFieldSilently(clazz, builder, "g", boolean.class, true); // backup_when_roaming
            setFieldSilently(clazz, builder, "f", long.class, Long.MAX_VALUE); // daily_data_cap
        } catch (Throwable t) {
            Logger.printException(() -> "Morphe: Error in initBackupPreferencesBuilder", t);
        }
    }

    /**
     * Seeds the default backup settings into SharedPreferences.
     */
    public static void seedDefaults(Context context) {
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(BACKUP_PREFS_FILE, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_APPLIED, false)) {
                return;
            }

            Set<String> cameraBuckets = resolveCameraBucketIds(context);
            Set<String> existingFolders = prefs.getStringSet(KEY_LOCAL_BACKUP_FOLDERS, null);
            Set<String> mergedFolders = new HashSet<>(cameraBuckets);
            if (existingFolders != null) {
                mergedFolders.addAll(existingFolders);
            }

            SharedPreferences.Editor editor = prefs.edit();
            editor.putLong(KEY_DAILY_DATA_CAP, Long.MAX_VALUE);
            editor.putBoolean(KEY_USE_UNRESTRICTED_DATA, true);
            editor.putBoolean(KEY_HAS_UNRESTRICTED_DATA_OPTIONS, true);
            editor.putBoolean(KEY_BACKUP_WHEN_ROAMING, true);
            editor.putBoolean(KEY_USE_DATA_FOR_PHOTOS, true);
            editor.putBoolean(KEY_USE_DATA_FOR_VIDEOS, true);
            editor.putStringSet(KEY_LOCAL_BACKUP_FOLDERS, mergedFolders);
            editor.putBoolean(KEY_APPLIED, true);
            editor.commit(); // Synchronous commit to ensure immediate visibility

            Logger.printInfo(() -> "Morphe: Seeded backup defaults (Unlimited, Roaming, Photos+Videos, Camera folders: " + mergedFolders.size() + ")");
        } catch (Throwable t) {
            Logger.printException(() -> "Morphe: Failed to seed backup defaults", t);
        }
    }

    /**
     * Hooked in BackupPreferencesStore.c() before return. Ensures default settings are present.
     */
    public static void wrapBackupPreferences(Object rzuObj, Object rzvStore) {
        if (rzuObj == null || rzvStore == null) return;
        try {
            Context context = getContextFromStore(rzvStore);
            if (context == null) return;

            SharedPreferences prefs = context.getSharedPreferences(BACKUP_PREFS_FILE, Context.MODE_PRIVATE);
            boolean applied = prefs.getBoolean(KEY_APPLIED, false);

            if (!applied) {
                seedDefaults(context);

                Class<?> rzuClass = rzuObj.getClass();

                // Mutate fields on rzuObj (use_data_for_photos, use_data_for_videos, daily_data_cap, backup_when_roaming)
                setFieldSilently(rzuClass, rzuObj, "c", boolean.class, true);
                setFieldSilently(rzuClass, rzuObj, "d", boolean.class, true);
                setFieldSilently(rzuClass, rzuObj, "e", boolean.class, true);
                setFieldSilently(rzuClass, rzuObj, "f", boolean.class, true);
                setFieldSilently(rzuClass, rzuObj, "g", long.class, Long.MAX_VALUE);
                setFieldSilently(rzuClass, rzuObj, "h", boolean.class, true);

                // Update folder set in rzuObj.s (photos.backup.backup_local_folders)
                Set<String> cameraBuckets = resolveCameraBucketIds(context);
                Set<String> existingFolders = prefs.getStringSet(KEY_LOCAL_BACKUP_FOLDERS, null);
                Set<String> mergedFolders = new HashSet<>(cameraBuckets);
                if (existingFolders != null) {
                    mergedFolders.addAll(existingFolders);
                }

                try {
                    Class<?> immutableSetClass = rzuClass.getClassLoader().loadClass("com.google.common.collect.ImmutableSet");
                    Method copyOfMethod = null;
                    for (Method m : immutableSetClass.getMethods()) {
                        if (Modifier.isStatic(m.getModifiers()) &&
                                m.getReturnType().equals(immutableSetClass) &&
                                m.getParameterTypes().length == 1 &&
                                Collection.class.isAssignableFrom(m.getParameterTypes()[0])) {
                            copyOfMethod = m;
                            break;
                        }
                    }
                    if (copyOfMethod != null) {
                        Object newImmutableSet = copyOfMethod.invoke(null, mergedFolders);
                        Field sField = rzuClass.getDeclaredField("s");
                        sField.setAccessible(true);
                        sField.set(rzuObj, newImmutableSet);
                    }
                } catch (Throwable ignored) {}

                // Also update rzvStore.e cached field
                try {
                    Field eField = rzvStore.getClass().getDeclaredField("e");
                    eField.setAccessible(true);
                    eField.set(rzvStore, rzuObj);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Morphe: Error in wrapBackupPreferences", t);
        }
    }

    private static Context getContextFromStore(Object rzvStore) {
        if (rzvStore == null) return null;
        try {
            Field bField = rzvStore.getClass().getDeclaredField("b");
            bField.setAccessible(true);
            Object bVal = bField.get(rzvStore);
            if (bVal instanceof Context) {
                return (Context) bVal;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void setFieldSilently(Class<?> clazz, Object target, String name, Class<?> type, Object value) {
        try {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Throwable ignored) {}
    }
}
