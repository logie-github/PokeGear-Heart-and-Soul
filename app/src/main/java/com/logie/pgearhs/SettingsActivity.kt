package com.logie.pgearhs

import android.os.Bundle
import android.widget.RadioGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.logie.pgearhs.ui.BaseImmersiveActivity
import com.logie.pgearhs.ui.MenuBackgroundPrefs
import com.logie.pgearhs.ui.ScrollingTiledBackgroundView.MovementPattern

class SettingsActivity : BaseImmersiveActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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
    }
}
