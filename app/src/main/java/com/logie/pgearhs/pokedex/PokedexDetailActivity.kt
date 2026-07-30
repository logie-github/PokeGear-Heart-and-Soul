package com.logie.pgearhs.pokedex

import android.content.Intent
import android.graphics.Bitmap
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
import android.widget.ImageView
import android.widget.TextView
import com.logie.pgearhs.R
import com.logie.pgearhs.ui.BaseImmersiveActivity

/**
 * The real HGSS Pokédex detail screen, rebuilt at the actual native 240x160 GBA coordinates
 * (see pokedex_plus_hgss.c) inside a [GbaScreenLayout], instead of a reflowed phone-style UI.
 *
 * Each tab is the decomp's own real, fully composited screenshot used as-is (the tab bar's
 * active-tab highlight is pre-baked per screen in HGSS_tilemap_{info,stats,evo,cry,size}_screen.bin
 * - it is NOT a separate dynamic layer here, so there is nothing to re-render). Real per-species
 * text/sprites from pokemon_dex_data.json are placed on top at the exact pixel offsets
 * pokedex_plus_hgss.c uses. Navigation is the real ◀/▶ arrows baked into every one of those
 * screenshots (x=0-16 and x=224-240), cycling through the six real screens in order - the same
 * left/right paging the real Pokédex uses, not invented per-tab tap zones.
 *
 * SIZE reuses Task_LoadSizeScreen's real sprite anchors (88,56)/(152,56) and title position.
 * CRY reuses pokedex_cry_screen.c's real box positions and the real cry_meter.png/
 * cry_meter_needle.png sprites (needle rotated from its real base pivot, not hand-drawn).
 * AREA has no equivalent full-screen reference in this decomp; it shows the real
 * johto_region_map.png with no marker, since no real per-species map-coordinate data exists.
 */
class PokedexDetailActivity : BaseImmersiveActivity() {

    companion object {
        const val EXTRA_SPECIES_ID = "species_id"

        // Real HGSS text sizes at native GBA resolution (matches the pixel-accurate HTML
        // prototype's .txt / .txt.small / .desc rules, themselves read off the real chrome).
        private const val TEXT_MAIN = 6.3f
        private const val TEXT_SMALL = 4.3f
        private const val TEXT_DESC = 5.3f

        private val COLOR_WHITE = Color.WHITE
        private val COLOR_DARK = Color.parseColor("#4A4A52")

        private const val WRAP = GbaScreenLayout.WRAP

        // Real ◀/▶ arrow glyph bounds, measured directly off the decoded tab-bar row -
        // every one of the five real screenshots below has them at the same position.
        private const val ARROW_W = 16
    }

    // null = no real full-screen background exists for that tab in this decomp (AREA);
    // its real content (johto_region_map.png) is placed at its own true, undistorted proportions
    // instead of stretched to fill the 240x160 slot every other tab's real screenshot fills exactly.
    private enum class Tab(val backgroundAsset: String?) {
        INFO("pokedex_chrome/info_bg.png"),
        AREA(null),
        STATS("pokedex_chrome/stats_bg.png"),
        EVO("pokedex_chrome/evo_bg.png"),
        CRY("pokedex_chrome/cry_bg.png"),
        SIZE("pokedex_chrome/size_bg.png")
    }

    private lateinit var gba: GbaScreenLayout
    private lateinit var bgImage: ImageView
    private lateinit var entry: PokedexEntry
    private var allEntries: List<PokedexEntry> = emptyList()
    private var activeTab: Tab = Tab.INFO

