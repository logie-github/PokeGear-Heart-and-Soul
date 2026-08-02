package com.logie.pgearhs.pokedex

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.logie.pgearhs.R
import com.logie.pgearhs.ui.BaseImmersiveActivity

/**
 * The Pokédex detail screen, built from the "Sidebar" layout in the Pokédex Screen HTML
 * prototype: a real responsive Android layout (see activity_pokedex_detail.xml), not a
 * fixed-pixel GBA canvas - so 4:3/16:9 landscape both just work with no scaling tricks.
 * Card/tab-bar chrome uses colors sampled from the decomp's real screenshots; content is real
 * per-species data from pokemon_dex_data.json, plus the real CRY meter/needle graphics and
 * SIZE screen's real per-species affine scale/offset table from pokedex_entries.h.
 */
class PokedexDetailActivity : BaseImmersiveActivity() {

    companion object {
        const val EXTRA_SPECIES_ID = "species_id"

        // Native pixel size of johto_region_map.png / the coordinate space AreaMarkerRepository's
        // marker x/y values are given in - see build_area_markers.py.
        const val AREA_MAP_NATIVE_SIZE = 256
        const val AREA_MARKER_NATIVE_SIZE = 16
    }

    private enum class Tab { INFO, AREA, STATS, EVO, CRY, SIZE }

    private lateinit var entry: PokedexEntry
    private var allEntries: List<PokedexEntry> = emptyList()
    private var cryPlayer: MediaPlayer? = null

    private lateinit var tabViews: Map<Tab, ImageView>
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

        contentViews = mapOf(
            Tab.INFO to findViewById(R.id.infoTabContent),
            Tab.AREA to findViewById(R.id.areaTabContent),
            Tab.STATS to findViewById(R.id.statsTabContent),
            Tab.EVO to findViewById(R.id.evoTabContent),
            Tab.CRY to findViewById(R.id.cryTabContent),
            Tab.SIZE to findViewById(R.id.sizeTabContent)
        )
        tabViews = buildTabBar()

        populateInfo()
        populateArea()
        populateStats()
        populateEvo()
        populateCry()
        populateSize()

