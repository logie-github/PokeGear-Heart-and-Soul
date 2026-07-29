package com.logie.pgearhs.pokedex

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.logie.pgearhs.R
import com.logie.pgearhs.ui.BaseImmersiveActivity

/**
 * The real HGSS Pokédex detail screen: INFO / AREA / STATS / EVO / CRY / SIZE tabs,
 * reusing the exact tab set from pokedex_plus_hgss.c. Reached from PokedexActivity's list.
 */
class PokedexDetailActivity : BaseImmersiveActivity() {

    companion object {
        const val EXTRA_SPECIES_ID = "species_id"
    }

    private enum class Tab { INFO, AREA, STATS, EVO, CRY, SIZE }

    private lateinit var entry: PokedexEntry
    private var allEntries: List<PokedexEntry> = emptyList()
    private var cryPlayer: MediaPlayer? = null

    private lateinit var tabViews: Map<Tab, TextView>
    private lateinit var contentViews: Map<Tab, View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pokedex_detail)

        val speciesId = intent.getIntExtra(EXTRA_SPECIES_ID, -1)
        allEntries = PokedexRepository.loadAll(this)
        entry = allEntries.firstOrNull { it.speciesId == speciesId } ?: run {
            finish()
            return
        }

        tabViews = mapOf(
            Tab.INFO to findViewById(R.id.tabInfo),
            Tab.AREA to findViewById(R.id.tabArea),
            Tab.STATS to findViewById(R.id.tabStats),
            Tab.EVO to findViewById(R.id.tabEvo),
            Tab.CRY to findViewById(R.id.tabCry),
            Tab.SIZE to findViewById(R.id.tabSize)
        )
        contentViews = mapOf(
            Tab.INFO to findViewById(R.id.infoTabContent),
            Tab.AREA to findViewById(R.id.areaTabContent),
            Tab.STATS to findViewById(R.id.statsTabContent),
            Tab.EVO to findViewById(R.id.evoTabContent),
            Tab.CRY to findViewById(R.id.cryTabContent),
            Tab.SIZE to findViewById(R.id.sizeTabContent)
        )
        tabViews.forEach { (tab, view) -> view.setOnClickListener { showTab(tab) } }

        populateInfo()
        populateArea()
        populateStats()
        populateEvo()
        populateCry()
        populateSize()

        showTab(Tab.INFO)
    }

    private fun showTab(tab: Tab) {
        contentViews.forEach { (t, view) -> view.visibility = if (t == tab) View.VISIBLE else View.GONE }
        tabViews.forEach { (t, view) ->
            if (t == tab) {
                view.setBackgroundResource(R.drawable.pokedex_tab_active_bg)
                view.setTextColor(getColorCompat(R.color.pokedexRedDeep))
            } else {
                view.background = null
                view.setTextColor(getColorCompat(R.color.pokedexPink))
            }
        }
    }

    private fun getColorCompat(id: Int) = ContextCompat.getColor(this, id)

    private fun loadSpeciesBitmap(assetFolder: String, file: String) =
        assets.open("pokemon/$assetFolder/$file").use { BitmapFactory.decodeStream(it) }

    private fun crispImageView(imageView: ImageView) {
        (imageView.drawable as? BitmapDrawable)?.isFilterBitmap = false
    }

    private fun addTypeChip(container: LinearLayout, type: String, widthDp: Int, heightDp: Int, marginEndDp: Int) {
        val density = resources.displayMetrics.density
        val iv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((widthDp * density).toInt(), (heightDp * density).toInt()).apply {
                marginEnd = (marginEndDp * density).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = type
        }
        try {
            val bmp = assets.open("types/${type.lowercase()}.png").use { BitmapFactory.decodeStream(it) }
            iv.setImageBitmap(bmp)
            crispImageView(iv)
        } catch (e: java.io.IOException) {
            return
        }
        container.addView(iv)
    }

    // ===================== INFO =====================

    private fun populateInfo() {
        val spriteView = findViewById<ImageView>(R.id.detailSprite)
        spriteView.setImageBitmap(loadSpeciesBitmap(entry.assetFolder, "front.png"))
        crispImageView(spriteView)

        findViewById<TextView>(R.id.detailIdHeader).text =
            getString(R.string.pokedex_id_header_format, entry.nationalDexNumber, entry.displayName)
        findViewById<TextView>(R.id.detailCategory).text =
            getString(R.string.pokedex_category_format, entry.category.lowercase().replaceFirstChar(Char::uppercase))
        findViewById<TextView>(R.id.detailHeightWeight).text =
            getString(R.string.pokedex_height_weight_format, entry.heightM, entry.weightKg)

        val chips = findViewById<LinearLayout>(R.id.detailTypeChips)
        chips.removeAllViews()
        entry.types.forEach { addTypeChip(chips, it, widthDp = 36, heightDp = 18, marginEndDp = 6) }

        findViewById<TextView>(R.id.detailAbilities).text = entry.abilities.joinToString(", ") {
            it.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
        }
        // The source text renders the species' own name in ALL CAPS mid-sentence
        // (a leftover of the original game's text formatting); swap in the normal
        // title-cased name for readability without touching the rest of the text.
        findViewById<TextView>(R.id.detailPokedexEntry).text =
            entry.pokedexEntry.replace(entry.name, entry.displayName)
    }

    // ===================== AREA =====================

    private fun populateArea() {
        findViewById<TextView>(R.id.areaMessage).text = entry.habitat?.let {
            getString(R.string.pokedex_area_habitat_format, it)
        } ?: getString(R.string.pokedex_area_no_wild, entry.displayName)
    }

    // ===================== STATS =====================

    private fun populateStats() {
        findViewById<IdleIconView>(R.id.statsIcon).loadSpecies(entry.assetFolder)
        findViewById<TextView>(R.id.statsName).text = entry.displayName

        val chips = findViewById<LinearLayout>(R.id.statsTypeChips)
        chips.removeAllViews()
        entry.types.forEach { addTypeChip(chips, it, widthDp = 30, heightDp = 15, marginEndDp = 4) }

        val statOrder = listOf(
            "hp" to "HP", "attack" to "Attack", "defense" to "Defense",
            "spAttack" to "Sp. Atk", "spDefense" to "Sp. Def", "speed" to "Speed"
        )
        val grid = findViewById<GridLayout>(R.id.statsGrid)
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        var total = 0
        for ((key, label) in statOrder) {
            val value = entry.baseStats[key] ?: continue
            total += value
            val labelView = TextView(this).apply {
                text = label
                setTextColor(getColorCompat(R.color.pokedexInkDim))
                textSize = 12f
                layoutParams = GridLayout.LayoutParams().apply { setMargins(0, 0, (8 * density).toInt(), (6 * density).toInt()) }
            }
            val valueView = TextView(this).apply {
                text = value.toString()
                setTextColor(getColorCompat(R.color.pokedexInk))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 12f
                gravity = Gravity.END
                layoutParams = GridLayout.LayoutParams().apply { setMargins(0, 0, (16 * density).toInt(), (6 * density).toInt()) }
            }
            grid.addView(labelView)
            grid.addView(valueView)
        }
        findViewById<TextView>(R.id.statsTotal).text = total.toString()
    }

    // ===================== EVO =====================

    private fun evoMethodLabel(method: String, param: Int): String = when (method) {
        "LEVEL" -> getString(R.string.pokedex_evo_method_level, param)
        "LEVEL_DAY" -> getString(R.string.pokedex_evo_method_level_day, param)
        "LEVEL_NIGHT" -> getString(R.string.pokedex_evo_method_level_night, param)
        "ITEM", "ITEM_HOLD" -> getString(R.string.pokedex_evo_method_item)
        "FRIENDSHIP" -> getString(R.string.pokedex_evo_method_friendship)
        "TRADE" -> getString(R.string.pokedex_evo_method_trade)
        else -> method.lowercase().replace('_', ' ')
    }

    private fun buildEvoNode(container: LinearLayout, speciesId: Int, speciesName: String, current: Boolean) {
        val density = resources.displayMetrics.density
        val target = allEntries.firstOrNull { it.speciesId == speciesId }
        val node = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            if (current) setBackgroundResource(R.drawable.pokedex_tab_active_bg)
        }
        val icon = IdleIconView(this).apply {
            layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
        }
        target?.let { icon.loadSpecies(it.assetFolder) }
        val label = TextView(this).apply {
            text = speciesName.lowercase().replaceFirstChar(Char::uppercase)
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColorCompat(R.color.pokedexInk))
        }
        node.addView(icon)
        node.addView(label)
        if (!current && target != null) {
            node.setOnClickListener {
                startActivity(Intent(this, PokedexDetailActivity::class.java).putExtra(EXTRA_SPECIES_ID, target.speciesId))
            }
        }
        container.addView(node)
    }

    private fun buildEvoArrow(container: LinearLayout, label: String) {
        val density = resources.displayMetrics.density
        val arrow = TextView(this).apply {
            text = "→\n$label"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(getColorCompat(R.color.pokedexInkDim))
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
        }
        container.addView(arrow)
    }

    private fun populateEvo() {
        val container = findViewById<LinearLayout>(R.id.evoChainContainer)
        val emptyMessage = findViewById<TextView>(R.id.evoEmptyMessage)
        container.removeAllViews()

        if (entry.evolvesFrom.isEmpty() && entry.evolvesTo.isEmpty()) {
            emptyMessage.visibility = View.VISIBLE
            emptyMessage.text = getString(R.string.pokedex_evo_no_data, entry.displayName)
            return
        }
        emptyMessage.visibility = View.GONE

        entry.evolvesFrom.forEach { e ->
            buildEvoNode(container, e.speciesId, e.species, current = false)
            buildEvoArrow(container, evoMethodLabel(e.method, e.param))
        }
        buildEvoNode(container, entry.speciesId, entry.name, current = true)
        entry.evolvesTo.forEach { e ->
            buildEvoArrow(container, evoMethodLabel(e.method, e.param))
            buildEvoNode(container, e.speciesId, e.species, current = false)
        }
    }

    // ===================== CRY =====================

    private fun populateCry() {
        val caption = findViewById<TextView>(R.id.cryCaption)
        val button = findViewById<TextView>(R.id.cryPlayButton)
        val cryAssetPath = "cries/${entry.assetFolder}.wav"

        val available = try {
            assets.open(cryAssetPath).close()
            true
        } catch (e: java.io.IOException) {
            false
        }

        if (!available) {
            button.isEnabled = false
            button.alpha = 0.4f
            caption.text = getString(R.string.pokedex_cry_unavailable)
            return
        }

        caption.text = getString(R.string.pokedex_cry_caption_format, entry.assetFolder)
        button.setOnClickListener {
            cryPlayer?.release()
            cryPlayer = MediaPlayer().apply {
                val afd = assets.openFd(cryAssetPath)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                start()
            }
        }
    }

    // ===================== SIZE =====================

    private fun populateSize() {
        findViewById<TextView>(R.id.sizeTitle).text =
            getString(R.string.pokedex_size_title_format, getString(R.string.pokedex_size_trainer_name))

        val scale = SizeScreenRepository.get(this, entry.nationalDexNumber)
        val figuresRow = findViewById<LinearLayout>(R.id.sizeFiguresRow)
        val caption = findViewById<TextView>(R.id.sizeCaption)

        if (scale == null) {
            figuresRow.visibility = View.GONE
            caption.text = getString(R.string.pokedex_size_unavailable)
            return
        }
        figuresRow.visibility = View.VISIBLE

        val density = resources.displayMetrics.density
        val basePx = 84f * density
        val monPx = (basePx * 256f / scale.pokemonScale).toInt()
        val trPx = (basePx * 256f / scale.trainerScale).toInt()

        // Real Task_LoadSizeScreen technique: both sprites recolored to solid black
        // (graphics/pokedex/size_silhouette.gbapal is literally all-black), then scaled/
        // nudged with the per-species affine values above.
        val monView = findViewById<ImageView>(R.id.sizePokemonImage)
        monView.layoutParams = LinearLayout.LayoutParams(monPx, monPx)
        monView.setImageBitmap(loadSpeciesBitmap(entry.assetFolder, "front.png"))
        monView.colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
        monView.translationY = scale.pokemonOffset * density
        crispImageView(monView)

        val trView = findViewById<ImageView>(R.id.sizeTrainerImage)
        trView.layoutParams = LinearLayout.LayoutParams(trPx, trPx)
        trView.setImageBitmap(
            assets.open(SizeScreenRepository.trainerSilhouetteAssetPath()).use { BitmapFactory.decodeStream(it) }
        )
        trView.translationY = scale.trainerOffset * density
        crispImageView(trView)

        caption.text = getString(R.string.pokedex_size_caption_format, scale.pokemonScale, scale.trainerScale)
    }

    override fun onDestroy() {
        cryPlayer?.release()
        cryPlayer = null
        super.onDestroy()
    }
}
