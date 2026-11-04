package app.plexmods.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_PLEX = Compatibility(
        name = "Plex",
        packageName = "com.plexapp.android",
        apkFileType = ApkFileType.APKM,
        // Plex icon: dark charcoal background.
        appIconColor = 0x282A2D,
        // No version pin: the Premium unlock patch finds the Plex Pass checks by
        // signature, so it is not tied to a particular build. Compatibility defaults
        // to any version, and the patch fails with its own message if the bytecode
        // ever changes shape.
    )
}