        showTab(Tab.INFO)
    }

    // ===================== tab bar =====================

    // Real tab-label pixel art, cropped straight out of the decoded HGSS_tilemap_*_screen.bin
    // screenshots (see pokedex_chrome/tabs/) - not re-typed text. AREA has no real "active"
    // screenshot anywhere in this decomp, so it only ever shows its real inactive-pink crop.
    private fun tabAsset(tab: Tab, active: Boolean): String {
        val name = tab.name.lowercase()
        val suffix = if (active && tab != Tab.AREA) "active" else "inactive"
        return "pokedex_chrome/tabs/tab_${name}_$suffix.png"
    }

    // Real native pixel widths of each tab's crop (see pokedex_chrome/tabs/) - these sum to
    // exactly 240, the real HGSS tab bar's full native width, so sizing every tab proportionally
    // to these always reproduces the real bar's proportions with the row filling the screen
    // exactly - no scrolling, and the trailing ▶ baked into the SIZE crop never runs off the edge.
    private val tabNativeWidths = mapOf(
        Tab.INFO to 46, Tab.AREA to 38, Tab.STATS to 46,
        Tab.EVO to 34, Tab.CRY to 30, Tab.SIZE to 46
    )
    private val tabNativeHeight = 16

    private fun buildTabBar(): Map<Tab, ImageView> {
        val bar = findViewById<LinearLayout>(R.id.tabBar)
        val map = mutableMapOf<Tab, ImageView>()
        for (tab in Tab.entries) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setOnClickListener { showTab(tab) }
            }
            bar.addView(iv, LinearLayout.LayoutParams(0, 0))
            map[tab] = iv
        }
        bar.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val barWidth = bar.width
                if (barWidth <= 0) return
                bar.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val scale = barWidth / 240f
                for (tab in Tab.entries) {
                    val nativeW = tabNativeWidths.getValue(tab)
                    map.getValue(tab).layoutParams = LinearLayout.LayoutParams(
                        (nativeW * scale).toInt(), (tabNativeHeight * scale).toInt()
                    )
                }
                bar.requestLayout()
            }
        })
        return map
    }

    private fun showTab(tab: Tab) {
        contentViews.forEach { (t, view) -> view.visibility = if (t == tab) View.VISIBLE else View.GONE }
        tabViews.forEach { (t, iv) ->
            iv.setImageBitmap(assets.open(tabAsset(t, t == tab)).use { BitmapFactory.decodeStream(it) })
            crisp(iv)
        }
        if (tab == Tab.CRY) playCry() else cryPlayer?.let { it.pause() }
    }

    private fun getColorCompat(id: Int) = ContextCompat.getColor(this, id)

    // ===================== shared helpers =====================

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
            (iv.drawable as? BitmapDrawable)?.isFilterBitmap = false
        } catch (e: java.io.IOException) {
            return
        }
        container.addView(iv)
    }

    private fun crisp(imageView: ImageView) {
        (imageView.drawable as? BitmapDrawable)?.isFilterBitmap = false
    }

    // ===================== INFO =====================

    private fun populateInfo() {
        val spriteView = findViewById<ImageView>(R.id.detailSprite)
        spriteView.setImageBitmap(assets.open("pokemon/${entry.assetFolder}/front.png").use { BitmapFactory.decodeStream(it) })
        crisp(spriteView)

        findViewById<TextView>(R.id.detailIdHeader).text =
            getString(R.string.pokedex_id_header_format, entry.nationalDexNumber, entry.displayName)
        findViewById<TextView>(R.id.detailCategory).text =
            getString(R.string.pokedex_category_format, entry.category.lowercase().replaceFirstChar(Char::uppercase))
        findViewById<TextView>(R.id.detailHeightValue).text =
            getString(R.string.pokedex_height_value_format, entry.heightM)
        findViewById<TextView>(R.id.detailWeightValue).text =
            getString(R.string.pokedex_weight_value_format, entry.weightKg)

        val footprintBox = findViewById<View>(R.id.detailFootprintBox)
        try {
            val footprint = findViewById<ImageView>(R.id.detailFootprint)
            footprint.setImageBitmap(
                assets.open("pokemon/${entry.assetFolder}/footprint.png").use { BitmapFactory.decodeStream(it) }
            )
            crisp(footprint)
            footprintBox.visibility = View.VISIBLE
        } catch (e: java.io.IOException) {
            // not every species has a footprint asset
            footprintBox.visibility = View.GONE
        }
        layoutTopRow { sizeVitalsCardToIdCard() }

        val chips = findViewById<LinearLayout>(R.id.detailTypeChips)
        entry.types.forEach { addTypeChip(chips, it, widthDp = 72, heightDp = 36, marginEndDp = 4) }

        findViewById<TextView>(R.id.detailPokedexEntry).text =
            entry.pokedexEntry.replace(entry.name, entry.displayName)
    }

    // Sizes the vitals card to exactly 3/4 of the name box's real measured width, then aligns
    // the type chips row (which sits above it) to start at the vitals card's real left edge -
    // both need a runtime measurement since a static weight ratio can't hit them exactly (the
    // name box's column weight also has the sprite region's fixed intrinsic width baked into
    // its share, and the vitals card's left edge depends on its own resized width).
    private fun sizeVitalsCardToIdCard() {
        val idCard = findViewById<View>(R.id.detailIdCard)
        val vitalsCard = findViewById<View>(R.id.detailVitalsCard)
        val typeChips = findViewById<View>(R.id.detailTypeChips)
        var widthApplied = false
        vitalsCard.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!widthApplied) {
                    if (idCard.width <= 0) return
                    val lp = vitalsCard.layoutParams as LinearLayout.LayoutParams
                    val targetWidth = (idCard.width * 0.75f).toInt()
                    widthApplied = true
                    if (lp.width != targetWidth) {
                        lp.width = targetWidth
                        vitalsCard.layoutParams = lp
                        return // wait for the layout pass this triggers before reading .left
                    }
                }
                if (vitalsCard.width <= 0) return
                vitalsCard.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val chipsLp = typeChips.layoutParams as LinearLayout.LayoutParams
                chipsLp.marginStart = vitalsCard.left
                typeChips.layoutParams = chipsLp
                fillDottedDivider()
            }
        })
    }

    // Fills the HT/WT divider with real small dot Views spaced to exactly fill its final width -
    // a GradientDrawable dashed stroke didn't reliably render across devices/hardware
    // acceleration, so this builds the dots as plain Views instead.
    private fun fillDottedDivider() {
        val divider = findViewById<LinearLayout>(R.id.detailDottedDivider)
        divider.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (divider.width <= 0) return
                divider.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val density = resources.displayMetrics.density
                val dotSize = (4 * density).toInt()
                val gap = (5 * density).toInt()
                val count = (divider.width / (dotSize + gap)).coerceAtLeast(1)
                divider.removeAllViews()
                for (i in 0 until count) {
                    val dot = View(this@PokedexDetailActivity).apply {
                        setBackgroundResource(R.drawable.pokedex_dot)
                        layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                            if (i < count - 1) marginEnd = gap
                        }
                    }
                    divider.addView(dot)
                }
            }
        })
    }

    // Widens the id/footprint column to exactly 1.25x its real measured width, shrinking the
    // sprite region down to its bare minimum (sprite + end padding, no extra breathing room) to
    // make room - clamped so the sprite is never cut into. Then centers the sprite in whatever
    // room is left, and finally runs onDone() (see sizeVitalsCardToIdCard()), since that depends
    // on the id column's FINAL width, not its initial weight-based one.
    private fun layoutTopRow(onDone: () -> Unit) {
        val topRow = findViewById<View>(R.id.detailTopRow)
        val leftArea = findViewById<View>(R.id.detailLeftArea)
        val sprite = findViewById<View>(R.id.detailSprite)
        val idColumn = findViewById<View>(R.id.detailIdColumn)
        var widthApplied = false
        topRow.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!widthApplied) {
                    if (idColumn.width <= 0 || leftArea.width <= 0 || sprite.width <= 0 || topRow.width <= 0) return
                    widthApplied = true
                    val naturalLeftAreaWidth = sprite.width + leftArea.paddingEnd
                    val target = (idColumn.width * 1.25f).toInt()
                        .coerceAtMost(topRow.width - naturalLeftAreaWidth)

                    val leftLp = leftArea.layoutParams as LinearLayout.LayoutParams
                    leftLp.width = naturalLeftAreaWidth
                    leftLp.weight = 0f
                    leftArea.layoutParams = leftLp

                    val colLp = idColumn.layoutParams as LinearLayout.LayoutParams
                    colLp.width = target
                    colLp.weight = 0f
                    idColumn.layoutParams = colLp
                    return // wait for the layout pass these changes trigger
                }
                if (leftArea.width <= 0) return
                topRow.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val availableForSprite = leftArea.width - leftArea.paddingEnd
                val spriteLp = sprite.layoutParams as FrameLayout.LayoutParams
                spriteLp.marginStart = ((availableForSprite - sprite.width) / 2).coerceAtLeast(0)
                sprite.layoutParams = spriteLp
                onDone()
            }
        })
    }

    // ===================== AREA =====================

    private fun populateArea() {
        val map = findViewById<ImageView>(R.id.areaMap)
        map.setImageBitmap(assets.open("pokedex_chrome/johto_region_map.png").use { BitmapFactory.decodeStream(it) })
        crisp(map)

        val indicators = AreaMarkerRepository.get(this, entry.speciesConst)
        val messageView = findViewById<TextView>(R.id.areaMessage)
        val layer = findViewById<FrameLayout>(R.id.areaMarkerLayer)
        layer.removeAllViews()

        if (indicators.isEmpty()) {
            messageView.text = getString(R.string.pokedex_area_no_wild, entry.displayName)
            addAreaOverlaySprite(layer, map, "pokedex_chrome/area/area_unknown.png", widthNative = 96, heightNative = 32)
            return
        }

        val locationNames = indicators.map { it.mapsec.removePrefix("MAPSEC_").split("_")
            .joinToString(" ") { w -> w.lowercase().replaceFirstChar(Char::uppercase) } }
            .distinct()
        messageView.text = getString(R.string.pokedex_area_found_in_format, locationNames.joinToString(", "))

        map.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (map.width <= 0 || map.height <= 0) return
                map.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val (scale, offsetX, offsetY) = centerCropTransform(map)
                val markerSize = (AREA_MARKER_NATIVE_SIZE * scale).toInt()
                // glows first so marker dots always draw on top of them
                indicators.sortedBy { it.type != "glow" }.forEach { indicator ->
                    val view = if (indicator.type == "glow") {
                        View(this@PokedexDetailActivity).apply {
                            setBackgroundResource(R.drawable.pokedex_area_glow)
                            layoutParams = FrameLayout.LayoutParams(
                                (indicator.rectW * scale).toInt().coerceAtLeast(1),
                                (indicator.rectH * scale).toInt().coerceAtLeast(1)
                            ).apply {
                                leftMargin = (offsetX + indicator.rectX * scale).toInt()
                                topMargin = (offsetY + indicator.rectY * scale).toInt()
                            }
                        }
                    } else {
                        ImageView(this@PokedexDetailActivity).apply {
                            setImageBitmap(
                                assets.open("pokedex_chrome/area/area_marker.png").use { BitmapFactory.decodeStream(it) }
                            )
                            (drawable as? BitmapDrawable)?.isFilterBitmap = false
                            layoutParams = FrameLayout.LayoutParams(markerSize, markerSize).apply {
                                leftMargin = (offsetX + indicator.x * scale - markerSize / 2f).toInt()
                                topMargin = (offsetY + indicator.y * scale - markerSize / 2f).toInt()
                            }
                        }
                    }
                    layer.addView(view)
                }
            }
        })
    }

    // areaMap uses scaleType="centerCrop" so it fully covers a non-square tab area with no white
    // space - which means a single width-based scale factor isn't enough to place overlays
    // correctly anymore. This mirrors ImageView's own centerCrop math: uniform scale is the
    // LARGER of the two axis ratios, and whichever axis has leftover size gets a (possibly
    // negative) centering offset applied, cropping that axis instead of letterboxing it.
    private fun centerCropTransform(map: ImageView): Triple<Float, Float, Float> {
        val scale = maxOf(
            map.width.toFloat() / AREA_MAP_NATIVE_SIZE,
            map.height.toFloat() / AREA_MAP_NATIVE_SIZE
        )
        val offsetX = (map.width - AREA_MAP_NATIVE_SIZE * scale) / 2f
        val offsetY = (map.height - AREA_MAP_NATIVE_SIZE * scale) / 2f
        return Triple(scale, offsetX, offsetY)
    }

    // Overlays a real sprite (native GBA pixel size) centered on the area map, scaled/offset by
    // the map's own current centerCrop transform so it stays crisp and correctly placed.
    private fun addAreaOverlaySprite(layer: FrameLayout, map: ImageView, assetPath: String, widthNative: Int, heightNative: Int) {
        map.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (map.width <= 0 || map.height <= 0) return
                map.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val (scale, _, _) = centerCropTransform(map)
                val sprite = ImageView(this@PokedexDetailActivity).apply {
                    setImageBitmap(assets.open(assetPath).use { BitmapFactory.decodeStream(it) })
                    (drawable as? BitmapDrawable)?.isFilterBitmap = false
                    layoutParams = FrameLayout.LayoutParams(
                        (widthNative * scale).toInt(),
                        (heightNative * scale).toInt()
                    ).apply { gravity = Gravity.CENTER }
                }
                layer.addView(sprite)
            }
        })
    }

    // ===================== STATS =====================

    private fun populateStats() {
        findViewById<IdleIconView>(R.id.statsIcon).loadSpecies(entry.assetFolder)
        findViewById<TextView>(R.id.statsName).text = entry.displayName

        val chips = findViewById<LinearLayout>(R.id.statsTypeChips)
        entry.types.forEach { addTypeChip(chips, it, widthDp = 30, heightDp = 15, marginEndDp = 4) }

        val statOrder = listOf(
            "hp" to "HP", "attack" to "Attack", "defense" to "Defense",
            "spAttack" to "Sp. Atk", "spDefense" to "Sp. Def", "speed" to "Speed"
        )
        val grid = findViewById<GridLayout>(R.id.statsGrid)
        val density = resources.displayMetrics.density
        var total = 0
        for ((key, label) in statOrder) {
            val value = entry.baseStats[key] ?: continue
            total += value
            grid.addView(TextView(this).apply {
                text = label
                setTextColor(getColorCompat(R.color.pokedexInkDim))
                textSize = 12f
                layoutParams = GridLayout.LayoutParams().apply { setMargins(0, 0, (8 * density).toInt(), (6 * density).toInt()) }
            })
            grid.addView(TextView(this).apply {
                text = value.toString()
                setTextColor(getColorCompat(R.color.pokedexInk))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 12f
                gravity = Gravity.END
                layoutParams = GridLayout.LayoutParams().apply { setMargins(0, 0, (16 * density).toInt(), (6 * density).toInt()) }
            })
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
            setTypeface(typeface, Typeface.BOLD)
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

    private lateinit var cryScope: CryScopeView

    private fun populateCry() {
        val sprite = findViewById<ImageView>(R.id.crySprite)
        sprite.setImageBitmap(assets.open("pokemon/${entry.assetFolder}/front.png").use { BitmapFactory.decodeStream(it) })
        crisp(sprite)
        findViewById<TextView>(R.id.cryName).text = entry.displayName

        val meter = findViewById<ImageView>(R.id.cryMeter)
        meter.setImageBitmap(assets.open("pokedex_chrome/cry_meter.png").use { BitmapFactory.decodeStream(it) })
        crisp(meter)

        val needle = findViewById<ImageView>(R.id.cryNeedle)
        needle.setImageBitmap(assets.open("pokedex_chrome/cry_meter_needle.png").use { BitmapFactory.decodeStream(it) })
        crisp(needle)
        needle.post {
            needle.pivotX = needle.width / 2f
            needle.pivotY = needle.height.toFloat()
        }

        cryScope = findViewById(R.id.cryScope)
        cryScope.onNeedleAngle = { angle -> needle.rotation = angle }

        val cryAssetPath = "cries/${entry.assetFolder}.wav"
        val available = try { assets.open(cryAssetPath).close(); true } catch (e: java.io.IOException) { false }
        if (!available) return
        cryScope.loadWav(this, cryAssetPath)
        cryScope.setOnClickListener { playCry() }
        needle.setOnClickListener { playCry() }
    }

    private fun playCry() {
        val cryAssetPath = "cries/${entry.assetFolder}.wav"
        val available = try { assets.open(cryAssetPath).close(); true } catch (e: java.io.IOException) { false }
        if (!available) return
        cryPlayer?.release()
        val afd = assets.openFd(cryAssetPath)
        val mp = MediaPlayer().apply {
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            prepare()
            start()
        }
        cryPlayer = mp
        cryScope.attachPlayer(mp)
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

        val monView = findViewById<ImageView>(R.id.sizePokemonImage)
        monView.layoutParams = LinearLayout.LayoutParams(monPx, monPx)
        monView.setImageBitmap(assets.open("pokemon/${entry.assetFolder}/front.png").use { BitmapFactory.decodeStream(it) })
        monView.colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
        monView.translationY = scale.pokemonOffset * density
        crisp(monView)

        val trView = findViewById<ImageView>(R.id.sizeTrainerImage)
        trView.layoutParams = LinearLayout.LayoutParams(trPx, trPx)
        trView.setImageBitmap(
            assets.open(SizeScreenRepository.trainerSilhouetteAssetPath()).use { BitmapFactory.decodeStream(it) }
        )
        trView.translationY = scale.trainerOffset * density
        crisp(trView)

        caption.text = getString(R.string.pokedex_size_caption_format, scale.pokemonScale, scale.trainerScale)
    }

    override fun onDestroy() {
        cryPlayer?.release()
        cryPlayer = null
        super.onDestroy()
    }
}
