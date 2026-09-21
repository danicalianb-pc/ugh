package com.bella.ugh

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import android.widget.Toast

class WebAppBridge(private val host: MainActivity) {

    private fun trusted(): Boolean = host.isTrustedPage()

    @JavascriptInterface
    fun platform(): String = "android"

    @JavascriptInterface
    fun versionName(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun sdkInt(): Int = Build.VERSION.SDK_INT

    @JavascriptInterface
    fun toast(message: String) {
        host.runOnUiThread {
            Toast.makeText(host, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun vibrate(milliseconds: Int) {
        val ms = milliseconds.coerceIn(1, 2000)
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (host.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            host.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                it.vibrate(ms.toLong())
            }
        }
    }

    @JavascriptInterface
    fun share(text: String) {
        if (!trusted()) return
        host.runOnUiThread {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            host.startActivity(Intent.createChooser(intent, host.getString(R.string.share_via)))
        }
    }

    @JavascriptInterface
    fun openExternal(url: String) {
        if (!trusted() || !Config.isInternal(url)) return
        host.runOnUiThread { host.openInBrowser(Uri.parse(url)) }
    }

    @JavascriptInterface
    fun reload() {
        if (!trusted()) return
        host.runOnUiThread { host.reloadPage() }
    }
}
