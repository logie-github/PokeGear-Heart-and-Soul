package com.logie.pgearhs

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ImageView
import com.logie.pgearhs.ui.BaseImmersiveActivity
import com.logie.pgearhs.ui.ButtonSelectionController
import com.logie.pgearhs.ui.MenuBackgroundPrefs
import com.logie.pgearhs.ui.ScrollingTiledBackgroundView

class MainActivity : BaseImmersiveActivity() {

    private lateinit var menuBackgroundDots: ScrollingTiledBackgroundView
    private lateinit var buttonSelection: ButtonSelectionController
    private var selectSound: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (DisplayRouter.routeIfNeeded(this)) {
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        menuBackgroundDots = findViewById(R.id.menuBackgroundDots)
        selectSound = MediaPlayer.create(this, R.raw.se_select)

        buttonSelection = ButtonSelectionController(
            listOf(
                findViewById<ImageView>(R.id.buttonMap),
                findViewById<ImageView>(R.id.buttonCall),
                findViewById<ImageView>(R.id.buttonSwitchOff)
            )
        )
        buttonSelection.onSelectionChanged = {
            selectSound?.apply {
                seekTo(0)
                start()
            }
        }

        findViewById<android.view.View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
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
        }
        return super.onKeyDown(keyCode, event)
    }
}
