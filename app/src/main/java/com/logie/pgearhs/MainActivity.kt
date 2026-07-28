package com.logie.pgearhs

import android.content.Intent
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.Toast
import com.logie.pgearhs.pokedex.PokedexActivity
import com.logie.pgearhs.sync.AppSyncManager
import com.logie.pgearhs.trainers.TrainerCallActivity
import com.logie.pgearhs.ui.BaseImmersiveActivity
import com.logie.pgearhs.ui.ButtonSelectionController
import com.logie.pgearhs.ui.MenuBackgroundPrefs
import com.logie.pgearhs.ui.ScrollingTiledBackgroundView

class MainActivity : BaseImmersiveActivity() {

    private lateinit var menuBackgroundDots: ScrollingTiledBackgroundView
    private lateinit var buttonSelection: ButtonSelectionController
    private lateinit var selectableButtons: List<ImageView>
    private var selectSound: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (DisplayRouter.routeIfNeeded(this)) {
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        AppSyncManager.startIfNeeded(this)

        menuBackgroundDots = findViewById(R.id.menuBackgroundDots)
        // The on-device MIDI synth doesn't honor this file's encoded tempo, playing it
        // back at roughly half the intended speed - compensate by doubling playback rate.
        selectSound = MediaPlayer.create(this, R.raw.se_select)?.apply {
            playbackParams = PlaybackParams().setSpeed(2f)
        }

        val mapButton = findViewById<ImageView>(R.id.buttonMap)
        val callButton = findViewById<ImageView>(R.id.buttonCall)
        val switchOffButton = findViewById<ImageView>(R.id.buttonSwitchOff)

        selectableButtons = listOf(mapButton, callButton, switchOffButton)
        buttonSelection = ButtonSelectionController(selectableButtons)
        buttonSelection.onSelectionChanged = {
            selectSound?.apply {
                seekTo(0)
                start()
            }
        }

        val showComingSoon = { showComingSoonToast() }
        mapButton.setOnClickListener { showComingSoon() }
        callButton.setOnClickListener { startActivity(Intent(this, TrainerCallActivity::class.java)) }
        switchOffButton.setOnClickListener { showComingSoon() }

        findViewById<android.view.View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Temporary - just a way to reach the Pokedex screen until it has a real home.
        findViewById<android.view.View>(R.id.pokedexButton).setOnClickListener {
            startActivity(Intent(this, PokedexActivity::class.java))
        }
    }

    private fun showComingSoonToast() {
        Toast.makeText(this, R.string.coming_soon_toast, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        menuBackgroundDots.movementPattern = MenuBackgroundPrefs.getMovementPattern(this)
        buttonSelection.start()
    }

    override fun onPause() {
        buttonSelection.stop()
        super.onPause()
    }

    override fun onDestroy() {
        selectSound?.release()
        selectSound = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                buttonSelection.moveSelection(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                buttonSelection.moveSelection(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER -> {
                selectableButtons[buttonSelection.selectedIndex].performClick()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
