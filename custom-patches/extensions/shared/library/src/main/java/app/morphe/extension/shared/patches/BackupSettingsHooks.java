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
 * Guarantees by default on fresh install, upgrade, or account sign-in:
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
    private static final String KEY_USER_CUSTOMIZED_MOBILE_DATA = "morphe_user_customized_mobile_data";
    private static final String KEY_FOLDERS_SEEDED = "morphe_camera_folders_seeded_v1";

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
            boolean userCustomized = prefs.getBoolean(KEY_USER_CUSTOMIZED_MOBILE_DATA, false);
            boolean foldersSeeded = prefs.getBoolean(KEY_FOLDERS_SEEDED, false);

            SharedPreferences.Editor editor = prefs.edit();

            if (!userCustomized) {
                editor.putLong(KEY_DAILY_DATA_CAP, Long.MAX_VALUE);
                editor.putBoolean(KEY_USE_UNRESTRICTED_DATA, true);
                editor.putBoolean(KEY_HAS_UNRESTRICTED_DATA_OPTIONS, true);
                editor.putBoolean(KEY_BACKUP_WHEN_ROAMING, true);
                editor.putBoolean(KEY_USE_DATA_FOR_PHOTOS, true);
                editor.putBoolean(KEY_USE_DATA_FOR_VIDEOS, true);
            }

            if (!foldersSeeded) {
                Set<String> cameraBuckets = resolveCameraBucketIds(context);
                Set<String> existingFolders = prefs.getStringSet(KEY_LOCAL_BACKUP_FOLDERS, null);
                Set<String> mergedFolders = new HashSet<>(cameraBuckets);
                if (existingFolders != null) {
                    mergedFolders.addAll(existingFolders);
                }
                editor.putStringSet(KEY_LOCAL_BACKUP_FOLDERS, mergedFolders);
                editor.putBoolean(KEY_FOLDERS_SEEDED, true);
            }

            editor.commit();
            Logger.printInfo(() -> "Morphe: Seeded backup defaults (userCustomized=" + userCustomized + ", foldersSeeded=" + foldersSeeded + ")");
        } catch (Throwable t) {
            Logger.printException(() -> "Morphe: Failed to seed backup defaults", t);
        }
    }

    /**
     * Hooked at the beginning of BackupPreferencesStore.o() to intercept preference writes.
     */
    public static void onSavePreferences(Object oldRzu, Object newRzu, Object stlReason, Object rzvStore) {
        if (rzvStore == null || newRzu == null) return;
        try {
            Context context = getContextFromStore(rzvStore);
            if (context == null) return;

            SharedPreferences prefs = context.getSharedPreferences(BACKUP_PREFS_FILE, Context.MODE_PRIVATE);

            String reasonStr = stlReason != null ? String.valueOf(stlReason) : "";
            boolean isReset = reasonStr.contains("reset backup preferences");

            if (isReset) {
                Class<?> rzuClass = newRzu.getClass();
                setFieldSilently(rzuClass, newRzu, "c", boolean.class, true);
                setFieldSilently(rzuClass, newRzu, "d", boolean.class, true);
                setFieldSilently(rzuClass, newRzu, "e", boolean.class, true);
                setFieldSilently(rzuClass, newRzu, "f", boolean.class, true);
                setFieldSilently(rzuClass, newRzu, "g", long.class, Long.MAX_VALUE);
                setFieldSilently(rzuClass, newRzu, "h", boolean.class, true);
                Logger.printInfo(() -> "Morphe: Prevented reset of mobile data settings, preserved defaults");
                return;
            }

            if (oldRzu != null) {
                long oldCap = getLongFieldSilently(oldRzu, "g", -1L);
                long newCap = getLongFieldSilently(newRzu, "g", -1L);
                boolean oldPhotos = getBooleanFieldSilently(oldRzu, "e", false);
                boolean newPhotos = getBooleanFieldSilently(newRzu, "e", false);
                boolean oldVideos = getBooleanFieldSilently(oldRzu, "f", false);
                boolean newVideos = getBooleanFieldSilently(newRzu, "f", false);
                boolean oldRoaming = getBooleanFieldSilently(oldRzu, "h", false);
                boolean newRoaming = getBooleanFieldSilently(newRzu, "h", false);

                boolean mobileDataModified = (oldCap != newCap) ||
                        (oldPhotos != newPhotos) ||
                        (oldVideos != newVideos) ||
                        (oldRoaming != newRoaming);

                if (mobileDataModified) {
                    prefs.edit().putBoolean(KEY_USER_CUSTOMIZED_MOBILE_DATA, true).commit();
                    Logger.printInfo(() -> "Morphe: User modified mobile data settings, marked as customized");
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Morphe: Error in onSavePreferences", t);
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
            boolean userCustomized = prefs.getBoolean(KEY_USER_CUSTOMIZED_MOBILE_DATA, false);
            boolean foldersSeeded = prefs.getBoolean(KEY_FOLDERS_SEEDED, false);

            Class<?> rzuClass = rzuObj.getClass();

            if (!userCustomized) {
                setFieldSilently(rzuClass, rzuObj, "c", boolean.class, true); // has_unrestricted_data_options
                setFieldSilently(rzuClass, rzuObj, "d", boolean.class, true); // use_unrestricted_data
                setFieldSilently(rzuClass, rzuObj, "e", boolean.class, true); // use_data_for_photos
                setFieldSilently(rzuClass, rzuObj, "f", boolean.class, true); // use_data_for_videos
                setFieldSilently(rzuClass, rzuObj, "g", long.class, Long.MAX_VALUE); // daily_data_cap
                setFieldSilently(rzuClass, rzuObj, "h", boolean.class, true); // backup_when_roaming

                boolean needsSync = !prefs.getBoolean(KEY_USE_DATA_FOR_PHOTOS, false)
                        || !prefs.getBoolean(KEY_USE_DATA_FOR_VIDEOS, false)
                        || !prefs.getBoolean(KEY_BACKUP_WHEN_ROAMING, false)
                        || prefs.getLong(KEY_DAILY_DATA_CAP, 0L) != Long.MAX_VALUE;

                if (needsSync) {
                    prefs.edit()
                            .putLong(KEY_DAILY_DATA_CAP, Long.MAX_VALUE)
                            .putBoolean(KEY_USE_UNRESTRICTED_DATA, true)
                            .putBoolean(KEY_HAS_UNRESTRICTED_DATA_OPTIONS, true)
                            .putBoolean(KEY_BACKUP_WHEN_ROAMING, true)
                            .putBoolean(KEY_USE_DATA_FOR_PHOTOS, true)
                            .putBoolean(KEY_USE_DATA_FOR_VIDEOS, true)
                            .apply();
                }
            }

            if (!foldersSeeded) {
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

                prefs.edit()
                        .putStringSet(KEY_LOCAL_BACKUP_FOLDERS, mergedFolders)
                        .putBoolean(KEY_FOLDERS_SEEDED, true)
                        .apply();
            }

            // Also update rzvStore.e cached field
            try {
                Field eField = rzvStore.getClass().getDeclaredField("e");
                eField.setAccessible(true);
                eField.set(rzvStore, rzuObj);
            } catch (Throwable ignored) {}

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

    private static boolean getBooleanFieldSilently(Object target, String name, boolean def) {
        if (target == null) return def;
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(target);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static long getLongFieldSilently(Object target, String name, long def) {
        if (target == null) return def;
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getLong(target);
        } catch (Throwable ignored) {
            return def;
        }
    }
}