    private var cryPlayer: MediaPlayer? = null
    private var cryScope: CryScopeView? = null
    private val contentViews = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pokedex_detail)
        gba = findViewById(R.id.gbaScreen)

        val speciesId = intent.getIntExtra(EXTRA_SPECIES_ID, -1)
        allEntries = PokedexRepository.loadAll(this)
        entry = allEntries.firstOrNull { it.speciesId == speciesId } ?: run {
            finish()
            return
        }

        bgImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        gba.addNative(bgImage, 0, 0, GbaScreenLayout.NATIVE_W, GbaScreenLayout.NATIVE_H)

        // The real ◀/▶ arrows, baked into every screenshot at the same spot - cycle tabs in order.
        val leftArrow = View(this).apply { setOnClickListener { cycleTab(-1) } }
        gba.addNative(leftArrow, 0, 0, ARROW_W, 16)
        val rightArrow = View(this).apply { setOnClickListener { cycleTab(1) } }
        gba.addNative(rightArrow, GbaScreenLayout.NATIVE_W - ARROW_W, 0, ARROW_W, 16)

        showTab(Tab.INFO)
    }

    private fun cycleTab(delta: Int) {
        val tabs = Tab.entries
        val next = (activeTab.ordinal + delta + tabs.size) % tabs.size
        showTab(tabs[next])
    }

    // ===================== shared helpers =====================

    private fun setBackground(assetPath: String) {
        val bmp = assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        bgImage.setImageBitmap(bmp)
        (bgImage.drawable as? BitmapDrawable)?.isFilterBitmap = false
    }

    private fun clearContent() {
        contentViews.forEach { gba.removeView(it) }
        contentViews.clear()
        cryScope?.stop()
        cryScope = null
        cryPlayer?.release()
        cryPlayer = null
    }

    /** [w]/[h] may be [GbaScreenLayout.WRAP] to size to the text's natural content size. */
    private fun addText(
        x: Int, y: Int, w: Int, h: Int, text: String, nativeSize: Float,
        color: Int = COLOR_DARK, bold: Boolean = false, gravity: Int = Gravity.START, lines: Int = 1
    ): TextView {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            this.gravity = gravity
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            if (lines > 1) setLines(lines)
        }
        gba.addNative(tv, x, y, w, h, nativeSize)
        contentViews.add(tv)
        return tv
    }

    private fun addBitmap(x: Int, y: Int, w: Int, h: Int, bitmap: Bitmap): ImageView {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
        }
        gba.addNative(iv, x, y, w, h)
        (iv.drawable as? BitmapDrawable)?.isFilterBitmap = false
        contentViews.add(iv)
        return iv
    }

    private fun addAsset(x: Int, y: Int, w: Int, h: Int, assetPath: String): ImageView =
        addBitmap(x, y, w, h, assets.open(assetPath).use { BitmapFactory.decodeStream(it) })

    private fun addIdleIcon(x: Int, y: Int, w: Int, h: Int, assetFolder: String): IdleIconView {
        val v = IdleIconView(this)
        gba.addNative(v, x, y, w, h)
        contentViews.add(v)
        v.loadSpecies(assetFolder)
        return v
    }

    private fun typeIconBitmap(type: String): Bitmap? = try {
        assets.open("types/${type.lowercase()}.png").use { BitmapFactory.decodeStream(it) }
    } catch (e: java.io.IOException) {
        null
    }

    // ===================== tab switching =====================

    private fun showTab(tab: Tab) {
        activeTab = tab
        clearContent()
        tab.backgroundAsset?.let { setBackground(it) } ?: bgImage.setImageDrawable(null)
        when (tab) {
            Tab.INFO -> populateInfo()
            Tab.AREA -> populateArea()
            Tab.STATS -> populateStats()
            Tab.EVO -> populateEvo()
            Tab.CRY -> populateCry()
            Tab.SIZE -> populateSize()
        }
    }

    // ===================== INFO (real pokedex_chrome/info_bg.png + real coords) =====================

    private fun populateInfo() {
        addBitmap(16, 24, 64, 64, assets.open("pokemon/${entry.assetFolder}/front.png").use { BitmapFactory.decodeStream(it) })
        try {
            addAsset(120, 56, 16, 16, "pokemon/${entry.assetFolder}/footprint.png")
        } catch (e: java.io.IOException) { /* not every species has a footprint asset */ }

        entry.types.forEachIndexed { i, type ->
            typeIconBitmap(type)?.let { addBitmap(147 + i * 33, 48, 32, 16, it) }
        }

        addText(123, 15, WRAP, 8, getString(R.string.pokedex_number_format, entry.nationalDexNumber), TEXT_MAIN, COLOR_WHITE, bold = true)
        addText(157, 15, WRAP, 8, entry.displayName, TEXT_MAIN, COLOR_WHITE, bold = true)
        addText(123, 30, WRAP, 6, getString(R.string.pokedex_category_format, entry.category.lowercase().replaceFirstChar(Char::uppercase)), TEXT_SMALL)

        addText(155, 63, WRAP, 6, "HT", TEXT_SMALL)
        addText(180, 63, WRAP, 6, "%.1fm".format(entry.heightM), TEXT_SMALL)
        addText(155, 76, WRAP, 6, "WT", TEXT_SMALL)
        addText(180, 76, WRAP, 6, "%.1fkg".format(entry.weightKg), TEXT_SMALL)

        addText(
            4, 92, 232, 64,
            entry.pokedexEntry.replace(entry.name, entry.displayName),
            TEXT_DESC, gravity = Gravity.CENTER_HORIZONTAL, lines = 5
        )
    }

    // ===================== STATS (real pokedex_chrome/stats_bg.png + real coords) =====================

    private fun populateStats() {
        addIdleIcon(4, 4, 32, 32, entry.assetFolder)
        addText(44, 6, WRAP, 6, entry.displayName, TEXT_SMALL, bold = true)
        entry.types.forEachIndexed { i, type ->
            typeIconBitmap(type)?.let { addBitmap(8 + i * 34, 30, 32, 16, it) }
        }

        // winX=0, baseY=53, rowH=11 - real coords from PrintStatsScreen_Left.
        val rows = listOf(
            Triple("HP", 8, 23) to "hp", Triple("SPEED", 51, 78) to "speed",
            Triple("ATTACK", 8, 23) to "attack", Triple("SP.ATK", 51, 78) to "spAttack",
            Triple("DEFENSE", 8, 23) to "defense", Triple("SP.DEF", 51, 78) to "spDefense"
        )
        val baseY = 53
        val rowH = 11
        var total = 0
        rows.forEachIndexed { idx, (labelInfo, key) ->
            val (label, lx, vx) = labelInfo
            val y = baseY + rowH * (idx / 2)
            val value = entry.baseStats[key] ?: 0
            total += value
            addText(lx, y, WRAP, 6, label, TEXT_SMALL)
            addText(vx, y, 16, 6, value.toString(), TEXT_SMALL, gravity = Gravity.END)
        }
        addText(8, baseY + rowH * 3, WRAP, 6, "TOTAL", TEXT_SMALL)
        addText(51, baseY + rowH * 3, WRAP, 6, total.toString(), TEXT_SMALL)
    }

    // ===================== AREA (real johto_region_map.png; no marker - no real per-species
    // route/coordinate data exists in this decomp's dex JSON to place one honestly) ===========

    private fun populateArea() {
        // No real full-screen AREA background exists in this decomp (see Tab.AREA), so the real
        // johto_region_map.png (128x128) is placed at its own true square proportions rather than
        // stretched to fill the 240x160 slot - 96x96 here is a uniform 0.75x scale, not a warp.
        addAsset(72, 16, 96, 96, "pokedex_chrome/johto_region_map.png")
        val message = entry.habitat?.let { getString(R.string.pokedex_area_habitat_format, it) }
            ?: getString(R.string.pokedex_area_no_wild, entry.displayName)
        addText(4, 116, 232, 44, message, TEXT_SMALL, gravity = Gravity.CENTER_HORIZONTAL, lines = 4)
    }

    // ===================== EVO - real HGSS_tilemap_evo_screen.bin chrome; the real content box
    // is x=2,y=54,w=235,h=92 (measured off the decoded tilemap). The thin box above it (y=17-50)
    // has no confirmed real text source in this decomp, so it's left blank rather than guessed. ==

    private fun evoMethodLabel(method: String, param: Int): String = when (method) {
        "LEVEL" -> getString(R.string.pokedex_evo_method_level, param)
        "LEVEL_DAY" -> getString(R.string.pokedex_evo_method_level_day, param)
        "LEVEL_NIGHT" -> getString(R.string.pokedex_evo_method_level_night, param)
        "ITEM", "ITEM_HOLD" -> getString(R.string.pokedex_evo_method_item)
        "FRIENDSHIP" -> getString(R.string.pokedex_evo_method_friendship)
        "TRADE" -> getString(R.string.pokedex_evo_method_trade)
        else -> method.lowercase().replace('_', ' ')
    }

    private fun populateEvo() {
        if (entry.evolvesFrom.isEmpty() && entry.evolvesTo.isEmpty()) {
            addText(2, 90, 235, 20, getString(R.string.pokedex_evo_no_data, entry.displayName), TEXT_SMALL, gravity = Gravity.CENTER_HORIZONTAL, lines = 3)
            return
        }

        val chain = mutableListOf<Triple<String, Int?, String?>>() // kind("node"/"arrow"), speciesId, label
        entry.evolvesFrom.forEach {
            chain.add(Triple("node", it.speciesId, it.species))
            chain.add(Triple("arrow", null, evoMethodLabel(it.method, it.param)))
        }
        chain.add(Triple("node", entry.speciesId, entry.name))
        entry.evolvesTo.forEach {
            chain.add(Triple("arrow", null, evoMethodLabel(it.method, it.param)))
            chain.add(Triple("node", it.speciesId, it.species))
        }

        val boxX = 2; val boxY = 54; val boxW = 235; val boxH = 92
        val slotWidth = boxW / chain.size
        val iconTop = boxY + (boxH - 24) / 2 - 6
        chain.forEachIndexed { i, (kind, speciesId, label) ->
            val x = boxX + i * slotWidth
            if (kind == "node" && speciesId != null) {
                val target = allEntries.firstOrNull { it.speciesId == speciesId }
                if (target != null) {
                    val icon = addIdleIcon(x + slotWidth / 2 - 12, iconTop, 24, 24, target.assetFolder)
                    if (target.speciesId != entry.speciesId) {
                        icon.setOnClickListener {
                            startActivity(Intent(this, PokedexDetailActivity::class.java).putExtra(EXTRA_SPECIES_ID, target.speciesId))
                        }
                    }
                }
                addText(x, iconTop + 28, slotWidth, 8, label ?: "", TEXT_SMALL, gravity = Gravity.CENTER_HORIZONTAL, bold = speciesId == entry.speciesId)
            } else {
                addText(x, iconTop - 4, slotWidth, 16, "→\n${label ?: ""}", TEXT_SMALL - 1f, gravity = Gravity.CENTER_HORIZONTAL, lines = 2)
            }
        }
    }

    // ===================== CRY - real HGSS_tilemap_cry_screen.bin chrome + the real
    // cry_meter.png/cry_meter_needle.png sprites (not a hand-drawn gauge). Box positions measured
    // off the decoded tilemap: waveform 16,24,64,64 (left box); meter 144,24,80,64 (right box,
    // matches cry_meter.png's actual 80x64 size exactly). Sprite/text anchors are the real
    // MON_PAGE_X/Y (48,56) and PrintInfoScreenText/PrintCryScreenSpeciesName calls (82,33/82,49). =

    private fun populateCry() {
        addBitmap(48, 56, 64, 64, assets.open("pokemon/${entry.assetFolder}/front.png").use { BitmapFactory.decodeStream(it) })
        addText(82, 33, WRAP, 8, getString(R.string.pokedex_cry_of), TEXT_MAIN, bold = true)
        addText(82, 49, WRAP, 8, entry.displayName, TEXT_MAIN, bold = true)

        val scope = CryScopeView(this)
        gba.addNative(scope, 16, 24, 64, 64)
        contentViews.add(scope)
        cryScope = scope

        addAsset(144, 24, 80, 64, "pokedex_chrome/cry_meter.png")
        val needle = addAsset(160, 34, 48, 48, "pokedex_chrome/cry_meter_needle.png")
        needle.post {
            needle.pivotX = needle.width / 2f
            needle.pivotY = needle.height.toFloat()
        }
        scope.onNeedleAngle = { angle -> needle.rotation = angle }

        val cryAssetPath = "cries/${entry.assetFolder}.wav"
        val available = try { assets.open(cryAssetPath).close(); true } catch (e: java.io.IOException) { false }
        if (!available) return

        scope.loadWav(this, cryAssetPath)
        val playAction: () -> Unit = {
            cryPlayer?.release()
            val afd = assets.openFd(cryAssetPath)
            val mp = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                start()
            }
            cryPlayer = mp
            scope.attachPlayer(mp)
        }
        scope.setOnClickListener { playAction() }
        needle.setOnClickListener { playAction() }
        // Auto-play once on entering the tab, like the real CRY screen does.
        playAction()
    }

    // ===================== SIZE (real Task_LoadSizeScreen anchors: mon (88,56), trainer (152,56)) =

    private fun populateSize() {
        addText(
            0, 121, GbaScreenLayout.NATIVE_W, 8,
            getString(R.string.pokedex_size_title_format, getString(R.string.pokedex_size_trainer_name)),
            TEXT_SMALL, bold = true, gravity = Gravity.CENTER_HORIZONTAL
        )

        val scale = SizeScreenRepository.get(this, entry.nationalDexNumber) ?: run {
            addText(20, 60, 200, 30, getString(R.string.pokedex_size_unavailable), TEXT_SMALL, gravity = Gravity.CENTER_HORIZONTAL, lines = 3)
            return
        }

        val nativeBase = 64f
        val monSize = (nativeBase * 256f / scale.pokemonScale).toInt()
        val trSize = (nativeBase * 256f / scale.trainerScale).toInt()

        val monBmp = assets.open("pokemon/${entry.assetFolder}/front.png").use { BitmapFactory.decodeStream(it) }
        val monIv = addBitmap(88 - monSize / 2, 90 - monSize + scale.pokemonOffset, monSize, monSize, monBmp)
        monIv.colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)

        val trBmp = assets.open(SizeScreenRepository.trainerSilhouetteAssetPath()).use { BitmapFactory.decodeStream(it) }
        addBitmap(152 - trSize / 2, 90 - trSize + scale.trainerOffset, trSize, trSize, trBmp)
    }

    override fun onDestroy() {
        cryPlayer?.release()
        cryPlayer = null
        super.onDestroy()
    }
}
