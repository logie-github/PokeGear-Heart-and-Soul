package com.logie.pgearhs

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.logie.pgearhs.debug.DebugLog
import com.logie.pgearhs.debug.DebugReportFormatter
import com.logie.pgearhs.retroarch.LiveDexState
import com.logie.pgearhs.retroarch.PokedexMemoryCalibrator
import com.logie.pgearhs.retroarch.RetroArchConnection
import com.logie.pgearhs.retroarch.RetroArchOsdPrefs
import com.logie.pgearhs.sync.BattleMoneyTracker
import com.logie.pgearhs.sync.MomGiftManager
import com.logie.pgearhs.ui.BaseImmersiveActivity
import com.logie.pgearhs.trainers.TrainerCallPrefs
import com.logie.pgearhs.ui.MenuBackgroundPrefs
import com.logie.pgearhs.ui.ScrollingTiledBackgroundView.MovementPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : BaseImmersiveActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val preferTopSwitch = findViewById<SwitchMaterial>(R.id.preferTopDisplaySwitch)
        preferTopSwitch.isChecked = DisplayRouter.isTopDisplayPreferred(this)
        preferTopSwitch.setOnCheckedChangeListener { _, isChecked ->
            DisplayRouter.setTopDisplayPreferred(this, isChecked)
        }

        val inGameTextSwitch = findViewById<SwitchMaterial>(R.id.inGameTextSwitch)
        inGameTextSwitch.isChecked = TrainerCallPrefs.isInGameTextEnabled(this)
        inGameTextSwitch.setOnCheckedChangeListener { _, isChecked ->
            TrainerCallPrefs.setInGameTextEnabled(this, isChecked)
        }

        val retroArchOsdSwitch = findViewById<SwitchMaterial>(R.id.retroArchOsdSwitch)
        retroArchOsdSwitch.isChecked = RetroArchOsdPrefs.isBattleOsdEnabled(this)
        retroArchOsdSwitch.setOnCheckedChangeListener { _, isChecked ->
            RetroArchOsdPrefs.setBattleOsdEnabled(this, isChecked)
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
            checkForUpdatesManually()
        }

        setUpRetroArchSync()
        updateSyncStatusLabel()
        updateBattleWinnings()

        findViewById<android.widget.Button>(R.id.debugLogButton).setOnClickListener {
            showDebugLog()
        }
    }

    private fun showDebugLog() {
        val entries = DebugLog.snapshot()
        val text = if (entries.isEmpty()) getString(R.string.debug_log_empty) else entries.joinToString("\n")

        val textView = TextView(this).apply {
            setText(text)
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(this).apply { addView(textView) }

        AlertDialog.Builder(this)
            .setTitle(R.string.debug_log_title)
            .setView(scrollView)
            .setNeutralButton(R.string.debug_log_send) { _, _ -> sendDebugLogToGitHub(entries) }
            .setPositiveButton(R.string.debug_log_close, null)
            .show()
    }

    private fun sendDebugLogToGitHub(entries: List<String>) {
        val syncStatus = when {
            !LiveDexState.isSynced -> "not synced"
            LiveDexState.nationalDexEnabled -> "National Dex, ${LiveDexState.registeredCount} registered"
            else -> "Regional Dex, ${LiveDexState.registeredCount} registered"
        }

        val report = DebugReportFormatter.create(
            timestampMillis = System.currentTimeMillis(),
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
            gitCommit = BuildConfig.GIT_COMMIT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            retroArchSyncStatus = syncStatus,
            logEntries = entries
        )

        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(report.issueUrl)))
            android.widget.Toast.makeText(
                this,
                getString(R.string.debug_log_report_ready, report.id),
                android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            DebugLog.add("! Could not open GitHub report ${report.id}: ${e.message}")
            android.widget.Toast.makeText(
                this,
                getString(R.string.debug_log_open_failed, report.id, e.message),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
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
        DebugLog.add("Calibrating RetroArch sync against $host:$port…")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                PokedexMemoryCalibrator(
                    host, port,
                    onDiagnostic = DebugLog::add
                ).calibrateAndRead()
            }

            when (result) {
                is PokedexMemoryCalibrator.Result.Success -> {
                    LiveDexState.applySyncResult(result.nationalDexEnabled, result.owned, result.seen)
                    statusView.text = getString(
                        R.string.retroarch_sync_status_success,
                        if (result.nationalDexEnabled) getString(R.string.pokedex_national) else getString(R.string.pokedex_regional),
                        LiveDexState.registeredCount
                    )
                    DebugLog.add(
                        "Calibration succeeded: ${if (result.nationalDexEnabled) "National" else "Regional"} Dex, " +
                            "${LiveDexState.registeredCount} registered."
                    )
                }
                is PokedexMemoryCalibrator.Result.Failure -> {
                    statusView.text = result.reason
                    DebugLog.add("! Calibration failed: ${result.reason}")
                }
            }
        }
    }

    /**
     * AppSyncManager syncs automatically in the background from app launch, but this screen's
     * status label was only ever updated by the manual "Calibrate and sync now" button - so
     * even when the background sync had already succeeded, opening Settings still showed the
     * stale "Not synced yet" default text, making it look broken. Reflect LiveDexState's real
     * state here instead of only reacting to the button.
     */
    private fun updateSyncStatusLabel() {
        if (!LiveDexState.isSynced) return
        findViewById<TextView>(R.id.retroArchSyncStatus).text = getString(
            R.string.retroarch_sync_status_success,
            if (LiveDexState.nationalDexEnabled) getString(R.string.pokedex_national) else getString(R.string.pokedex_regional),
            LiveDexState.registeredCount
        )
    }

    private fun updateBattleWinnings() {
        findViewById<TextView>(R.id.battleWinningsAmount).text =
            getString(R.string.battle_winnings_amount, BattleMoneyTracker.totalWinnings(this))
        findViewById<TextView>(R.id.savingsAmount).text =
            getString(R.string.savings_amount, BattleMoneyTracker.savings(this))
        findViewById<TextView>(R.id.momGiftsAmount).text = getString(
            R.string.mom_gifts_amount,
            MomGiftManager.purchasedOnceItemCount(this),
            MomGiftManager.totalOnceItemCount(),
            MomGiftManager.pendingCount(this)
        )
    }

    override fun onResume() {
        super.onResume()
        updateSyncStatusLabel()
        updateBattleWinnings()
    }

    private fun checkForUpdatesManually() {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.check_for_updates)
            .setMessage(R.string.checking_for_updates)
            .setCancelable(false)
            .show()
        checkForUpdates(announceResult = true, onChecked = { progressDialog.dismiss() })
    }
}
