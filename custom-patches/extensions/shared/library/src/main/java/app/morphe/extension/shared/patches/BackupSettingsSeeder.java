package app.morphe.extension.shared.patches;

import android.content.Context;
import android.content.SharedPreferences;
import app.morphe.extension.shared.Logger;

/**
 * Seeds default backup settings on first launch.
 * 
 * Sets:
 * - Unlimited mobile data for backup (daily_data_cap = Long.MAX_VALUE)
 * - Backup while roaming enabled
 * - Mobile data enabled for photos and videos
 */
public final class BackupSettingsSeeder {
    private BackupSettingsSeeder() {}

    private static final String BACKUP_PREFS_FILE = "photos.backup.backup_prefs";
    private static final String KEY_SEEDED = "morphe_default_backup_seeded_v3";
    
    // Preference keys discovered from APK analysis
    private static final String KEY_DAILY_DATA_CAP = "backup_prefs_daily_data_cap";
    private static final String KEY_USE_UNRESTRICTED_DATA = "use_unrestricted_data";
    private static final String KEY_HAS_UNRESTRICTED_DATA_OPTIONS = "has_unrestricted_data_options";
    private static final String KEY_BACKUP_WHEN_ROAMING = "backup_prefs_backup_when_roaming";
    private static final String KEY_USE_DATA_FOR_PHOTOS = "backup_prefs_use_data_for_photos";
    private static final String KEY_USE_DATA_FOR_VIDEOS = "backup_prefs_use_data_for_videos";

    public static void seedDefaultBackupSettings(Context context) {
        if (context == null) return;
        
        try {
            SharedPreferences prefs = context.getSharedPreferences(BACKUP_PREFS_FILE, Context.MODE_PRIVATE);
            
            // Only seed once per version
            if (prefs.getBoolean(KEY_SEEDED, false)) {
                Logger.printInfo(() -> "Default backup settings already seeded");
                return;
            }

            SharedPreferences.Editor editor = prefs.edit();
            
            // Set unlimited mobile data (Long.MAX_VALUE = Unlimited)
            editor.putLong(KEY_DAILY_DATA_CAP, Long.MAX_VALUE);
            editor.putBoolean(KEY_USE_UNRESTRICTED_DATA, true);
            editor.putBoolean(KEY_HAS_UNRESTRICTED_DATA_OPTIONS, true);
            
            // Enable backup while roaming
            editor.putBoolean(KEY_BACKUP_WHEN_ROAMING, true);
            
            // Enable mobile data for photos and videos
            editor.putBoolean(KEY_USE_DATA_FOR_PHOTOS, true);
            editor.putBoolean(KEY_USE_DATA_FOR_VIDEOS, true);
            
            // Mark as seeded
            editor.putBoolean(KEY_SEEDED, true);
            
            editor.apply();
            
            Logger.printInfo(() -> "Seeded default backup settings: Unlimited mobile data, roaming enabled");
        } catch (Exception e) {
            Logger.printException(() -> "Failed to seed default backup settings", e);
        }
    }
}
