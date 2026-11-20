package com.eng.henrysvocab

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import kotlin.math.roundToInt

private const val PREFERENCES_NAME = "translation_shortcut"
private const val SELECTED_TRANSLATOR = "selected_translator"

internal enum class ExternalTranslator(val label: String, val packageName: String) {
    BAIDU("百度翻译", "com.baidu.baidutranslate"),
    YOUDAO("有道词典", "com.youdao.dict");

    companion object {
        val trusted: List<ExternalTranslator> = listOf(BAIDU, YOUDAO)
    }
}

internal class ExternalTranslationPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val packageManager = context.packageManager

    var selected: ExternalTranslator
        get() {
            val savedPackage = preferences.getString(SELECTED_TRANSLATOR, null)
            return ExternalTranslator.trusted.firstOrNull { it.packageName == savedPackage }
                ?: ExternalTranslator.BAIDU
        }
        set(value) {
            preferences.edit().putString(SELECTED_TRANSLATOR, value.packageName).apply()
        }

    @Suppress("DEPRECATION") // The flags-int overload is available on the app's minSdk.
    fun isInstalled(translator: ExternalTranslator): Boolean = try {
        packageManager.getPackageInfo(translator.packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/** Activity-owned, main-thread shortcut. Call [clear] in onResume and onDestroy. */
internal class TranslationShortcut(private val activity: MainActivity) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager get() = activity.getSystemService(WindowManager::class.java)
    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val translationPreferences = ExternalTranslationPreferences(activity)
    private var permissionDialog: AlertDialog? = null
    private var returnButton: Button? = null
    private val expireOverlay = Runnable { removeReturnButton() }

    fun lookup(word: String) {
        if (word.isBlank() || activity.isFinishing || activity.isDestroyed || permissionDialog != null) return
        removeReturnButton()
        activity.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("单词", word))

        val translator = translationPreferences.selected
        if (!translationPreferences.isInstalled(translator)) {
            toast("未安装${translator.label}，单词已复制。")
            return
        }

        val firstLookup = !preferences.getBoolean(OVERLAY_OFFER_SEEN, false)
        if (firstLookup) preferences.edit().putBoolean(OVERLAY_OFFER_SEEN, true).apply()
        if (firstLookup && !Settings.canDrawOverlays(activity)) {
            offerOverlayPermission(word, translator)
        } else {
            launchTranslator(word, translator)
        }
    }

    fun clear() {
        permissionDialog?.dismiss()
        permissionDialog = null
        removeReturnButton()
    }

    /**
     * Special app access, not a runtime permission. The user can also enable it manually in
     * Settings > Apps > Special app access > Display over other apps. We recheck on lookup.
     * Returning from Settings only cleans up; there is no pending word or automatic launch.
     */
    private fun offerOverlayPermission(word: String, translator: ExternalTranslator) {
        val appName = activity.getString(R.string.app_name)
        permissionDialog = AlertDialog.Builder(activity)
            .setTitle("启用悬浮返回按钮？")
            .setMessage("在${translator.label}中短暂显示返回按钮。此权限可选，不影响查词。若要启用，请在系统设置中选择 $appName 并允许显示在其他应用上层，返回后再点一次单词。")
            .setPositiveButton("前往设置") { _, _ ->
                val settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    // Android 11+ opens the app list rather than the per-app page.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        data = Uri.fromParts("package", activity.packageName, null)
                    }
                }
                if (!tryStart(settingsIntent)) {
                    toast("无法打开悬浮窗设置，请在系统设置中手动启用。")
                    launchTranslator(word, translator)
                }
            }
            .setNegativeButton("不用，继续查词") { _, _ -> launchTranslator(word, translator) }
            .setOnDismissListener { if (permissionDialog === it) permissionDialog = null }
            .show()
    }

    private fun launchTranslator(word: String, translator: ExternalTranslator) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (translator == ExternalTranslator.YOUDAO && tryStart(youdaoLookupIntent(word))) {
            showReturnButton()
            return
        }
        if (tryStart(wordShareIntent(word, translator))) {
            showReturnButton()
            return
        }

        val launcher = activity.packageManager.getLaunchIntentForPackage(translator.packageName)
        if (launcher?.component?.packageName == translator.packageName && tryStart(launcher)) {
            toast("单词已复制，可在${translator.label}中粘贴。")
            showReturnButton()
        } else {
            toast("无法打开${translator.label}，单词已复制。")
        }
    }

    private fun youdaoLookupIntent(word: String): Intent {
        val uri = Uri.Builder()
            .scheme("yddict")
            .authority("m.youdao.com")
            .appendPath("dict")
            .appendQueryParameter("le", "eng")
            .appendQueryParameter("q", word)
            .build()
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(ExternalTranslator.YOUDAO.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun wordShareIntent(word: String, translator: ExternalTranslator) = Intent(Intent.ACTION_SEND).apply {
        setPackage(translator.packageName)
        type = "text/plain"
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Build a fresh share instead of forwarding anything from a browser.
        putExtra(Intent.EXTRA_TEXT, word)
    }

    private fun tryStart(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        Log.w(TAG, "Android denied an activity launch.")
        false
    }

    private fun showReturnButton() {
        if (!Settings.canDrawOverlays(activity)) return
        val manager = windowManager ?: return
        val button = Button(activity).apply {
            text = "返回"
            contentDescription = "返回${activity.getString(R.string.app_name)}"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            minimumWidth = 0
            minimumHeight = 0
            filterTouchesWhenObscured = true
            background = RippleDrawable(
                ColorStateList.valueOf(0x55FFFFFF),
                GradientDrawable().apply {
                    setColor(0xFF031827.toInt())
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), 0xFFD6E8C1.toInt())
                },
                null,
            )
            setOnClickListener {
                if (returnButton === this && !activity.isFinishing && !activity.isDestroyed) {
                    val returnIntent = Intent(activity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    // Request the return while the user-tapped overlay is still visible.
                    if (!tryStart(returnIntent)) toast("请使用系统导航返回。")
                }
                removeReturnButton()
            }
        }
        val params = WindowManager.LayoutParams(
            dp(60),
            dp(48),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            x = dp(8)
        }
        try {
            manager.addView(button, params)
            returnButton = button
            mainHandler.postDelayed(expireOverlay, OVERLAY_LIFETIME_MS)
        } catch (_: WindowManager.BadTokenException) {
            // The app switch still works even if Android refuses the optional window.
        } catch (_: WindowManager.InvalidDisplayException) {
            // The Activity display went away during the switch.
        } catch (_: SecurityException) {
            Log.w(TAG, "Android denied the optional return overlay.")
        }
    }

    private fun removeReturnButton() {
        mainHandler.removeCallbacks(expireOverlay)
        val button = returnButton ?: return
        returnButton = null
        button.setOnClickListener(null)
        try {
            windowManager?.removeViewImmediate(button)
        } catch (_: IllegalArgumentException) {
            // The window was already detached by Android.
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "TranslationShortcut"
        const val OVERLAY_OFFER_SEEN = "overlay_offer_seen"
        const val OVERLAY_LIFETIME_MS = 60_000L
    }
}
