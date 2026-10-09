package app.morphe.patches.googlephotos.misc.backup

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.googlephotos.misc.extension.sharedExtensionPatch
import app.morphe.patches.googlephotos.misc.updater.HomeActivityOnCreateFingerprint
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
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

internal object BackupPreferencesBuilderConstructorFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    custom = { method, classDef ->
        method.name == "<init>" &&
            classDef.methods.any {
                it.referencesString("Storage policy wasn't set, Backup is off") ||
                it.referencesString("Toggle source wasn't set!")
            }
    }
)

internal object BackupPreferencesStoreGetFingerprint : Fingerprint(
    parameters = listOf(),
    custom = { method, classDef ->
        if (!classDef.methods.any { it.name == "<clinit>" && it.referencesString("BackupPreferencesStore") }) {
            return@Fingerprint false
        }
        val prefsReturnType = classDef.methods.firstOrNull {
            it.parameterTypes == listOf("Landroid/content/SharedPreferences;") && it.returnType.startsWith("L")
        }?.returnType ?: return@Fingerprint false

        method.parameters.isEmpty() && method.returnType == prefsReturnType
    }
)

internal object BackupPreferencesStoreSaveFingerprint : Fingerprint(
    returnType = "Z",
    custom = { method, classDef ->
        classDef.methods.any { it.name == "<clinit>" && it.referencesString("BackupPreferencesStore") } &&
            method.parameters.size == 4 &&
            method.returnType == "Z" &&
            (method.referencesString("Cannot enable backup for a managed account") ||
             method.referencesString("Account not found."))
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

        // 2. Also seed in HomeActivity.onCreate
        HomeActivityOnCreateFingerprint.methodOrNull?.let { method ->
            method.addInstructions(
                0,
                "invoke-static { p0 }, Lapp/morphe/extension/shared/patches/BackupSettingsHooks;->seedDefaults(Landroid/content/Context;)V"
            )
        }

        // 3. Initialize default values in BackupPreferences.Builder.<init>()
        BackupPreferencesBuilderConstructorFingerprint.methodOrNull?.let { method ->
            val returnIndex = method.implementation?.instructions?.indexOfFirst {
                it.opcode == Opcode.RETURN_VOID
            } ?: -1
            if (returnIndex >= 0) {
                method.addInstructions(
                    returnIndex,
                    "invoke-static { p0 }, Lapp/morphe/extension/shared/patches/BackupSettingsHooks;->initBackupPreferencesBuilder(Ljava/lang/Object;)V"
                )
            }
        }

        // 4. Wrap returned BackupPreferences in BackupPreferencesStore.c() before returning
        BackupPreferencesStoreGetFingerprint.methodOrNull?.let { method ->
            val instructions = method.implementation?.instructions ?: return@let
            val returnIndices = instructions.mapIndexedNotNull { index, instruction ->
                if (instruction.opcode == Opcode.RETURN_OBJECT) index else null
            }.reversed()

            for (index in returnIndices) {
                method.addInstructions(
                    index,
                    "invoke-static { v0, p0 }, Lapp/morphe/extension/shared/patches/BackupSettingsHooks;->wrapBackupPreferences(Ljava/lang/Object;Ljava/lang/Object;)V"
                )
            }
        }

        // 5. Intercept BackupPreferencesStore.o() to preserve defaults on reset and track user customizations
        BackupPreferencesStoreSaveFingerprint.methodOrNull?.let { method ->
            method.addInstructions(
                0,
                "invoke-static { p1, p2, p3, p0 }, Lapp/morphe/extension/shared/patches/BackupSettingsHooks;->onSavePreferences(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
            )
        }
    }
}
