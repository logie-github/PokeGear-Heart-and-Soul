package com.logie.pgearhs.ui

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.logie.pgearhs.R
import com.logie.pgearhs.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Thor is a dedicated handheld, not a phone - the app should own the
 * full display rather than leaving the system status/navigation bars
 * (which otherwise render as an opaque black strip) on screen.
 *
 * Also gives every activity a shared Gen3-style dialogue box, attached over whatever layout
 * setContentView() loads. Both an activity's own flows (TrainerCallActivity's rematch calls)
 * and background events (GlobalDialogueNotices - e.g. the Mom money transfer, which can fire
 * while any screen is open) go through this one overlay per screen, instead of activities
 * each maintaining a separate copy. Subclasses that add their own onKeyDown handling must
 * call super.onKeyDown() *first* so the dialogue box gets first refusal on DPAD/A input
 * while it's showing.
 */
abstract class BaseImmersiveActivity : AppCompatActivity() {

    private var _dialogueBox: PokemonDialogueBox? = null

    /** Only valid after setContentView() has run. */
    protected val dialogueBox: PokemonDialogueBox
        get() = _dialogueBox ?: error("dialogueBox accessed before setContentView()")

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        val contentRoot = findViewById<ViewGroup>(android.R.id.content)
        val overlayView = layoutInflater.inflate(R.layout.view_pokemon_dialogue_overlay, contentRoot, false)
        contentRoot.addView(overlayView)
        _dialogueBox = PokemonDialogueBox(overlayView)
    }

    protected val appUpdater: AppUpdater by lazy { AppUpdater(this) }
    private var pendingUpdateFile: File? = null

    /**
     * Shared by SettingsActivity's manual "Check for updates" button and MainActivity's
     * silent launch-time check, so both go through the exact same download/permission/install
     * flow instead of two copies that could drift. [announceResult] controls whether
     * "up to date" / a failure gets its own dialog - the manual button wants that, a launch
     * check should just stay quiet unless an update is actually found.
     */
    protected fun checkForUpdates(announceResult: Boolean, onChecked: (() -> Unit)? = null) {
        lifecycleScope.launch {
            val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            val result = runCatching { withContext(Dispatchers.IO) { appUpdater.check(currentVersion) } }
            onChecked?.invoke()

            result.onSuccess { release ->
                if (release == null) {
                    DebugLog.add("Update check: up to date.")
                    if (announceResult) {
                        AlertDialog.Builder(this@BaseImmersiveActivity)
                            .setTitle(R.string.up_to_date_title)
                            .setMessage(R.string.up_to_date_message)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                } else {
                    DebugLog.add("Update check: ${release.versionName} available.")
                    showUpdateAvailableDialog(release)
                }
            }.onFailure {
                DebugLog.add("! Update check failed: ${it.message}")
                if (announceResult) {
                    AlertDialog.Builder(this@BaseImmersiveActivity)
                        .setTitle(R.string.update_check_failed_title)
                        .setMessage(it.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun showUpdateAvailableDialog(release: AppRelease) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, release.versionName))
            .setNegativeButton(R.string.update_action_later, null)
            .setPositiveButton(R.string.update_action_update) { _, _ -> downloadAndInstall(release) }
            .show()
    }

    private fun downloadAndInstall(release: AppRelease) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.check_for_updates)
            .setMessage(getString(R.string.downloading_update, release.versionName))
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { appUpdater.download(release) } }
            progressDialog.dismiss()

            result.onSuccess { file ->
                DebugLog.add("Downloaded ${release.versionName} to ${file.name}.")
                if (appUpdater.canInstallPackages()) {
                    appUpdater.install(file)
                } else {
                    pendingUpdateFile = file
                    appUpdater.requestInstallPermission()
                }
            }.onFailure {
                DebugLog.add("! Update download failed: ${it.message}")
                AlertDialog.Builder(this@BaseImmersiveActivity)
                    .setTitle(R.string.update_download_failed_title)
                    .setMessage(it.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun installPendingUpdate() {
        val file = pendingUpdateFile ?: return
        if (appUpdater.canInstallPackages()) {
            pendingUpdateFile = null
            appUpdater.install(file)
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        installPendingUpdate()
        GlobalDialogueNotices.register { lines ->
            if (dialogueBox.isVisible) {
                false
            } else {
                dialogueBox.showText(lines) { dialogueBox.hide() }
                true
            }
        }
    }

    override fun onPause() {
        GlobalDialogueNotices.unregister()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (dialogueBox.isVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER -> {
                    dialogueBox.onAdvance()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    dialogueBox.onNavigate(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
