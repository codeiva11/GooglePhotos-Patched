package app.morphe.patches.googlephotos.misc.backup

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patches.googlephotos.misc.extension.homeActivityInitHook
import app.morphe.patcher.fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val homeActivityOnCreateFingerprint = fingerprint {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.PROTECTED)
    returns("V")
    parameters("Landroid/os/Bundle;")
    opcodes(
        Opcode.INVOKE_SUPER,
        Opcode.RETURN_VOID
    )
    custom { method, _ ->
        method.definingClass.endsWith("/HomeActivity;")
    }
}

@Suppress("unused")
val defaultBackupSettingsPatch = bytecodePatch(
    name = "Default backup settings",
    description = "Sets default backup settings: unlimited mobile data, backup while roaming, " +
        "and enables mobile data for photos and videos on first launch.",
    default = true,
) {
    compatibleWith(AppCompatibilities.GOOGLE_PHOTOS)

    execute {
        homeActivityInitHook.mutableMethod.addInstructions(
            0,
            """
                invoke-static {p0}, Lapp/morphe/extension/shared/patches/BackupSettingsSeeder;->seedDefaultBackupSettings(Landroid/content/Context;)V
            """
        )
    }
}
