package app.morphe.extension.shared.patches;

import android.content.Context;

/**
 * Seeds default backup settings on launch. Delegates to BackupSettingsHooks.
 */
public final class BackupSettingsSeeder {
    private BackupSettingsSeeder() {}

    public static void seedDefaultBackupSettings(Context context) {
        BackupSettingsHooks.seedDefaults(context);
    }
}
