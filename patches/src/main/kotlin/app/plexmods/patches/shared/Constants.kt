package app.plexmods.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_PLEX = Compatibility(
        name = "Plex",
        packageName = "com.plexapp.android",
        apkFileType = ApkFileType.APKM,
        // Plex icon: dark charcoal background.
        appIconColor = 0x282A2D,
        targets = listOf(
            AppTarget(version = "2026.20.0"),
            AppTarget(version = "2026.19.1")
        )
    )
}
