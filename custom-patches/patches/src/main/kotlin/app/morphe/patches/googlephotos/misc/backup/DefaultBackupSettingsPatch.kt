package app.morphe.patches.googlephotos.misc.backup

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patches.googlephotos.misc.gms.HomeActivityOnCreateFingerprint

@Suppress("unused")
val defaultBackupSettingsPatch = bytecodePatch(
    name = "Default backup settings",
    description = "Sets default backup settings: unlimited mobile data, backup while roaming, " +
        "and enables mobile data for photos and videos on first launch.",
    default = true,
) {
    compatibleWith(AppCompatibilities.GOOGLE_PHOTOS)

    execute {
        HomeActivityOnCreateFingerprint.result!!.mutableMethod.addInstructions(
            0,
            """
                invoke-static {p0}, Lapp/morphe/extension/shared/patches/BackupSettingsSeeder;->seedDefaultBackupSettings(Landroid/content/Context;)V
            """
        )
    }
}
