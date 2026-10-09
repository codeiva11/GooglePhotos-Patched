package app.morphe.patches.googlephotos.misc.backup

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.googlephotos.misc.extension.sharedExtensionPatch
import app.morphe.patches.googlephotos.misc.updater.HomeActivityOnCreateFingerprint
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private fun com.android.tools.smali.dexlib2.iface.Method.referencesString(value: String) =
    implementation?.instructions?.any {
        it.getReference<StringReference>()?.string == value
    } == true

internal object BackupPreferencesStoreConstructorFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    custom = { method, classDef ->
        classDef.methods.any { it.name == "<clinit>" && it.referencesString("BackupPreferencesStore") } &&
            method.name == "<init>"
    }
)

@Suppress("unused")
val defaultBackupSettingsPatch = bytecodePatch(
    name = "Default backup settings",
    description = "Sets default backup settings: unlimited mobile data, backup while roaming, " +
        "enables mobile data for photos and videos, and automatically enables Camera folder backup on launch.",
    default = true,
) {
    compatibleWith(AppCompatibilities.GOOGLE_PHOTOS)
    dependsOn(sharedExtensionPatch)

    execute {
        // 1. Seed defaults inside BackupPreferencesStore constructor at index 1 (safely right after super.<init>())
        BackupPreferencesStoreConstructorFingerprint.methodOrNull?.let { method ->
            method.addInstructions(
                1,
                "invoke-static { p1 }, Lapp/morphe/extension/shared/patches/BackupSettingsHooks;->seedDefaults(Landroid/content/Context;)V"
            )
        }

        // 2. Fallback: also seed in HomeActivity.onCreate
        HomeActivityOnCreateFingerprint.methodOrNull?.let { method ->
            method.addInstructions(
                0,
                "invoke-static { p0 }, Lapp/morphe/extension/shared/patches/BackupSettingsHooks;->seedDefaults(Landroid/content/Context;)V"
            )
        }
    }
}
