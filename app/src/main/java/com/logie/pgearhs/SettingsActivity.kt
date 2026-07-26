package com.logie.pgearhs

import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.logie.pgearhs.retroarch.LiveDexState
import com.logie.pgearhs.retroarch.PokedexMemoryCalibrator
import com.logie.pgearhs.retroarch.RetroArchConnection
import com.logie.pgearhs.retroarch.RetroArchMemoryBridge
import com.logie.pgearhs.ui.AppRelease
import com.logie.pgearhs.ui.AppUpdater
import com.logie.pgearhs.ui.BaseImmersiveActivity
import com.logie.pgearhs.ui.MenuBackgroundPrefs
import com.logie.pgearhs.ui.ScrollingTiledBackgroundView.MovementPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : BaseImmersiveActivity() {

    private lateinit var appUpdater: AppUpdater
    private var pendingUpdateFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        appUpdater = AppUpdater(this)

        val preferTopSwitch = findViewById<SwitchMaterial>(R.id.preferTopDisplaySwitch)
        preferTopSwitch.isChecked = DisplayRouter.isTopDisplayPreferred(this)
        preferTopSwitch.setOnCheckedChangeListener { _, isChecked ->
            DisplayRouter.setTopDisplayPreferred(this, isChecked)
        }

        val movementGroup = findViewById<RadioGroup>(R.id.menuBackgroundMovementGroup)
        val idForPattern = mapOf(
            MovementPattern.LEFT to R.id.movementOptionLeft,
            MovementPattern.FIGURE_EIGHT to R.id.movementOptionFigureEight,
            MovementPattern.NONE to R.id.movementOptionNone
        )
        val patternForId = idForPattern.entries.associate { (pattern, id) -> id to pattern }

        movementGroup.check(idForPattern.getValue(MenuBackgroundPrefs.getMovementPattern(this)))
        movementGroup.setOnCheckedChangeListener { _, checkedId ->
            val pattern = patternForId[checkedId] ?: return@setOnCheckedChangeListener
            MenuBackgroundPrefs.setMovementPattern(this, pattern)
        }

        findViewById<android.widget.Button>(R.id.checkForUpdatesButton).setOnClickListener {
            checkForUpdates()
        }

        setUpRetroArchSync()
    }

    private fun setUpRetroArchSync() {
        val hostInput = findViewById<EditText>(R.id.retroArchHostInput)
        val portInput = findViewById<EditText>(R.id.retroArchPortInput)
        hostInput.setText(RetroArchConnection.getHost(this))
        portInput.setText(RetroArchConnection.getPort(this).toString())

        findViewById<android.widget.Button>(R.id.calibrateSyncButton).setOnClickListener {
            val host = hostInput.text.toString().ifBlank { "127.0.0.1" }
            val port = portInput.text.toString().toIntOrNull() ?: RetroArchConnection.DEFAULT_PORT
            RetroArchConnection.setHost(this, host)
            RetroArchConnection.setPort(this, port)
            runCalibration(host, port)
        }
    }

    private fun runCalibration(host: String, port: Int) {
        val statusView = findViewById<TextView>(R.id.retroArchSyncStatus)
        statusView.text = getString(R.string.retroarch_sync_status_working)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val bridge = RetroArchMemoryBridge(host, port)
                PokedexMemoryCalibrator(bridge).calibrateAndRead()
            }

            when (result) {
                is PokedexMemoryCalibrator.Result.Success -> {
                    LiveDexState.applySyncResult(result.nationalDexEnabled, result.owned, result.seen)
                    statusView.text = getString(
                        R.string.retroarch_sync_status_success,
                        if (result.nationalDexEnabled) getString(R.string.pokedex_national) else getString(R.string.pokedex_regional),
                        LiveDexState.registeredCount
                    )
                }
                is PokedexMemoryCalibrator.Result.Failure -> {
                    statusView.text = result.reason
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        installPendingUpdate()
    }

    private fun checkForUpdates() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.check_for_updates)
            .setMessage(R.string.checking_for_updates)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

            val result = runCatching {
                withContext(Dispatchers.IO) { appUpdater.check(currentVersion) }
            }

            progressDialog.dismiss()

            result.onSuccess { release ->
                if (release == null) {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(R.string.up_to_date_title)
                        .setMessage(R.string.up_to_date_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    showUpdateAvailableDialog(release)
                }
            }.onFailure {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.update_check_failed_title)
                    .setMessage(it.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
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
            val result = runCatching {
                withContext(Dispatchers.IO) { appUpdater.download(release) }
            }

            progressDialog.dismiss()

            result.onSuccess { file ->
                if (appUpdater.canInstallPackages()) {
                    appUpdater.install(file)
                } else {
                    pendingUpdateFile = file
                    appUpdater.requestInstallPermission()
                }
            }.onFailure {
                AlertDialog.Builder(this@SettingsActivity)
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
}
