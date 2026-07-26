package com.logie.pgearhs

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.widget.ImageView
import com.logie.pgearhs.ui.BaseImmersiveActivity
import com.logie.pgearhs.ui.MenuBackgroundPrefs
import com.logie.pgearhs.ui.ScrollingTiledBackgroundView

class MainActivity : BaseImmersiveActivity() {

    private lateinit var menuBackgroundDots: ScrollingTiledBackgroundView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (DisplayRouter.routeIfNeeded(this)) {
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        menuBackgroundDots = findViewById(R.id.menuBackgroundDots)

        // gearOutline is stretched to a non-integer height to reach the screen edges,
        // so disable the default bilinear filtering to keep it crisp.
        val gearOutline = findViewById<ImageView>(R.id.gearOutline)
        (gearOutline.drawable as? BitmapDrawable)?.isFilterBitmap = false

        findViewById<android.view.View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        menuBackgroundDots.movementPattern = MenuBackgroundPrefs.getMovementPattern(this)
    }
}
