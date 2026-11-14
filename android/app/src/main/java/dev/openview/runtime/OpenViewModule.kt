// Copyright 2026, OpenView contributors.
// SPDX-License-Identifier: BSL-1.0

package dev.openview.runtime

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.mikepenz.aboutlibraries.LibsBuilder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import org.freedesktop.monado.android_common.AboutActivity
import org.freedesktop.monado.android_common.AboutMenuProvider
import org.freedesktop.monado.android_common.NoticeFragmentProvider
import org.freedesktop.monado.android_common.ServiceNotificationImpl
import org.freedesktop.monado.auxiliary.IServiceNotification
import org.freedesktop.monado.auxiliary.NameAndLogoProvider
import org.freedesktop.monado.auxiliary.ScannerProvider
import org.freedesktop.monado.auxiliary.UiProvider
import org.freedesktop.monado.auxiliary.cardboard.QrScannerActivity

/*
 * Monado's Android libraries are product-neutral: everything that identifies
 * the runtime to the user is requested through these interfaces. This file is
 * the whole of OpenView's Android-side identity.
 */

class OpenViewBranding @Inject constructor(@ApplicationContext val context: Context) :
    NameAndLogoProvider {
    override fun getLocalizedRuntimeName(): CharSequence =
        context.packageManager.getApplicationLabel(context.applicationInfo)

    override fun getLogoDrawable(): Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_openview_logo)
}

class OpenViewUi @Inject constructor(@ApplicationContext val context: Context) : UiProvider {
    override fun getNotificationIcon(): Icon? =
        Icon.createWithResource(context, R.drawable.ic_openview_notif)

    override fun makeAboutActivityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent.makeMainActivity(
                ComponentName.createRelative(context, HomeActivity::class.qualifiedName!!)
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
}

class OpenViewNotices @Inject constructor() : NoticeFragmentProvider {
    override fun makeNoticeFragment(): Fragment =
        LibsBuilder()
            .withAboutIconShown(false)
            .withAboutVersionShown(true)
            .withLicenseShown(true)
            .supportFragment()
}

class CardboardScanner @Inject constructor(@ApplicationContext val context: Context) :
    ScannerProvider {
    override fun getIcon(): Drawable =
        ContextCompat.getDrawable(
            context,
            org.freedesktop.monado.auxiliary.cardboard.R.drawable.cardboard_oss_qr,
        )!!

    override fun makeScannerIntent(): Intent = Intent(context, QrScannerActivity::class.java)
}

/** Adds the viewer calibration (Cardboard QR) entry to the About screen. */
class AboutMenu @Inject constructor(private val scanner: ScannerProvider) : AboutMenuProvider {
    override fun onCreateOptionsMenu(menuInflater: MenuInflater, menu: Menu?) {
        menuInflater.inflate(R.menu.menu_runtime, menu)
        menu?.findItem(R.id.qrscan)?.icon = scanner.getIcon()
    }

    override fun onOptionsItemSelected(context: Context, item: MenuItem): Boolean {
        if (item.itemId != R.id.qrscan) {
            return false
        }
        context.startActivity(scanner.makeScannerIntent())
        return true
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OpenViewModule {
    @Binds abstract fun bindUi(impl: OpenViewUi): UiProvider

    @Binds abstract fun bindBranding(impl: OpenViewBranding): NameAndLogoProvider

    @Binds abstract fun bindNotices(impl: OpenViewNotices): NoticeFragmentProvider

    @Binds abstract fun bindNotification(impl: ServiceNotificationImpl): IServiceNotification
}

@Module
@InstallIn(ActivityComponent::class)
abstract class OpenViewActivityModule {
    @Binds abstract fun bindAboutMenu(impl: AboutMenu): AboutMenuProvider

    @Binds abstract fun bindScanner(impl: CardboardScanner): ScannerProvider
}
