package com.logie.pgearhs

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val preferTopSwitch = findViewById<SwitchMaterial>(R.id.preferTopDisplaySwitch)
        preferTopSwitch.isChecked = DisplayRouter.isTopDisplayPreferred(this)
        preferTopSwitch.setOnCheckedChangeListener { _, isChecked ->
            DisplayRouter.setTopDisplayPreferred(this, isChecked)
        }
    }
}
