// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0

package dev.openview.runtime

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OpenViewApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        extractSlamAssets()
        warnIfOverlayPermissionMissing()
    }

    /*
     * The SLAM tracker reads its calibration and VIO configuration from
     * plain files, so the copies bundled in assets are extracted to the
     * files dir and a SLAM config pointing at them is written next to
     * them. The native factory looks for that config at a fixed path
     * under the package's files dir. Runs on every start: the files are
     * tiny and this keeps them in step with the APK after an update.
     */
    private fun extractSlamAssets() {
        try {
            val slamDir = java.io.File(filesDir, "slam")
            slamDir.mkdirs()
            for (name in listOf("calib.json", "vio_config.json")) {
                assets.open("slam/$name").use { input ->
                    java.io.File(slamDir, name).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val calib = java.io.File(slamDir, "calib.json").absolutePath
            val vioConfig = java.io.File(slamDir, "vio_config.json").absolutePath
            java.io.File(slamDir, "slam_config.txt")
                .writeText("cam-calib=\"$calib\"\nconfig-path=\"$vioConfig\"\n")
        } catch (e: Exception) {
            android.util.Log.e("OpenView", "SLAM asset extraction failed", e)
        }
    }

    /*
     * Without "display over other apps" the runtime has no window of its own
     * and draws into a surface owned by the XR app. Vendor power managers then
     * see a windowless background process and freeze it: on Transsion devices
     * five seconds after the session starts, foreground service or not (see
     * docs/findings/dispositivo-infinix-x6885.md). The process is usually
     * started by an XR app binding to the service, with no activity of ours on
     * screen, so a notification is the only way to tell the user.
     */
    private fun warnIfOverlayPermissionMissing() {
        val notifications = getSystemService(NotificationManager::class.java)
        if (Settings.canDrawOverlays(this)) {
            notifications.cancel(SETUP_NOTIFICATION_ID)
            return
        }

        notifications.createNotificationChannel(
            NotificationChannel(
                SETUP_CHANNEL_ID,
                getString(R.string.setup_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
        )

        val openSettings =
            PendingIntent.getActivity(
                this,
                0,
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", packageName, null),
                ),
                PendingIntent.FLAG_IMMUTABLE,
            )

        notifications.notify(
            SETUP_NOTIFICATION_ID,
            Notification.Builder(this, SETUP_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_openview_notif)
                .setContentTitle(getString(R.string.setup_overlay_title))
                .setContentText(getString(R.string.setup_overlay_text))
                .setStyle(
                    Notification.BigTextStyle().bigText(getString(R.string.setup_overlay_text))
                )
                .setContentIntent(openSettings)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val SETUP_CHANNEL_ID = "dev.openview.runtime.SETUP"
        const val SETUP_NOTIFICATION_ID = 1
    }
}
