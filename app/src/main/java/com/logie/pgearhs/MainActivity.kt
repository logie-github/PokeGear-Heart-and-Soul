package com.logie.pgearhs

import android.content.Intent
import android.os.Bundle
import com.logie.pgearhs.ui.BaseImmersiveActivity

class MainActivity : BaseImmersiveActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (DisplayRouter.routeIfNeeded(this)) {
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        findViewById<android.view.View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
