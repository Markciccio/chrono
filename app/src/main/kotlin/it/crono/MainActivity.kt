package it.crono

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.Geocoder
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.io.OutputStreamWriter
import kotlin.math.abs

/** Track-day focused MVP: big live data, robust timing-line crossings and voice feedback. */
class MainActivity : Activity(), LocationListener, TextToSpeech.OnInitListener {
    private enum class VoiceBriefingMode { ALL, SECTORS_AND_LAPS, LAPS_ONLY }
    private enum class LiveTimingSize(val label: String, val scale: Float) {
        COMPACT("Compatto", 1f), STANDARD("Standard", 1.14f), LARGE("Grande", 1.28f)
    }
    private enum class ScreenMode(val label: String, val orientation: Int) {
        LANDSCAPE("Orizzontale", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
        PORTRAIT("Verticale", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        AUTO("Automatica", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    }
    private data class SectorDisplay(val elapsedMs: Long, val deltaMs: Long?)
    private data class DistanceComparison(
        val distanceM: DoubleArray, val speedA: FloatArray, val speedB: FloatArray,
        val cumulativeMs: FloatArray, val localMs: FloatArray, val sectorFractions: List<Float>
    )
    private data class LocalDeltaBin(val start: Int, val end: Int, val deltaMs: Float)

    private lateinit var locationManager: LocationManager
    private lateinit var dashboard: RaceView
    private lateinit var trackMap: TrackMapView
    private lateinit var status: TextView
    private var tts: TextToSpeech? = null
    /** Utterances after which predictive delta calls must stay quiet. */
    private val speechSilenceAfter = mutableSetOf<String>()

    private var route = emptyList<TrackPoint>()
    private var latestFix: GpsSample? = null
    private var previousFix: GpsSample? = null
    private var running = false
    private var paused = false
    private var pausedLapElapsedMs: Long? = null
    private var lowSpeedSinceMs: Long? = null
    private var screenLockOverlay: View? = null
    private var activeSavedTrack: SavedTrack? = null
    private val timing = TimingEngine()
    private var bestLap: Lap? = null
    private var predictor: PredictiveDelta? = null
    private var lastLapMs: Long? = null
    private var lastBestReminderLap = 0
    private var liveDeltaMs: Long? = null
    private var lastDeltaAnnouncementElapsedMs = Long.MIN_VALUE
    private val autoFinish = AutoFinishDetector()
    private val liveRoute = mutableListOf<TrackPoint>()
    private val sessionSamples = mutableListOf<GpsSample>()
    private var sectorReferences: List<SectorReference> = emptyList()
    private var lastAnnouncedDeltaMs: Long? = null
    private var previousLiveDeltaMs: Long? = null
    /** A lap call has radio priority over any live-delta call. */
    private var deltaAnnouncementsSuppressedUntilMs = Long.MIN_VALUE
    private var voiceEnabled = true
    private var preferredVoiceName: String? = null
    private var voiceAlertIntervalMs = 10_000L
    private var voiceBriefingMode = VoiceBriefingMode.ALL
    /** Adds the unit only occasionally: radio calls stay compact but never ambiguous. */
    private var sectorVoiceAnnouncementCount = 0
    private val preferences by lazy { getSharedPreferences("pit_engineer_options", MODE_PRIVATE) }
    private var liveTimingSize = LiveTimingSize.STANDARD
    private var screenMode = ScreenMode.LANDSCAPE
    private val simulationHandler = Handler(Looper.getMainLooper())
    private var simulator: DebugGpsSimulator? = null
    private var testButton: Button? = null
    private var startButton: Button? = null
    private var pauseButton: Button? = null
    private var lockButton: Button? = null
    private lateinit var sessionStore: SessionStore
    private lateinit var trackStore: TrackStore
    private val recordedLaps = mutableListOf<RecordedLap>()
    private val currentSectorTimes = linkedMapOf<Int, Long>()
    /** Best duration for each individual segment, independent from the best full lap. */
    private val bestSectorSegmentMs = mutableMapOf<Int, Long>()
    /** Sectors added without a usable baseline must not be announced as purple on their first pass. */
    private val pendingSectorBaseline = mutableSetOf<Int>()
    private val simulationTick = object : Runnable {
        override fun run() {
            val activeSimulator = simulator ?: return
            processSample(activeSimulator.next())
            simulationHandler.postDelayed(this, DebugGpsSimulator.STEP_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveTimingSize = LiveTimingSize.entries.getOrElse(preferences.getInt("liveTimingSize", LiveTimingSize.STANDARD.ordinal)) { LiveTimingSize.STANDARD }
        screenMode = ScreenMode.entries.getOrElse(preferences.getInt("screenMode", ScreenMode.LANDSCAPE.ordinal)) { ScreenMode.LANDSCAPE }
        if (requestedOrientation != screenMode.orientation) requestedOrientation = screenMode.orientation
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sessionStore = SessionStore(this)
        trackStore = TrackStore(this)
        preferredVoiceName = preferences.getString("engineerVoice", null)
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) {
                if (utteranceId != null) speechSilenceAfter.remove(utteranceId)
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId != null && speechSilenceAfter.remove(utteranceId)) {
                    // The five seconds begin after TTS has actually finished speaking,
                    // not at the instant the finish/sector line is crossed.
                    deltaAnnouncementsSuppressedUntilMs = System.currentTimeMillis() + 5_000L
                }
            }
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.rgb(3, 9, 14))
        }
        status = TextView(this).apply {
            setTextColor(Color.rgb(187, 223, 235))
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            background = hudPanel(Color.rgb(7, 22, 31), Color.rgb(28, 148, 190), 1)
            text = "Pronto · premi AVVIA e percorri il primo giro"
        }
        val optionsButton = Button(this).apply {
            text = "☰"
            textSize = 19f
            setTextColor(Color.rgb(72, 205, 255))
            background = hudPanel(Color.rgb(7, 22, 31), Color.rgb(28, 148, 190), 1)
            contentDescription = "Opzioni avanzate"
            setOnClickListener { showAdvancedOptions(this) }
        }
        val closeButton = Button(this).apply {
            text = "×"
            textSize = 24f
            setTextColor(Color.rgb(72, 205, 255))
            background = hudPanel(Color.rgb(7, 22, 31), Color.rgb(28, 148, 190), 1)
            contentDescription = "Chiudi applicazione"
            setOnClickListener { requestClose() }
        }
        val brand = TextView(this).apply {
            text = "PIT ENGINEER"
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = .06f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(72, 205, 255))
            background = hudPanel(Color.rgb(7, 22, 31), Color.rgb(28, 148, 190), 1)
        }
        dashboard = RaceView().apply {
            onTrackTapped = ::handleTrackTap
            setLiveTimingScale(liveTimingSize.scale)
        }
        trackMap = TrackMapView(this, ::handleTrackTap)
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val closeWidth = dp(if (isPortrait) 44 else 48)
        val headerCellHeight = dp(40)
        // Keep a physical gap before the floating close control: overlapping cyan outlines made
        // the GPS/status panel look squeezed even though its layout width was unchanged.
        status.setPadding(dp(14), 0, dp(8), 0)
        val headerContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(optionsButton, LinearLayout.LayoutParams(dp(if (isPortrait) 48 else 58), headerCellHeight))
            addView(brand, LinearLayout.LayoutParams(dp(if (isPortrait) 140 else 178), headerCellHeight).apply { setMargins(dp(4), 0, dp(4), 0) })
            addView(status, LinearLayout.LayoutParams(0, headerCellHeight, 1f))
        }
        val header = FrameLayout(this).apply {
            addView(headerContent, FrameLayout.LayoutParams(-1, dp(42)).apply { rightMargin = closeWidth + dp(4) })
            addView(closeButton, FrameLayout.LayoutParams(closeWidth, headerCellHeight, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(42)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val firstControlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val secondControlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun controlParams(weight: Float) = LinearLayout.LayoutParams(0, dp(37), weight).apply { setMargins(dp(2), dp(2), dp(2), 0) }
        fun control(row: LinearLayout, label: String, weight: Float = 1f, action: () -> Unit) {
            row.addView(actionButton(label, action), controlParams(weight))
        }
        startButton = actionButton("AVVIA") { toggleSession() }
        firstControlRow.addView(startButton, controlParams(1f))
        pauseButton = actionButton("PAUSA") { togglePause() }
        firstControlRow.addView(pauseButton, controlParams(1f))
        testButton = actionButton("TEST GPS") { toggleSimulation() }
        secondControlRow.addView(testButton, controlParams(.5f))
        lockButton = actionButton("BLOCCA") { lockScreen() }
        secondControlRow.addView(lockButton, controlParams(.5f))
        controls.addView(firstControlRow, LinearLayout.LayoutParams(-1, dp(39)))
        controls.addView(secondControlRow, LinearLayout.LayoutParams(-1, dp(39)))

        val content = LinearLayout(this).apply { orientation = if (isPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val mapColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(trackMap, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(-1, dp(80)))
        }
        if (isPortrait) {
            content.addView(mapColumn, LinearLayout.LayoutParams(-1, 0, .39f).apply { setMargins(0, dp(6), 0, dp(4)) })
            content.addView(dashboard, LinearLayout.LayoutParams(-1, 0, .61f).apply { setMargins(0, dp(4), 0, 0) })
        } else {
            content.addView(mapColumn, LinearLayout.LayoutParams(0, -1, .35f).apply { setMargins(0, dp(6), dp(5), 0) })
            content.addView(dashboard, LinearLayout.LayoutParams(0, -1, .65f).apply { setMargins(dp(5), dp(6), 0, 0) })
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        ensureLocationPermission()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGps()
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        val isClose = label == "CHIUDI"
        text = if (isClose) "×" else label
        textSize = if (isClose) 25f else 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        minHeight = dp(44)
        val accent = when (label) {
            "AVVIA" -> Color.rgb(24, 213, 184)
            "RESET" -> Color.rgb(255, 91, 100)
            "SPOSTA TRAGUARDO" -> Color.rgb(174, 119, 255)
            "CHIUDI" -> Color.rgb(72, 205, 255)
            else -> Color.rgb(255, 185, 64)
        }
        if (isClose) contentDescription = "Chiudi"
        setTextColor(accent)
        background = hudPanel(Color.rgb(8, 23, 32), accent, 2)
        setOnClickListener { action() }
    }

    private fun hudPanel(fill: Int, stroke: Int, strokeDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(strokeDp), stroke)
        cornerRadius = dp(7).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Keeps the telemetry visible while swallowing every accidental touch in a pocket. */
    private fun lockScreen() {
        if (screenLockOverlay != null) return
        screenLockOverlay = View(this).apply {
            // Nearly transparent on purpose: numbers stay readable, but no control receives taps.
            setBackgroundColor(Color.argb(8, 0, 0, 0))
            isClickable = true
            // Consume every touch: unlock is intentionally limited to physical volume keys.
            setOnTouchListener { _, _ -> true }
        }
        addContentView(screenLockOverlay, ViewGroup.LayoutParams(-1, -1))
        status.text = "🔒 SCHERMO BLOCCATO · premi un tasto volume per sbloccare"
        speak("Schermo bloccato", flush = true)
    }

    private fun unlockScreen() {
        val overlay = screenLockOverlay ?: return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        screenLockOverlay = null
        status.text = if (running) "Registrazione attiva" else "Schermo sbloccato"
        speak("Schermo sbloccato", flush = true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (screenLockOverlay != null && event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            unlockScreen()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showAdvancedOptions(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(if (voiceEnabled) "Voce: attiva" else "Voce: disattivata")
            menu.add("Avvisi di pista")
            menu.add("Frequenza avvisi vocali")
            menu.add("Voce dell'ingegnere")
            menu.add("Dimensione live timing")
            menu.add("Orientamento schermo")
            menu.add("Elenco piste")
            menu.add("Inquadra tutta la traccia")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Voce: attiva", "Voce: disattivata" -> {
                        voiceEnabled = !voiceEnabled
                        status.text = if (voiceEnabled) "Messaggi vocali attivati" else "Messaggi vocali disattivati"
                        if (voiceEnabled) speak("Messaggi vocali attivati", flush = true)
                    }
                    "Avvisi di pista" -> showVoiceBriefingMenu()
                    "Frequenza avvisi vocali" -> showVoiceFrequencyMenu()
                    "Voce dell'ingegnere" -> showEngineerVoiceMenu()
                    "Dimensione live timing" -> showLiveTimingSizeMenu()
                    "Orientamento schermo" -> showScreenOrientationMenu()
                    "Elenco piste" -> showTrackList()
                    "Inquadra tutta la traccia" -> {
                        trackMap.fitEntireTrack()
                        status.text = "Mappa adattata alla traccia"
                    }
                }
                true
            }
            show()
        }
    }

    /** Library of circuits obtained from previous sessions. Editing is intentionally only
     * available while stopped, so a pocket tap can never change a live timing line. */
    private fun showTrackList() {
        val tracks = trackStore.list()
        if (tracks.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Elenco piste")
                .setMessage("Non hai ancora piste salvate. Apri una sessione e premi + PISTA sul giro migliore.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)) }
        val scroll = ScrollView(this).apply { addView(list) }
        val dialog = AlertDialog.Builder(this).setTitle("Elenco piste").setView(scroll).setNegativeButton("×", null).create()
        tracks.forEach { track ->
            val open = Button(this).apply {
                text = "${track.name}\n${formatTime(track.lap.durationMs)} · ${track.sectors.size} settori"
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
                background = hudPanel(Color.rgb(7, 24, 33), Color.rgb(28, 148, 190), 1)
                setOnClickListener { dialog.dismiss(); showTrackEditor(track) }
            }
            val delete = Button(this).apply {
                text = "🗑"
                textSize = 18f
                contentDescription = "Elimina ${track.name}"
                setTextColor(Color.rgb(255, 112, 112))
                background = hudPanel(Color.rgb(37, 16, 22), Color.rgb(255, 91, 100), 1)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Eliminare pista?")
                        .setMessage(track.name)
                        .setNegativeButton("ANNULLA", null)
                        .setPositiveButton("ELIMINA") { _, _ ->
                            trackStore.delete(track)
                            if (activeSavedTrack?.id == track.id) useAutomaticFinish()
                            dialog.dismiss()
                            showTrackList()
                        }
                        .show()
                }
            }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
                addView(open, LinearLayout.LayoutParams(0, dp(58), 1f))
                addView(delete, LinearLayout.LayoutParams(dp(52), dp(58)).apply { setMargins(dp(5), 0, 0, 0) })
            })
        }
        dialog.show()
    }

    /**
     * Edits markers by walking them along the recorded centreline.  This avoids having to
     * drag a thin GPS line precisely and makes the result deterministic even on a phone.
     */
    private fun showTrackEditor(track: SavedTrack) {
        if (running) {
            status.text = "Ferma la registrazione prima di modificare una pista"
            return
        }
        val points = track.lap.samples.map { TrackPoint(it.lat, it.lon) }
        if (points.size < 3) return
        var finishIndex = Geo.nearestRouteIndex(lineCenter(track.finishLine), points)
        val sectorIndexes = track.sectors.take(2).map { Geo.nearestRouteIndex(lineCenter(it), points) }.toMutableList()
        var selection = -1 // -1 finish; otherwise index in sectorIndexes
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(Color.rgb(3, 9, 14)) }
        lateinit var map: TrackMapView
        lateinit var selectedLabel: TextView
        lateinit var addSectorButton: Button
        lateinit var removeSectorButton: Button
        lateinit var markerButtons: LinearLayout

        fun orderedIndexes() = sectorIndexes.sortedBy { (it - finishIndex + points.size) % points.size }
        fun markerLine(index: Int): TimingLine {
            val before = points[(index - 1 + points.size) % points.size]
            val after = points[(index + 1) % points.size]
            return Geo.timingLine(points[index], Geo.headingDeg(before, after))
        }
        fun selectedText() = if (selection == -1) "TRAGUARDO" else "SETTORE ${selection + 1}"
        fun redraw() {
            val ordered = orderedIndexes()
            val selectedIndex = if (selection == -1) finishIndex else sectorIndexes[selection]
            map.setTrack(points)
            map.setTimingLine(markerLine(finishIndex))
            map.setSectors(ordered.map(::markerLine))
            // The red point is the centre of the selected timing line, making translation
            // obvious even in a tight bend where the line also changes its tangent angle.
            val selectedSample = track.lap.samples[selectedIndex]
            map.setFix(selectedSample)
            selectedLabel.text = "${selectedText()} · centro rosso · − / + spostano di 1 m lungo la traccia"
            addSectorButton.text = if (sectorIndexes.size >= 2) "LIMITE: 2 SETTORI" else "AGGIUNGI SETTORE (${sectorIndexes.size}/2)"
            addSectorButton.isEnabled = sectorIndexes.size < 2
            removeSectorButton.isEnabled = selection >= 0
            markerButtons.removeAllViews()
            fun markerButton(label: String, itemSelection: Int, accent: Int) = Button(this@MainActivity).apply {
                text = if (selection == itemSelection) "● $label" else label
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(accent)
                background = hudPanel(Color.rgb(7, 24, 33), accent, if (selection == itemSelection) 3 else 1)
                setOnClickListener { selection = itemSelection; redraw() }
            }
            markerButtons.addView(markerButton("TRAGUARDO", -1, Color.rgb(255, 224, 0)), LinearLayout.LayoutParams(-1, dp(40)))
            val sectorRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, 0) }
            sectorIndexes.indices.forEach { index ->
                val accent = when (index) {
                    0 -> Color.rgb(255, 159, 28)
                    1 -> Color.rgb(185, 131, 255)
                    else -> Color.rgb(245, 98, 200)
                }
                sectorRow.addView(markerButton("S${index + 1}", index, accent), LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (index > 0) leftMargin = dp(3) })
            }
            markerButtons.addView(sectorRow, LinearLayout.LayoutParams(-1, dp(43)))
        }
        fun shiftAlongTrack(index: Int, direction: Int, targetDistanceM: Double = 1.0): Int {
            var result = index
            var traveled = 0.0
            var guard = 0
            while (traveled < targetDistanceM && guard++ < points.size) {
                val next = (result + direction + points.size) % points.size
                traveled += Geo.distanceM(points[result].lat, points[result].lon, points[next].lat, points[next].lon)
                result = next
            }
            return result
        }
        fun shiftMarker(direction: Int) {
            if (selection == -1) finishIndex = shiftAlongTrack(finishIndex, direction)
            else sectorIndexes[selection] = shiftAlongTrack(sectorIndexes[selection], direction)
            redraw()
        }
        fun addSector() {
            if (sectorIndexes.size >= 2) {
                status.text = "Una pista può avere al massimo 2 settori"
                return
            }
            val markers = (sectorIndexes + finishIndex).sorted()
            if (markers.size == 1) {
                sectorIndexes += (finishIndex + points.size / 2) % points.size
                selection = sectorIndexes.lastIndex
                redraw()
                return
            }
            val gapStart = markers.indices.maxByOrNull { i ->
                val next = markers[(i + 1) % markers.size]
                (next - markers[i] + points.size) % points.size
            } ?: 0
            val start = markers[gapStart]
            val next = markers[(gapStart + 1) % markers.size]
            val gap = (next - start + points.size) % points.size
            sectorIndexes += (start + gap / 2) % points.size
            selection = sectorIndexes.lastIndex
            redraw()
        }
        map = TrackMapView(this) { tapped ->
            val index = Geo.nearestRouteIndex(tapped, points)
            if (selection == -1) finishIndex = index else sectorIndexes[selection] = index
            redraw()
        }.apply { setTrack(points); postDelayed({ fitEntireTrack() }, 400) }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(Color.rgb(7, 24, 33)) }
        info.addView(TextView(this).apply { text = track.name.uppercase(Locale.ITALIAN); textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
        info.addView(actionButton("CHIUDI") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(42), dp(38)).apply { topMargin = dp(6); gravity = Gravity.END })
        selectedLabel = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(184, 223, 235)); setPadding(0, dp(14), 0, dp(10)) }
        info.addView(selectedLabel)
        markerButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(markerButtons, LinearLayout.LayoutParams(-1, dp(83)))
        val moveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        moveRow.addView(actionButton("− 1 m") { shiftMarker(-1) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(3) })
        moveRow.addView(actionButton("+ 1 m") { shiftMarker(1) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(3) })
        info.addView(moveRow)
        addSectorButton = actionButton("AGGIUNGI SETTORE") { addSector() }
        info.addView(addSectorButton, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
        removeSectorButton = actionButton("RIMUOVI") {
            if (selection >= 0) { sectorIndexes.removeAt(selection); selection = -1; redraw() }
            else status.text = "Seleziona un settore da rimuovere"
        }
        info.addView(removeSectorButton, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
        info.addView(actionButton("SALVA MODIFICHE") {
            val saved = track.copy(finishLine = markerLine(finishIndex), sectors = orderedIndexes().map(::markerLine))
            trackStore.save(saved)
            if (activeSavedTrack?.id == saved.id) { activeSavedTrack = saved; configureSavedTrack(saved) }
            status.text = "${saved.name}: traguardo e settori aggiornati"
            speak("Pista aggiornata", flush = true)
            dialog.dismiss()
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(9) })
        info.addView(actionButton("ELIMINA PISTA") {
            AlertDialog.Builder(this).setTitle("Eliminare pista?").setMessage(track.name)
                .setNegativeButton("ANNULLA", null).setPositiveButton("ELIMINA") { _, _ ->
                    trackStore.delete(track); if (activeSavedTrack?.id == track.id) useAutomaticFinish(); dialog.dismiss()
                }.show()
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(5) })
        root.addView(map, LinearLayout.LayoutParams(0, -1, .62f).apply { rightMargin = dp(6) })
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(info) }, LinearLayout.LayoutParams(0, -1, .38f))
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply { setLayout(-1, -1); setBackgroundDrawable(ColorDrawable(Color.rgb(3, 9, 14))); decorView.systemUiVisibility = window.decorView.systemUiVisibility }
        redraw()
    }

    private fun lineCenter(line: TimingLine) = TrackPoint((line.pointA.lat + line.pointB.lat) / 2.0, (line.pointA.lon + line.pointB.lon) / 2.0)

    private fun showLiveTimingSizeMenu() {
        val choices = LiveTimingSize.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Dimensione live timing")
            .setSingleChoiceItems(choices, liveTimingSize.ordinal) { dialog, which ->
                liveTimingSize = LiveTimingSize.entries[which]
                preferences.edit().putInt("liveTimingSize", which).apply()
                dashboard.setLiveTimingScale(liveTimingSize.scale)
                status.text = "Live timing: ${liveTimingSize.label.lowercase(Locale.ITALIAN)}"
                dialog.dismiss()
            }
            .show()
    }

    private fun showScreenOrientationMenu() {
        val choices = ScreenMode.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Orientamento schermo")
            .setSingleChoiceItems(choices, screenMode.ordinal) { dialog, which ->
                if (running) {
                    status.text = "Ferma la registrazione prima di ruotare lo schermo"
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }
                screenMode = ScreenMode.entries[which]
                preferences.edit().putInt("screenMode", which).apply()
                requestedOrientation = screenMode.orientation
                dialog.dismiss()
            }
            .show()
    }

    private fun showVoiceBriefingMenu() {
        val choices = arrayOf(
            "Tutti · delta, intermedi e giro",
            "Solo intermedi e giro",
            "Solo giro"
        )
        val selected = when (voiceBriefingMode) {
            VoiceBriefingMode.ALL -> 0
            VoiceBriefingMode.SECTORS_AND_LAPS -> 1
            VoiceBriefingMode.LAPS_ONLY -> 2
        }
        AlertDialog.Builder(this)
            .setTitle("Avvisi di pista")
            .setSingleChoiceItems(choices, selected) { dialog, which ->
                voiceBriefingMode = when (which) {
                    1 -> VoiceBriefingMode.SECTORS_AND_LAPS
                    2 -> VoiceBriefingMode.LAPS_ONLY
                    else -> VoiceBriefingMode.ALL
                }
                status.text = "Avvisi: ${choices[which].substringBefore(" · ").lowercase()}"
                if (voiceEnabled) speak("Avvisi ${choices[which].substringBefore(" · ").lowercase()}", flush = true)
                dialog.dismiss()
            }
            .show()
    }

    private fun showVoiceFrequencyMenu() {
        val choices = arrayOf(
            "Frequente · almeno 5 secondi",
            "Equilibrata · almeno 10 secondi",
            "Prudente · almeno 20 secondi"
        )
        val selected = when (voiceAlertIntervalMs) {
            10_000L -> 1
            20_000L -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("Frequenza avvisi vocali")
            .setSingleChoiceItems(choices, selected) { dialog, which ->
                voiceAlertIntervalMs = when (which) {
                    1 -> 10_000L
                    2 -> 20_000L
                    else -> 5_000L
                }
                status.text = "Avvisi vocali: ${choices[which].substringBefore(" · ").lowercase()}"
                if (voiceEnabled) speak("Frequenza ${choices[which].substringBefore(" · ").lowercase()}", flush = true)
                dialog.dismiss()
            }
            .show()
    }

    private fun showEngineerVoiceMenu() {
        val available = tts?.voices
            ?.filter { it.locale.language.equals(Locale.ITALIAN.language, ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (available.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Voce dell'ingegnere")
                .setMessage("Il telefono non ha altre voci italiane disponibili. Puoi scaricarle da Impostazioni Android · Accessibilità · Sintesi vocale.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        fun label(index: Int, name: String): String {
            val lower = name.lowercase(Locale.ITALIAN)
            val style = when {
                "female" in lower || "femmin" in lower -> "femminile"
                "male" in lower || "masch" in lower -> "maschile"
                else -> "italiana ${index + 1}"
            }
            return "Voce $style"
        }
        val selected = available.indexOfFirst { it.name == preferredVoiceName }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Voce dell'ingegnere")
            .setSingleChoiceItems(available.mapIndexed { index, voice -> label(index, voice.name) }.toTypedArray(), selected) { dialog, which ->
                val voice = available[which]
                tts?.voice = voice
                preferredVoiceName = voice.name
                preferences.edit().putString("engineerVoice", voice.name).apply()
                status.text = "Voce dell'ingegnere aggiornata"
                speak("Questa è la voce dell'ingegnere", flush = true)
                dialog.dismiss()
            }
            .show()
    }

    private fun requestClose() {
        if (!running) {
            finishAndRemoveTask()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Chiudere Pit Engineer?")
            .setMessage("La sessione in corso verrà salvata prima di chiudere.")
            .setNegativeButton("ANNULLA", null)
            .setPositiveButton("SALVA E CHIUDI") { _, _ ->
                val simulated = simulator != null
                val gpxName = saveCurrentSession()
                saveSessionRecord(simulated, gpxName)?.let(::resolveSessionLocationName)
                stopSimulation()
                running = false
                finishAndRemoveTask()
            }
            .show()
    }

    private fun ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_REQUEST)
        }
    }

    private fun useAutomaticFinish() {
        activeSavedTrack = null
        timing.line = null
        timing.sectors = emptyList()
        sectorReferences = emptyList()
        autoFinish.reset()
        dashboard.setTimingLine(null)
        trackMap.setTimingLine(null)
        trackMap.setSectors(emptyList())
        status.text = "Modalità automatica · il primo giro determinerà il traguardo"
        speak("Modalità automatica", flush = true)
    }

    private fun loadTrack(points: List<TrackPoint>, name: String) {
        activeSavedTrack = null
        route = points
        timing.line = null
        resetSession(keepTrack = true)
        dashboard.setTrack(points)
        trackMap.setTrack(points)
        trackMap.setTimingLine(null)
        status.text = "$name caricato come traccia visiva"
    }

    private fun pickGpx() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, GPX_REQUEST)
    }

    @Deprecated("Kept dependency-free for this small native MVP")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GPX_REQUEST && resultCode == RESULT_OK) data?.data?.let { uri ->
            runCatching { contentResolver.openInputStream(uri)?.use { GpxReader.read(it) } ?: emptyList() }
                .onSuccess { points ->
                    if (points.size >= 2) loadTrack(points, "Traccia GPX")
                    else status.text = "Il file non contiene una traccia valida"
                }
                .onFailure { status.text = "Errore nella lettura del GPX" }
        }
    }

    private fun setTimingLineAtCurrentFix() {
        val fix = latestFix
        if (fix == null) {
            status.text = "Attendo un fix GPS: resta fermo qualche secondo"
            speak("Attendo il segnale GPS", flush = true)
            return
        }
        activeSavedTrack = null
        val center = TrackPoint(fix.lat, fix.lon)
        val heading = previousFix?.let { Geo.headingDeg(TrackPoint(it.lat, it.lon), center) }
            ?: headingFromRoute(center)
        val newLine = Geo.timingLine(center, heading)
        timing.reset()
        timing.line = newLine
        timing.sectors = emptyList()
        sectorReferences = emptyList()
        bestLap = null
        lastLapMs = null
        predictor = null
        liveDeltaMs = null
        recordedLaps.clear()
        currentSectorTimes.clear()
        bestSectorSegmentMs.clear()
        autoFinish.reset()
        dashboard.setTimingLine(timing.line)
        trackMap.setTimingLine(timing.line)
        trackMap.setSectors(emptyList())
        if (running) {
            timing.armAt(fix)
            status.text = "Traguardo spostato qui · nuovo giro avviato"
            speak("Traguardo spostato. Nuovo giro avviato", flush = true)
        } else {
            status.text = "Traguardo impostato · direzione ${heading.toInt()} gradi · premi AVVIA"
            speak("Traguardo impostato", flush = true)
        }
    }

    private fun setTimingLineAtMapPoint(tapped: TrackPoint) {
        activeSavedTrack = null
        val center = route.getOrNull(Geo.nearestRouteIndex(tapped, route)) ?: tapped
        val heading = headingFromRoute(center)
        timing.line = Geo.timingLine(center, heading)
        timing.sectors = emptyList()
        sectorReferences = emptyList()
        lastLapMs = null
        autoFinish.reset()
        dashboard.setTimingLine(timing.line)
        trackMap.setTimingLine(timing.line)
        trackMap.setSectors(emptyList())
        status.text = "Traguardo spostato sulla traccia · premi AVVIA"
        speak("Traguardo spostato", flush = true)
    }

    private fun handleTrackTap(tapped: TrackPoint) {
        // The live map is deliberately read-only: timing geometry belongs to the saved-track
        // editor, not to accidental taps while the phone is in a pocket or on a kart.
        status.text = if (running) "Mappa bloccata durante la registrazione" else "Modifica traguardo e settori da ☰ · Elenco piste"
    }

    private fun showMoveSectorMenu() {
        val fix = latestFix
        if (fix == null) {
            status.text = "Attendo un fix GPS"
            return
        }
        val canAdd = sectorReferences.size < 2
        val choices = mutableListOf<String>()
        if (canAdd) choices += "Aggiungi intermedio qui"
        sectorReferences.forEach { reference -> choices += "Sposta S${reference.number} qui" }
        AlertDialog.Builder(this)
            .setTitle("Intermedi · posizione GPS attuale")
            .setItems(choices.toTypedArray()) { _, which ->
                val point = TrackPoint(fix.lat, fix.lon)
                if (canAdd && which == 0) addSector(point)
                else moveSector(sectorReferences[which - if (canAdd) 1 else 0].number, point)
            }
            .show()
    }

    /** Adds a sector at the current position; it is safe to call while a lap is running. */
    private fun addSector(center: TrackPoint) {
        if (sectorReferences.size >= 2) {
            status.text = "Limite raggiunto: massimo 2 settori"
            return
        }
        val snappedCenter = snapToDrivenTrack(center)
        val line = Geo.timingLine(snappedCenter, headingFromDrivenTrack(snappedCenter))
        val referenceElapsed = bestLap?.let { elapsedAtLine(it.samples, line) }
            ?: timing.currentLapStartMs?.let { latestFix?.timeMs?.minus(it) }
            ?: 0L
        val number = sectorReferences.size + 1
        sectorReferences = sectorReferences + SectorReference(number, line, referenceElapsed)
        pendingSectorBaseline += number
        timing.sectors = sectorReferences.map { it.line }
        trackMap.setSectors(timing.sectors)
        status.text = "Sector S$number aggiunto${if (running) " · attivo da questo giro" else ""}"
        speak("Settore $number aggiunto", flush = true)
    }

    private fun moveSector(number: Int, center: TrackPoint) {
        val index = number - 1
        val old = sectorReferences.getOrNull(index) ?: return
        // Orient against the local circuit tangent, not the chord from the current GPS fix to a map tap.
        // The latter can make a valid crossing fail the forward-direction safety check.
        val snappedCenter = snapToDrivenTrack(center)
        val newLine = Geo.timingLine(snappedCenter, headingFromDrivenTrack(snappedCenter, old.line.allowedHeadingDeg))
        val referenceElapsed = bestLap?.let { elapsedAtLine(it.samples, newLine) } ?: old.referenceElapsedMs
        sectorReferences = sectorReferences.mapIndexed { i, value ->
            if (i == index) value.copy(line = newLine, referenceElapsedMs = referenceElapsed ?: value.referenceElapsedMs) else value
        }
        // The old split was measured on a different physical line. Rebuild every segment baseline
        // from the best lap on the new geometry, then clear the currently shown old split.
        bestLap?.let { seedBestSectorSegments(it) } ?: run {
            bestSectorSegmentMs.clear()
            pendingSectorBaseline += number
        }
        currentSectorTimes.clear()
        dashboard.clearSectorResult()
        timing.sectors = sectorReferences.map { it.line }
        trackMap.setSectors(timing.sectors)
        status.text = "Sector S$number spostato · tempi ricalibrati"
        speak("Settore $number spostato. Tempi ricalibrati", flush = true)
    }

    private fun elapsedAtLine(samples: List<GpsSample>, line: TimingLine): Long? {
        if (samples.size < 2) return null
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            val fraction = Geo.segmentIntersectionFraction(
                TrackPoint(previous.lat, previous.lon), TrackPoint(current.lat, current.lon), line.pointA, line.pointB
            ) ?: continue
            val heading = Geo.headingDeg(TrackPoint(previous.lat, previous.lon), TrackPoint(current.lat, current.lon))
            if (Geo.angleDifferenceDeg(heading, line.allowedHeadingDeg) > 75.0) continue
            val crossedAt = previous.timeMs + ((current.timeMs - previous.timeMs) * fraction).toLong()
            return crossedAt - samples.first().timeMs
        }
        return null
    }

    private fun headingFromRoute(point: TrackPoint): Double {
        if (route.size < 2) return 0.0
        val index = Geo.nearestRouteIndex(point, route)
        val before = route[(index - 1).coerceAtLeast(0)]
        val after = route[(index + 1).coerceAtMost(route.lastIndex)]
        return Geo.headingDeg(before, after)
    }

    /** The learned/best lap is the real circuit path while recording; a GPX is only a fallback. */
    private fun drivenTrack(): List<TrackPoint> = when {
        (bestLap?.samples?.size ?: 0) >= 3 -> bestLap!!.samples.map { TrackPoint(it.lat, it.lon) }
        liveRoute.size >= 3 -> liveRoute
        else -> route
    }

    private fun snapToDrivenTrack(point: TrackPoint): TrackPoint {
        val track = drivenTrack()
        return track.getOrNull(Geo.nearestRouteIndex(point, track)) ?: point
    }

    private fun headingFromDrivenTrack(point: TrackPoint, fallback: Double = 0.0): Double {
        val track = drivenTrack()
        if (track.size < 2) return fallback
        val index = Geo.nearestRouteIndex(point, track)
        val before = track[(index - 1).coerceAtLeast(0)]
        val after = track[(index + 1).coerceAtMost(track.lastIndex)]
        return Geo.headingDeg(before, after)
    }

    private fun toggleSession() {
        if (!running) {
            // Ask only at the moment AVVIA is pressed. A nearby saved profile is useful,
            // but the driver must always be able to record a new circuit/variant from scratch.
            val nearby = latestFix?.let { trackStore.nearby(TrackPoint(it.lat, it.lon)) }.orEmpty()
            if (nearby.isNotEmpty()) {
                showNearbyTrackStartChoice(nearby)
            } else {
                startSession(activeSavedTrack)
            }
        } else {
            finishAndAnalyzeSession()
        }
        dashboard.invalidate()
    }

    private fun startSession(track: SavedTrack?) {
        activeSavedTrack = track
        resetSession(keepTrack = track != null)
        running = true
        paused = false
        pauseButton?.text = "PAUSA"
        startButton?.text = "FERMA"
        startGps()
        if (timing.line == null) {
            status.text = "Registrazione attiva · primo giro in apprendimento"
            speak("Registrazione avviata. Primo giro in apprendimento", flush = true)
        } else {
            status.text = "${track?.name ?: "Pista"} caricata · attraversa il traguardo per armare il giro"
            speak("Registrazione avviata. Attraversa il traguardo per armare il giro", flush = true)
        }
        dashboard.invalidate()
    }

    /** Pause keeps all completed laps and sectors, while excluding a box stop from the active lap. */
    private fun togglePause() {
        if (!running) {
            status.text = "Avvia una registrazione prima di mettere in pausa"
            return
        }
        val timeMs = latestFix?.timeMs ?: System.currentTimeMillis()
        if (!paused) {
            pauseSession(timeMs, automatic = false)
        } else {
            paused = false
            timing.resumeAt(timeMs)
            pausedLapElapsedMs = null
            lowSpeedSinceMs = null
            if (simulator != null) simulationHandler.post(simulationTick)
            pauseButton?.text = "PAUSA"
            status.text = "Registrazione ripresa"
            speak("Registrazione ripresa", flush = true)
        }
        dashboard.invalidate()
    }

    private fun pauseSession(timeMs: Long, automatic: Boolean) {
        paused = true
        pausedLapElapsedMs = timing.currentLapStartMs?.let { timeMs - it }
        timing.pauseAt(timeMs)
        if (simulator != null) simulationHandler.removeCallbacks(simulationTick)
        pauseButton?.text = "RIPRENDI"
        status.text = if (automatic) "PAUSA AUTOMATICA · velocità bassa prolungata" else "PAUSA · dati sessione mantenuti"
        speak(if (automatic) "Pausa automatica. Velocità bassa prolungata" else "Pausa", flush = true)
    }

    private fun autoPauseIfNeeded(sample: GpsSample): Boolean {
        if (sample.speedMps < .8f) {
            val stoppedSince = lowSpeedSinceMs ?: sample.timeMs.also { lowSpeedSinceMs = it }
            if (sample.timeMs - stoppedSince >= 10 * 60 * 1_000L) {
                pauseSession(sample.timeMs, automatic = true)
                return true
            }
        } else {
            lowSpeedSinceMs = null
        }
        return false
    }

    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, this)
        }
    }

    private fun stopGps() = locationManager.removeUpdates(this)

    override fun onLocationChanged(location: Location) {
        if (simulator != null) return
        previousFix = latestFix
        processSample(GpsSample(
            location.latitude,
            location.longitude,
            location.speed,
            location.accuracy,
            location.time
        ))
    }

    private fun processSample(sample: GpsSample) {
        previousFix = latestFix
        latestFix = sample
        if (running && !paused) {
            liveRoute += TrackPoint(sample.lat, sample.lon)
            sessionSamples += sample
            if (liveRoute.size % 3 == 0) {
                trackMap.setTrack(liveRoute)
                if (bestLap == null) trackMap.fitEntireTrack()
            }
            if (autoPauseIfNeeded(sample)) {
                // Keep the current GPS point visible, but do not time the box interval.
            } else if (timing.line == null) {
                autoFinish.process(sample)?.let { discovery ->
                    timing.line = discovery.line
                    bestLap = discovery.lap
                    predictor = PredictiveDelta(discovery.lap.samples)
                    sectorReferences = deriveSectors(discovery.lap.samples)
                    seedBestSectorSegments(discovery.lap)
                    recordedLaps += RecordedLap(1, discovery.lap.durationMs, sectorReferences.map { it.referenceElapsedMs }, discovery.lap.samples)
                    timing.sectors = sectorReferences.map { it.line }
                    timing.armAt(sample, completedLapCount = 1)
                    dashboard.setTimingLine(discovery.line)
                    trackMap.setTimingLine(discovery.line)
                    trackMap.setSectors(timing.sectors)
                    trackMap.setTrack(liveRoute)
                    trackMap.fitEntireTrack()
                    status.text = "Linea rilevata automaticamente · il prossimo passaggio chiude il giro"
                    speak("Primo giro. ${spokenTime(discovery.lap.durationMs)}. Linea rilevata. Il prossimo passaggio chiude il giro", flush = true)
                }
            } else {
                timing.process(sample)?.let(::handleTimingEvent)
            }
            updatePredictiveDelta(sample)
        }
        updateStatus()
        dashboard.setLiveData(
            latestFix,
            if (paused) pausedLapElapsedMs else timing.currentLapStartMs?.let { latestFix!!.timeMs - it },
            liveDeltaMs,
            timing.currentLapNumber,
            lastLapMs,
            bestLap?.durationMs,
            bestLap?.number,
            running && !paused
        )
        trackMap.setFix(latestFix)
    }

    /** Explicit, high-contrast chooser used before starting: native setItems can be unreadable
     * in the HUD theme on some Pixel builds. */
    private fun showNearbyTrackStartChoice(nearby: List<SavedTrack>) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val scroll = ScrollView(this).apply { addView(list) }
        lateinit var dialog: AlertDialog
        nearby.forEach { track ->
            val use = Button(this).apply {
                text = "USA ${track.name.uppercase(Locale.ITALIAN)}\n${formatTime(track.lap.durationMs)} · ${track.sectors.size} SETTORI"
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                setTextColor(Color.WHITE)
                background = hudPanel(Color.rgb(7, 31, 42), Color.rgb(28, 190, 222), 2)
                setOnClickListener { dialog.dismiss(); startSession(track) }
            }
            list.addView(use, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(4) })
        }
        val fromScratch = Button(this).apply {
            text = "REGISTRA DA ZERO"
            textSize = 13f
            setTextColor(Color.rgb(255, 185, 64))
            background = hudPanel(Color.rgb(33, 26, 13), Color.rgb(255, 185, 64), 1)
            setOnClickListener { dialog.dismiss(); startSession(null) }
        }
        list.addView(fromScratch, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        dialog = AlertDialog.Builder(this)
            .setTitle("Piste nelle vicinanze")
            .setMessage("Scegli una pista come base per traguardo e settori, oppure registra una nuova variante.")
            .setView(scroll)
            .setNegativeButton("ANNULLA", null)
            .create()
        dialog.show()
    }

    private fun activateSavedTrack(track: SavedTrack) {
        activeSavedTrack = track
        configureSavedTrack(track)
        status.text = "${track.name} caricata · traguardo e ${track.sectors.size} settori pronti"
        speak("${track.name} caricata. Traguardo e settori pronti", flush = true)
    }

    private fun configureSavedTrack(track: SavedTrack) {
        val referenceLap = Lap(track.lap.number, track.lap.durationMs, track.lap.samples)
        route = track.lap.samples.map { TrackPoint(it.lat, it.lon) }
        bestLap = referenceLap
        predictor = PredictiveDelta(referenceLap.samples)
        timing.line = track.finishLine
        timing.sectors = track.sectors.take(2)
        sectorReferences = timing.sectors.mapIndexed { index, line ->
            SectorReference(index + 1, line, elapsedAtLine(referenceLap.samples, line) ?: 0L)
        }
        seedBestSectorSegments(referenceLap)
        dashboard.setTrack(route)
        dashboard.setTimingLine(track.finishLine)
        trackMap.setTrack(route)
        trackMap.setTimingLine(track.finishLine)
        trackMap.setSectors(track.sectors)
        trackMap.fitEntireTrack()
    }

    private fun toggleSimulation() {
        if (simulator != null) {
            status.text = "Test già attivo · premi FERMA per interromperlo"
            return
        }
        val fix = latestFix
        if (fix == null) {
            status.text = "Attendo il GPS reale per centrare il test"
            speak("Attendo il segnale GPS", flush = true)
            return
        }
        resetSession()
        simulator = DebugGpsSimulator(TrackPoint(fix.lat, fix.lon), System.currentTimeMillis())
        running = true
        paused = false
        pauseButton?.text = "PAUSA"
        startButton?.text = "FERMA"
        testButton?.text = "TEST ATTIVO"
        status.text = "TEST GPS · giro simulato in apprendimento"
        speak("Test GPS avviato. Primo giro simulato in apprendimento", flush = true)
        simulationHandler.post(simulationTick)
    }

    private fun stopSimulation() {
        simulator = null
        simulationHandler.removeCallbacks(simulationTick)
        testButton?.text = "TEST GPS"
    }

    private fun handleTimingEvent(event: TimingEvent) {
        when (event) {
            is TimingEvent.Armed -> {
                liveDeltaMs = null
                lastDeltaAnnouncementElapsedMs = Long.MIN_VALUE
                lastAnnouncedDeltaMs = null
                previousLiveDeltaMs = null
                sectorVoiceAnnouncementCount = 0
                currentSectorTimes.clear()
                status.text = "Giro armato · BEST LAP non ancora disponibile"
                speak("Cronometro armato", flush = true)
            }
            is TimingEvent.SectorCompleted -> {
                val previousElapsed = currentSectorTimes[event.number - 1] ?: 0L
                val segmentMs = (event.elapsedMs - previousElapsed).coerceAtLeast(0L)
                val previousBestSegment = bestSectorSegmentMs[event.number]
                val isSegmentRecord = previousBestSegment != null && segmentMs < previousBestSegment
                if (isSegmentRecord) bestSectorSegmentMs[event.number] = segmentMs
                if (previousBestSegment == null) {
                    bestSectorSegmentMs[event.number] = segmentMs
                    pendingSectorBaseline -= event.number
                }
                currentSectorTimes[event.number] = event.elapsedMs
                val reference = sectorReferences.getOrNull(event.number - 1)?.referenceElapsedMs
                val delta = reference?.let { event.elapsedMs - it }
                liveDeltaMs = delta
                lastDeltaAnnouncementElapsedMs = event.elapsedMs
                lastAnnouncedDeltaMs = delta
                previousLiveDeltaMs = delta
                dashboard.setSectorResult(event.number, event.elapsedMs, delta)
                if (voiceBriefingMode != VoiceBriefingMode.LAPS_ONLY) {
                    sectorVoiceAnnouncementCount++
                    val sectorCall = if (isSegmentRecord) "Fucsia. ${spokenSectorName(event.number)}" else spokenSectorName(event.number)
                    val deltaPart = delta?.let {
                        ", ${spokenSectorDelta(it)}"
                    } ?: ""
                    // Do not read the sector time: the useful radio information here is its delta.
                    speak("$sectorCall$deltaPart", flush = true, silenceAfterMs = 5_000L)
                }
                status.text = "Sector ${event.number}: ${formatTime(event.elapsedMs)}${delta?.let { " · ${formatDelta(it)}" } ?: ""}"
            }
            is TimingEvent.LapCompleted -> {
                lastLapMs = event.lap.durationMs
                val finalSegmentNumber = sectorReferences.size + 1
                val finalSegmentStart = currentSectorTimes[sectorReferences.size] ?: 0L
                val finalSegmentMs = (event.lap.durationMs - finalSegmentStart).coerceAtLeast(0L)
                val previousFinalBest = bestSectorSegmentMs[finalSegmentNumber]
                val isFinalSegmentRecord = sectorReferences.isNotEmpty() && previousFinalBest != null && finalSegmentMs < previousFinalBest
                if (isFinalSegmentRecord) bestSectorSegmentMs[finalSegmentNumber] = finalSegmentMs
                if (previousFinalBest == null && sectorReferences.isNotEmpty()) bestSectorSegmentMs[finalSegmentNumber] = finalSegmentMs
                recordedLaps += RecordedLap(event.lap.number, event.lap.durationMs, currentSectorTimes.toSortedMap().values.toList(), event.lap.samples)
                currentSectorTimes.clear()
                // With a manually positioned finish line, make the first completed lap
                // establish the same two default sectors as automatic discovery.
                val defaultSectorsAdded = if (sectorReferences.isEmpty()) {
                    sectorReferences = deriveSectors(event.lap.samples)
                    timing.sectors = sectorReferences.map { it.line }
                    trackMap.setSectors(timing.sectors)
                    seedBestSectorSegments(event.lap)
                    sectorReferences.size == 2
                } else false
                val oldBest = bestLap
                val isBest = oldBest == null || event.lap.durationMs < oldBest.durationMs
                if (isBest) {
                    bestLap = event.lap
                    predictor = PredictiveDelta(event.lap.samples)
                    updateSectorReferencesForBestLap(event.lap)
                    liveDeltaMs = null
                    lastBestReminderLap = event.lap.number
                }
                val message = buildString {
                    append("Giro ${event.lap.number}. ${spokenTime(event.lap.durationMs)}.")
                    oldBest?.let {
                        // Announce the gap to the best time on every completed lap, including a new best.
                        append(" ${spokenDelta(event.lap.durationMs - it.durationMs)} sul tempo migliore.")
                    } ?: append(" Tempo migliore impostato.")
                    if (isBest) append(" Miglior giro.")
                    else oldBest?.let {
                        if (event.lap.number - lastBestReminderLap >= 3) {
                            append(" Tempo migliore ${spokenTime(it.durationMs)}.")
                            lastBestReminderLap = event.lap.number
                        }
                    }
                    if (isFinalSegmentRecord) append(" ${finalSectorRecordMessage(finalSegmentMs)}.")
                }
                speak(message, flush = true, silenceAfterMs = 5_000L)
                val sectorNotice = if (defaultSectorsAdded) " · S1 e S2 automatici pronti" else ""
                status.text = if (isBest) "Giro ${event.lap.number}: miglior giro ${formatTime(event.lap.durationMs)}$sectorNotice" else "Giro ${event.lap.number}: ${formatTime(event.lap.durationMs)}$sectorNotice"
                predictor?.reset()
                dashboard.clearSectorResult()
                lastDeltaAnnouncementElapsedMs = Long.MIN_VALUE
                lastAnnouncedDeltaMs = null
                previousLiveDeltaMs = null
                // The five-second suppression is armed by the TTS completion callback,
                // so it starts after the complete lap message has been spoken.
            }
        }
    }

    private fun updatePredictiveDelta(sample: GpsSample) {
        val start = timing.currentLapStartMs ?: return
        val elapsed = sample.timeMs - start
        liveDeltaMs = predictor?.calculate(sample, start)
        val closeToSector = sectorReferences.any { reference ->
            reference.number !in currentSectorTimes && abs(elapsed - reference.referenceElapsedMs) < 3_000
        }
        val current = liveDeltaMs ?: return
        val lastReported = lastAnnouncedDeltaMs
        val previous = previousLiveDeltaMs
        // Il delta Ã¨ utile solo quando cambia davvero: nessun messaggio ripetuto sullo stesso valore.
        val elapsedSinceMessage = if (lastDeltaAnnouncementElapsedMs == Long.MIN_VALUE) Long.MAX_VALUE else elapsed - lastDeltaAnnouncementElapsedMs
        val improvementCooldownPassed = elapsedSinceMessage >= voiceAlertIntervalMs
        val lossCooldownPassed = elapsedSinceMessage >= voiceAlertIntervalMs * 2
        // Un centesimo Ã¨ giÃ  un progresso utile; il filtro evita solo le oscillazioni identiche.
        val improvingDelta = improvementCooldownPassed && (
            (lastReported == null && current <= -10) ||
                (lastReported != null && current <= lastReported - 10)
            )
        val suddenLoss = current >= 350 && lossCooldownPassed && (
            (lastReported != null && current >= lastReported + 350) || (previous != null && current >= previous + 400)
        )
        val deltaCallAllowed = sample.timeMs >= deltaAnnouncementsSuppressedUntilMs
        if (voiceBriefingMode == VoiceBriefingMode.ALL && deltaCallAllowed && !closeToSector && (improvingDelta || suddenLoss)) {
            lastDeltaAnnouncementElapsedMs = elapsed
            val message = if (improvingDelta && current >= 0 && lastReported != null) {
                recoveryDeltaMessage(current, lastReported, elapsed)
            } else {
                engineerDeltaMessage(current, elapsed)
            }
            speak(message, flush = false)
            lastAnnouncedDeltaMs = current
        }
        previousLiveDeltaMs = current
    }

    private fun updateStatus() {
        if (screenLockOverlay != null) {
            status.text = "🔒 SCHERMO BLOCCATO · premi un tasto volume per sbloccare"
            return
        }
        val fix = latestFix ?: return
        val lineText = if (timing.line == null) if (running) "ricerca giro" else "automatico" else "traguardo pronto"
        val runText = when {
            paused -> "PAUSA"
            simulator != null -> "TEST"
            running -> "REC"
            else -> "pronto"
        }
        status.text = "$runText · GPS ±${fix.accuracyM.toInt()} m · ${(fix.speedMps * 3.6f).format1()} km/h · $lineText"
    }

    /** Places S1/S2 at one-third and two-thirds of the recorded first-lap distance. */
    private fun deriveSectors(samples: List<GpsSample>): List<SectorReference> {
        if (samples.size < 6) return emptyList()
        val cumulative = DoubleArray(samples.size)
        for (index in 1 until samples.size) {
            cumulative[index] = cumulative[index - 1] + Geo.distanceM(
                samples[index - 1].lat, samples[index - 1].lon, samples[index].lat, samples[index].lon
            )
        }
        val total = cumulative.last()
        if (total < 60.0) return emptyList()
        return listOf(1, 2).mapNotNull { number ->
            val index = cumulative.indices.minByOrNull { kotlin.math.abs(cumulative[it] - total * number / 3.0) } ?: return@mapNotNull null
            val point = samples[index]
            val before = samples[(index - 1).coerceAtLeast(0)]
            val after = samples[(index + 1).coerceAtMost(samples.lastIndex)]
            SectorReference(
                number,
                Geo.timingLine(TrackPoint(point.lat, point.lon), Geo.headingDeg(TrackPoint(before.lat, before.lon), TrackPoint(after.lat, after.lon))),
                point.timeMs - samples.first().timeMs
            )
        }
    }

    /** Sector reference times always follow the same best lap used by the live delta. */
    private fun updateSectorReferencesForBestLap(lap: Lap) {
        sectorReferences = sectorReferences.map { reference ->
            reference.copy(referenceElapsedMs = elapsedAtLine(lap.samples, reference.line) ?: reference.referenceElapsedMs)
        }
    }

    private fun seedBestSectorSegments(lap: Lap) {
        bestSectorSegmentMs.clear()
        pendingSectorBaseline.clear()
        var previousElapsed = 0L
        sectorReferences.forEachIndexed { index, reference ->
            bestSectorSegmentMs[index + 1] = (reference.referenceElapsedMs - previousElapsed).coerceAtLeast(0L)
            previousElapsed = reference.referenceElapsedMs
        }
        if (sectorReferences.isNotEmpty()) {
            bestSectorSegmentMs[sectorReferences.size + 1] = (lap.durationMs - previousElapsed).coerceAtLeast(0L)
        }
    }

    private fun sectorRecordMessage(number: Int, segmentMs: Long) = when (number) {
        1 -> "Tempo migliore settore uno. ${spokenTime(segmentMs)}"
        2 -> "Tempo migliore nel tratto tra settore uno e due. ${spokenTime(segmentMs)}"
        else -> "Tempo migliore del settore $number. ${spokenTime(segmentMs)}"
    }

    private fun finalSectorRecordMessage(segmentMs: Long) = "Fucsia. Tempo migliore ultimo settore. ${spokenTime(segmentMs)}"

    private fun saveCurrentSession(): String? {
        if (simulator != null || sessionSamples.size < 2 || recordedLaps.none { it.valid }) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "crono_$stamp.gpx"
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Crono")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                    writer.appendLine("<gpx version=\"1.1\" creator=\"Crono\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>Crono $stamp</name><trkseg>")
                    sessionSamples.forEach { sample ->
                        writer.appendLine("<trkpt lat=\"${sample.lat}\" lon=\"${sample.lon}\"><time>${java.time.Instant.ofEpochMilli(sample.timeMs)}</time><extensions><speed>${sample.speedMps}</speed></extensions></trkpt>")
                    }
                    writer.appendLine("</trkseg></trk></gpx>")
                }
            } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            name
        }.getOrNull()
    }

    private fun finishAndAnalyzeSession() {
        val wasSimulated = simulator != null
        val gpxName = saveCurrentSession()
        val saved = saveSessionRecord(wasSimulated, gpxName)
        stopSimulation()
        running = false
        paused = false
        pausedLapElapsedMs = null
        lowSpeedSinceMs = null
        startButton?.text = "AVVIA"
        pauseButton?.text = "PAUSA"
        status.text = if (saved != null) "Sessione salvata · analisi disponibile" else "Registrazione fermata · nessun giro valido, sessione non salvata"
        speak(if (saved != null) "Registrazione fermata. Sessione salvata" else "Registrazione fermata. Nessun giro valido, sessione non salvata", flush = true)
        saved?.let(::resolveSessionLocationName)
        saved?.let(::showSessionAnalysis)
    }

    private fun saveSessionRecord(simulated: Boolean, gpxName: String?): SavedSession? {
        val first = sessionSamples.firstOrNull() ?: return null
        val last = sessionSamples.lastOrNull() ?: return null
        if (sessionSamples.size < 2 || recordedLaps.none { it.valid }) return null
        return sessionStore.save(
            SavedSession(
                id = "session_${first.timeMs}",
                displayName = if (simulated) "Simulazione GPS" else "Sessione GPS ${SimpleDateFormat("dd MMM HH:mm", Locale.ITALIAN).format(Date(first.timeMs))}",
                startedAtMs = first.timeMs,
                durationMs = (last.timeMs - first.timeMs).coerceAtLeast(0),
                simulated = simulated,
                gpxName = gpxName,
                laps = recordedLaps.toList(),
                maxSpeedMps = sessionSamples.maxOfOrNull { it.speedMps },
                minSpeedMps = sessionSamples.minOfOrNull { it.speedMps },
                timingLine = timing.line,
                sectorLines = timing.sectors.take(2),
                samples = sessionSamples.toList()
            )
        )
    }

    private fun resolveSessionLocationName(session: SavedSession) {
        if (session.simulated) return
        val sample = sessionSamples.firstOrNull() ?: return
        Thread {
            val locality = runCatching {
                if (!Geocoder.isPresent()) null else {
                    @Suppress("DEPRECATION")
                    Geocoder(this@MainActivity, Locale.ITALIAN).getFromLocation(sample.lat, sample.lon, 1)
                        ?.firstOrNull()
                        ?.let { address -> address.locality ?: address.subAdminArea ?: address.adminArea }
                }
            }.getOrNull()
            if (!locality.isNullOrBlank()) {
                val date = SimpleDateFormat("dd MMM HH:mm", Locale.ITALIAN).format(Date(session.startedAtMs))
                sessionStore.save(session.copy(displayName = "$locality · $date"))
            }
        }.start()
    }

    private fun showSessionHistory() {
        val sessions = sessionStore.list()
        if (sessions.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Storico sessioni").setMessage("Nessuna sessione salvata").setPositiveButton("OK", null).show()
            return
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val scroll = ScrollView(this).apply { addView(list) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Storico sessioni")
            .setView(scroll)
            .setNegativeButton("×", null)
            .create()
        sessions.forEach { session ->
            val validLaps = session.laps.filter { it.valid }
            val best = validLaps.minOfOrNull { it.durationMs }?.let(::formatTime) ?: "nessun giro valido"
            val title = TextView(this).apply {
                text = "${session.displayName}\n${validLaps.size}/${session.laps.size} giri validi · best $best"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(6), dp(4))
                background = hudPanel(Color.rgb(7, 24, 33), Color.rgb(28, 148, 190), 1)
                setOnClickListener { dialog.dismiss(); showSessionAnalysis(session) }
            }
            val delete = Button(this).apply {
                text = "🗑"
                textSize = 18f
                contentDescription = "Elimina ${session.displayName}"
                setTextColor(Color.rgb(255, 112, 112))
                background = hudPanel(Color.rgb(37, 16, 22), Color.rgb(255, 91, 100), 1)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Eliminare sessione?")
                        .setMessage(session.displayName)
                        .setNegativeButton("ANNULLA", null)
                        .setPositiveButton("ELIMINA") { _, _ ->
                            sessionStore.delete(session)
                            dialog.dismiss()
                            showSessionHistory()
                        }
                        .show()
                }
            }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
                addView(title, LinearLayout.LayoutParams(0, dp(54), 1f))
                addView(delete, LinearLayout.LayoutParams(dp(52), dp(54)).apply { setMargins(dp(5), 0, 0, 0) })
            })
        }
        dialog.show()
    }

    /** Speed traces normalized by distance, so laps with different durations can be compared
     * corner by corner just like a race-engineering overlay. */
    private inner class SpeedComparisonView(lap: RecordedLap, bestLap: RecordedLap) : View(this@MainActivity) {
        private val lapProfile = speedProfile(lap)
        private val bestProfile = speedProfile(bestLap)
        private val lapElapsedProfile = elapsedProfile(lap)
        private val bestElapsedProfile = elapsedProfile(bestLap)
        private val deltaProfile = lapElapsedProfile.indices.map { index ->
            lapElapsedProfile[index] - (bestElapsedProfile.getOrNull(index) ?: 0L)
        }
        private val maxSpeed = maxOf(lapProfile.maxOrNull() ?: 1f, bestProfile.maxOrNull() ?: 1f, 10f)
        private val maxDelta = maxOf(deltaProfile.maxOfOrNull { abs(it) } ?: 100L, 100L)
        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(232, 3, 8, 13) }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(180, 194, 205); style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(55, 72, 82); strokeWidth = dp(1).toFloat() }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(10).toFloat(); typeface = Typeface.DEFAULT_BOLD }
        private val bestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 61, 72); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val lapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 232, 31); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }

        override fun onDraw(canvas: Canvas) {
            val expanded = height > dp(280)
            val inset = dp(5).toFloat()
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(5).toFloat(), dp(5).toFloat(), panelPaint)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(5).toFloat(), dp(5).toFloat(), borderPaint)
            val chartLeft = dp(if (expanded) 34 else 12).toFloat()
            val chartRight = width - dp(if (expanded) 28 else 8).toFloat()
            val chartTop = dp(if (expanded) 82 else 29).toFloat()
            val chartBottom = if (expanded) height * .48f else height - dp(17).toFloat()
            (0..3).forEach { index ->
                val y = chartTop + (chartBottom - chartTop) * index / 3f
                canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
            }
            titlePaint.textSize = dp(if (expanded) 20 else 10).toFloat()
            canvas.drawText("VELOCITÀ / DISTANZA", inset + dp(5), dp(if (expanded) 30 else 14).toFloat(), titlePaint)
            titlePaint.color = Color.WHITE
            titlePaint.textSize = dp(if (expanded) 14 else 8).toFloat()
            canvas.drawText("0%", chartLeft, chartBottom + dp(if (expanded) 18 else 12), titlePaint)
            titlePaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("100%", chartRight, chartBottom + dp(if (expanded) 18 else 12), titlePaint)
            titlePaint.textAlign = Paint.Align.LEFT
            drawProfile(canvas, bestProfile, chartLeft, chartRight, chartTop, chartBottom, bestPaint)
            drawProfile(canvas, lapProfile, chartLeft, chartRight, chartTop, chartBottom, lapPaint)
            if (expanded) drawDeltaChart(canvas, chartLeft, chartRight)
            drawLegend(canvas, expanded)
        }

        private fun drawDeltaChart(canvas: Canvas, left: Float, right: Float) {
            val top = height * .62f
            val bottom = height - dp(54).toFloat()
            val middle = (top + bottom) / 2f
            (0..4).forEach { index ->
                val y = top + (bottom - top) * index / 4f
                canvas.drawLine(left, y, right, y, gridPaint)
            }
            val zeroPaint = Paint(gridPaint).apply { color = Color.rgb(220, 230, 235); strokeWidth = dp(1).toFloat() }
            canvas.drawLine(left, middle, right, middle, zeroPaint)
            titlePaint.color = Color.WHITE; titlePaint.textSize = dp(20).toFloat(); titlePaint.textAlign = Paint.Align.LEFT
            canvas.drawText("DELTA VS BEST / DISTANZA", dp(10).toFloat(), top - dp(14), titlePaint)
            titlePaint.textSize = dp(12).toFloat(); titlePaint.color = Color.rgb(0, 232, 31)
            canvas.drawText("GUADAGNO", dp(10).toFloat(), top + dp(15), titlePaint)
            titlePaint.color = Color.rgb(255, 61, 72)
            canvas.drawText("PERDITA", dp(10).toFloat(), bottom - dp(8), titlePaint)
            if (deltaProfile.size < 2) return
            deltaProfile.indices.drop(1).forEach { index ->
                val x1 = left + (right - left) * (index - 1) / (deltaProfile.size - 1).toFloat()
                val x2 = left + (right - left) * index / (deltaProfile.size - 1).toFloat()
                val y1 = middle + (bottom - top) * .42f * (deltaProfile[index - 1].toFloat() / maxDelta).coerceIn(-1f, 1f)
                val y2 = middle + (bottom - top) * .42f * (deltaProfile[index].toFloat() / maxDelta).coerceIn(-1f, 1f)
                val color = if ((deltaProfile[index - 1] + deltaProfile[index]) <= 0L) Color.rgb(0, 232, 31) else Color.rgb(255, 61, 72)
                canvas.drawLine(x1, y1, x2, y2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = dp(3).toFloat() })
            }
        }

        private fun drawLegend(canvas: Canvas, expanded: Boolean) {
            val y = height - dp(if (expanded) 20 else 5).toFloat()
            titlePaint.textSize = dp(if (expanded) 15 else 8).toFloat(); titlePaint.textAlign = Paint.Align.LEFT
            titlePaint.color = lapPaint.color; canvas.drawText("— GIRO ANALIZZATO", dp(12).toFloat(), y, titlePaint)
            titlePaint.color = bestPaint.color; canvas.drawText("— BEST LAP", width * .43f, y, titlePaint)
            if (expanded) {
                titlePaint.color = Color.LTGRAY
                canvas.drawText("Linea bianca = zero delta", width * .70f, y, titlePaint)
            }
            titlePaint.color = Color.WHITE
        }

        private fun drawProfile(canvas: Canvas, profile: List<Float>, left: Float, right: Float, top: Float, bottom: Float, paint: Paint) {
            if (profile.size < 2) return
            val path = Path()
            profile.forEachIndexed { index, speed ->
                val x = left + (right - left) * index / (profile.size - 1).toFloat()
                val y = bottom - (bottom - top) * (speed / maxSpeed).coerceIn(0f, 1f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }

        private fun speedProfile(lap: RecordedLap, samples: Int = 72): List<Float> {
            val source = lap.samples
            if (source.size < 2) return emptyList()
            val distance = DoubleArray(source.size)
            for (index in 1 until source.size) {
                distance[index] = distance[index - 1] + Geo.distanceM(source[index - 1].lat, source[index - 1].lon, source[index].lat, source[index].lon)
            }
            val total = distance.last().takeIf { it > 0.5 } ?: return source.map { it.speedMps * 3.6f }
            var segment = 1
            return List(samples) { step ->
                val target = total * step / (samples - 1).toDouble()
                while (segment < distance.lastIndex && distance[segment] < target) segment++
                val previous = (segment - 1).coerceAtLeast(0)
                val length = (distance[segment] - distance[previous]).takeIf { it > .01 } ?: 1.0
                val fraction = ((target - distance[previous]) / length).coerceIn(0.0, 1.0)
                ((source[previous].speedMps.toDouble() + (source[segment].speedMps - source[previous].speedMps).toDouble() * fraction) * 3.6).toFloat()
            }
        }

        /** Elapsed time sampled at equal distance intervals, used for the lower delta trace. */
        private fun elapsedProfile(lap: RecordedLap, samples: Int = 72): List<Long> {
            val source = lap.samples
            if (source.size < 2) return emptyList()
            val distance = DoubleArray(source.size)
            for (index in 1 until source.size) {
                distance[index] = distance[index - 1] + Geo.distanceM(source[index - 1].lat, source[index - 1].lon, source[index].lat, source[index].lon)
            }
            val total = distance.last().takeIf { it > .5 } ?: return emptyList()
            var segment = 1
            return List(samples) { step ->
                val target = total * step / (samples - 1).toDouble()
                while (segment < distance.lastIndex && distance[segment] < target) segment++
                val previous = (segment - 1).coerceAtLeast(0)
                val length = (distance[segment] - distance[previous]).takeIf { it > .01 } ?: 1.0
                val fraction = ((target - distance[previous]) / length).coerceIn(0.0, 1.0)
                (source[previous].timeMs.toDouble() + (source[segment].timeMs - source[previous].timeMs).toDouble() * fraction).toLong() - source.first().timeMs
            }
        }
    }

    /** GPS-only telemetry comparison on a one-metre distance grid.
     * A is the analysed lap; B is the best lap. Positive delta means A gains time. */
    private inner class DistanceDeltaComparisonView(
        private val analysed: RecordedLap,
        private val best: RecordedLap,
        sectorLines: List<TimingLine>
    ) : View(this@MainActivity) {
        private val comparison = buildComparison(analysed, best, sectorLines)
        private var windowMeters = 10
        private var cursor = -1
        private var chartLeft = 0f
        private var chartRight = 1f
        private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(3, 9, 14) }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(61, 112, 131); style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 58, 70); strokeWidth = dp(1).toFloat() }
        private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(224, 238, 244); textSize = dp(13).toFloat(); typeface = Typeface.DEFAULT_BOLD }
        private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 232, 31); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 61, 72); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val cyan = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 151, 255); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }

        fun setLocalWindowMeters(meters: Int) { windowMeters = meters; invalidate() }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(3, 9, 14))
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(6).toFloat(), dp(6).toFloat(), panel)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(6).toFloat(), dp(6).toFloat(), border)
            chartLeft = dp(42).toFloat(); chartRight = width - dp(20).toFloat()
            val topSpeed = dp(90).toFloat(); val bottomSpeed = height * .31f
            val topCumulative = height * .40f; val bottomCumulative = height * .59f
            val topLocal = height * .69f; val bottomLocal = height - dp(58).toFloat()
            drawSimpleExplanation(canvas)
            drawTitle(canvas, "VELOCITÀ / DISTANZA", dp(14).toFloat())
            drawSpeedChart(canvas, topSpeed, bottomSpeed)
            drawTitle(canvas, "DELTA TEMPO CUMULATIVO / DISTANZA", topCumulative - dp(13))
            drawCumulativeChart(canvas, topCumulative, bottomCumulative)
            drawTitle(canvas, "GUADAGNO / PERDITA LOCALE — FINESTRA ${windowMeters} m", topLocal - dp(13))
            drawLocalChart(canvas, topLocal, bottomLocal)
            drawLegend(canvas)
            drawCursor(canvas, topSpeed, bottomLocal)
        }

        /** A human explanation of the same plotted facts: no invented throttle/brake data. */
        private fun drawSimpleExplanation(canvas: Canvas) {
            val bins = localBins()
            val bestZone = bins.maxByOrNull { it.deltaMs }
            val worstZone = bins.minByOrNull { it.deltaMs }
            val finalMs = best.durationMs - analysed.durationMs
            val headline = when {
                finalMs > 120 -> "LETTURA SEMPLICE: questo giro è ${"%.2f".format(Locale.US, finalMs / 1_000.0)} s più veloce del riferimento."
                finalMs < -120 -> "LETTURA SEMPLICE: questo giro perde ${"%.2f".format(Locale.US, -finalMs / 1_000.0)} s dal riferimento."
                else -> "LETTURA SEMPLICE: questo giro è praticamente sul ritmo del riferimento."
            }
            fun zoneText(bin: LocalDeltaBin?, positive: Boolean): String {
                if (bin == null) return ""
                val middle = ((bin.start + bin.end) / 2).coerceIn(0, comparison.speedA.lastIndex)
                val speedGap = comparison.speedA[middle] - comparison.speedB[middle]
                val cause = when {
                    speedGap <= -3f -> "qui A è ${-speedGap.toInt()} km/h più lento: controlla frenata, percorrenza o uscita."
                    speedGap >= 3f -> "qui A è ${speedGap.toInt()} km/h più veloce: è un tratto da replicare."
                    positive -> "il guadagno non è solo velocità di punta: probabilmente linea e continuità sono migliori."
                    else -> "la perdita non è solo velocità di punta: cerca un errore di linea o di timing in curva."
                }
                return "${if (positive) "Miglior" else "Peggior"} tratto: m ${bin.start}-${bin.end}, ${"%+.0f".format(Locale.US, bin.deltaMs)} ms; $cause"
            }
            val detail = when {
                finalMs < -120 -> zoneText(worstZone, positive = false)
                finalMs > 120 -> zoneText(bestZone, positive = true)
                else -> zoneText(worstZone, positive = false)
            }
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 28, 38) }
            canvas.drawRoundRect(dp(8).toFloat(), dp(23).toFloat(), width - dp(58).toFloat(), dp(78).toFloat(), dp(4).toFloat(), dp(4).toFloat(), box)
            white.textAlign = Paint.Align.LEFT; white.textSize = dp(12).toFloat(); white.color = Color.rgb(210, 237, 246)
            canvas.drawText(headline, dp(15).toFloat(), dp(43).toFloat(), white)
            white.textSize = dp(11).toFloat(); white.color = Color.rgb(170, 212, 227)
            canvas.drawText(detail.take(120), dp(15).toFloat(), dp(63).toFloat(), white)
        }

        private fun drawTitle(canvas: Canvas, title: String, y: Float) {
            white.textAlign = Paint.Align.LEFT; white.textSize = dp(15).toFloat(); white.color = Color.WHITE
            canvas.drawText(title, dp(12).toFloat(), y, white)
        }

        private fun drawGrid(canvas: Canvas, top: Float, bottom: Float, zero: Float? = null) {
            (0..3).forEach { i ->
                val y = top + (bottom - top) * i / 3f
                canvas.drawLine(chartLeft, y, chartRight, y, grid)
            }
            comparison.sectorFractions.forEachIndexed { index, fraction ->
                val x = chartLeft + (chartRight - chartLeft) * fraction
                canvas.drawLine(x, top, x, bottom, Paint(grid).apply { color = Color.rgb(156, 111, 255); strokeWidth = dp(1).toFloat() })
                white.textSize = dp(10).toFloat(); white.color = Color.rgb(185, 131, 255); white.textAlign = Paint.Align.CENTER
                canvas.drawText("S${index + 1}", x, top + dp(12), white)
            }
            zero?.let { canvas.drawLine(chartLeft, it, chartRight, it, Paint(grid).apply { color = Color.WHITE; strokeWidth = dp(1).toFloat() }) }
        }

        private fun drawSpeedChart(canvas: Canvas, top: Float, bottom: Float) {
            val maxSpeed = maxOf(comparison.speedA.maxOrNull() ?: 1f, comparison.speedB.maxOrNull() ?: 1f, 10f)
            drawGrid(canvas, top, bottom)
            drawTrace(canvas, comparison.speedB, top, bottom, maxSpeed, red)
            drawTrace(canvas, comparison.speedA, top, bottom, maxSpeed, green)
            white.textSize = dp(11).toFloat(); white.color = Color.LTGRAY; white.textAlign = Paint.Align.LEFT
            canvas.drawText("0", dp(8).toFloat(), bottom, white)
            canvas.drawText("${maxSpeed.toInt()} km/h", dp(8).toFloat(), top + dp(10), white)
        }

        private fun drawTrace(canvas: Canvas, values: FloatArray, top: Float, bottom: Float, max: Float, paint: Paint) {
            if (values.size < 2) return
            val path = Path()
            values.forEachIndexed { index, value ->
                val x = chartLeft + (chartRight - chartLeft) * index / (values.size - 1).toFloat()
                val y = bottom - (bottom - top) * (value / max).coerceIn(0f, 1f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }

        private fun drawCumulativeChart(canvas: Canvas, top: Float, bottom: Float) {
            val maxDelta = maxOf(comparison.cumulativeMs.maxOfOrNull { abs(it) } ?: 100f, 100f)
            val zero = (top + bottom) / 2f
            drawGrid(canvas, top, bottom, zero)
            if (comparison.cumulativeMs.size < 2) return
            comparison.cumulativeMs.indices.drop(1).forEach { index ->
                val x1 = xAt(index - 1); val x2 = xAt(index)
                val y1 = zero - (bottom - top) * .43f * (comparison.cumulativeMs[index - 1] / maxDelta).coerceIn(-1f, 1f)
                val y2 = zero - (bottom - top) * .43f * (comparison.cumulativeMs[index] / maxDelta).coerceIn(-1f, 1f)
                val paint = if ((comparison.cumulativeMs[index - 1] + comparison.cumulativeMs[index]) >= 0f) green else red
                canvas.drawLine(x1, y1, x2, y2, paint)
            }
            white.textSize = dp(11).toFloat(); white.color = Color.LTGRAY; white.textAlign = Paint.Align.LEFT
            canvas.drawText("+${"%.2f".format(Locale.US, maxDelta / 1_000f)} s", dp(3).toFloat(), top + dp(10), white)
            canvas.drawText("−${"%.2f".format(Locale.US, maxDelta / 1_000f)} s", dp(3).toFloat(), bottom, white)
        }

        private fun localBins(): List<LocalDeltaBin> {
            val values = comparison.localMs
            val window = maxOf(1, windowMeters)
            return generateSequence(1) { previous -> (previous + window).takeIf { it < values.size } }
                .map { start ->
                    val end = minOf(values.lastIndex, start + window - 1)
                    LocalDeltaBin(start, end, (start..end).sumOf { values[it].toDouble() }.toFloat())
                }.toList()
        }

        private fun drawLocalChart(canvas: Canvas, top: Float, bottom: Float) {
            val bins = localBins(); if (bins.isEmpty()) return
            val maxLocal = maxOf(bins.maxOf { abs(it.deltaMs) }, 25f)
            val zero = (top + bottom) / 2f
            drawGrid(canvas, top, bottom, zero)
            val gains = bins.sortedByDescending { it.deltaMs }.take(5).toSet()
            val losses = bins.sortedBy { it.deltaMs }.take(5).toSet()
            bins.forEach { bin ->
                val x = (xAt(bin.start) + xAt(bin.end)) / 2f
                val y = zero - (bottom - top) * .43f * (bin.deltaMs / maxLocal).coerceIn(-1f, 1f)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (bin.deltaMs >= 0f) Color.rgb(0, 232, 31) else Color.rgb(255, 61, 72)
                    strokeWidth = if (bin in gains || bin in losses) dp(5).toFloat() else dp(3).toFloat()
                }
                canvas.drawLine(x, zero, x, y, paint)
            }
            white.textSize = dp(11).toFloat(); white.color = Color.rgb(0, 232, 31); white.textAlign = Paint.Align.LEFT
            canvas.drawText("+ guadagno A", chartLeft, top + dp(12), white)
            white.color = Color.rgb(255, 61, 72); white.textAlign = Paint.Align.RIGHT
            canvas.drawText("− vantaggio B", chartRight, bottom - dp(5), white)
        }

        private fun drawLegend(canvas: Canvas) {
            val y = height - dp(22).toFloat()
            white.textSize = dp(13).toFloat(); white.textAlign = Paint.Align.LEFT
            white.color = green.color; canvas.drawText("— Pilota A / giro analizzato", dp(14).toFloat(), y, white)
            white.color = red.color; canvas.drawText("— Pilota B / best lap", width * .42f, y, white)
            white.color = Color.LTGRAY; canvas.drawText("A positivo = guadagna", width * .70f, y, white)
        }

        private fun drawCursor(canvas: Canvas, top: Float, bottom: Float) {
            val index = cursor.takeIf { it in comparison.distanceM.indices } ?: return
            val x = xAt(index)
            canvas.drawLine(x, top, x, bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = dp(1).toFloat() })
            val local = comparison.localMs[index]
            val text = "${comparison.distanceM[index].toInt()} m  ·  Δ ${"%+.3f".format(Locale.US, comparison.cumulativeMs[index] / 1_000f)} s  ·  locale ${"%+.0f".format(Locale.US, local)} ms\nA ${comparison.speedA[index].toInt()} km/h  ·  B ${comparison.speedB[index].toInt()} km/h"
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 5, 18, 25) }
            canvas.drawRoundRect(dp(50).toFloat(), dp(30).toFloat(), width - dp(58).toFloat(), dp(74).toFloat(), dp(4).toFloat(), dp(4).toFloat(), box)
            white.color = Color.WHITE; white.textSize = dp(12).toFloat(); white.textAlign = Paint.Align.LEFT
            text.split('\n').forEachIndexed { row, line -> canvas.drawText(line, dp(58).toFloat(), dp(47 + row * 17).toFloat(), white) }
        }

        private fun xAt(index: Int) = chartLeft + (chartRight - chartLeft) * index / (comparison.distanceM.size - 1).toFloat()

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                val fraction = ((event.x - chartLeft) / (chartRight - chartLeft)).coerceIn(0f, 1f)
                cursor = (fraction * (comparison.distanceM.size - 1)).toInt()
                invalidate()
            }
            return true
        }

        private fun buildComparison(a: RecordedLap, b: RecordedLap, sectors: List<TimingLine>): DistanceComparison {
            val pathA = cumulativeDistance(a.samples); val pathB = cumulativeDistance(b.samples)
            val commonLength = pathB.last().coerceAtLeast(20.0)
            val pointCount = commonLength.toInt().coerceAtLeast(2) + 1
            val grid = DoubleArray(pointCount) { index -> commonLength * index / (pointCount - 1) }
            val speedA = resampleSpeed(a, pathA, grid, commonLength)
            val speedB = resampleSpeed(b, pathB, grid, commonLength)
            val rawA = DoubleArray(pointCount); val rawB = DoubleArray(pointCount)
            for (index in 1 until pointCount) {
                val step = grid[index] - grid[index - 1]
                rawA[index] = step / (speedA[index].toDouble() / 3.6).coerceAtLeast(.8)
                rawB[index] = step / (speedB[index].toDouble() / 3.6).coerceAtLeast(.8)
            }
            val scaleA = (a.durationMs / 1_000.0) / rawA.sum().coerceAtLeast(.01)
            val scaleB = (b.durationMs / 1_000.0) / rawB.sum().coerceAtLeast(.01)
            val local = FloatArray(pointCount); val cumulative = FloatArray(pointCount)
            for (index in 1 until pointCount) {
                // Positive: A needs less time than B and therefore gains.
                local[index] = ((rawB[index] * scaleB - rawA[index] * scaleA) * 1_000.0).toFloat()
                cumulative[index] = cumulative[index - 1] + local[index]
            }
            // Eliminate any floating rounding residual: endpoint exactly matches lap-time difference.
            cumulative[cumulative.lastIndex] = (b.durationMs - a.durationMs).toFloat()
            val sectorFractions = sectors.mapNotNull { line ->
                val index = Geo.nearestRouteIndex(lineCenter(line), b.samples.map { TrackPoint(it.lat, it.lon) })
                (pathB[index] / commonLength).toFloat().takeIf { it in .02f..0.98f }
            }
            return DistanceComparison(grid, speedA, speedB, cumulative, local, sectorFractions)
        }

        private fun cumulativeDistance(samples: List<GpsSample>): DoubleArray {
            val result = DoubleArray(samples.size)
            for (index in 1 until samples.size) result[index] = result[index - 1] + Geo.distanceM(samples[index - 1].lat, samples[index - 1].lon, samples[index].lat, samples[index].lon)
            return result
        }

        private fun resampleSpeed(lap: RecordedLap, path: DoubleArray, grid: DoubleArray, commonLength: Double): FloatArray {
            val ownLength = path.last().coerceAtLeast(.01)
            var segment = 1
            return FloatArray(grid.size) { index ->
                val target = grid[index] / commonLength * ownLength
                while (segment < path.lastIndex && path[segment] < target) segment++
                val previous = (segment - 1).coerceAtLeast(0)
                val distance = (path[segment] - path[previous]).takeIf { it > .01 } ?: 1.0
                val fraction = ((target - path[previous]) / distance).coerceIn(0.0, 1.0)
                (lap.samples[previous].speedMps.toDouble() + (lap.samples[segment].speedMps - lap.samples[previous].speedMps).toDouble() * fraction).toFloat() * 3.6f
            }
        }
    }

    private fun showSpeedComparison(session: SavedSession, lap: RecordedLap) {
        val best = session.laps.filter { it.valid }.minByOrNull { it.durationMs }
        if (best == null || best.samples.size < 3) {
            AlertDialog.Builder(this).setTitle("Confronto velocità").setMessage("Serve almeno un giro valido di riferimento.").setPositiveButton("OK", null).show()
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(3, 9, 14)) }
        val geometry = geometryForSession(session, lap)
        val comparison = DistanceDeltaComparisonView(lap, best, geometry.sectors)
        root.addView(comparison, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(12), dp(12), dp(12), dp(62)) })
        val windows = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(5, 10, 20, 50).forEach { meters ->
            windows.addView(actionButton("${meters} m") { comparison.setLocalWindowMeters(meters) }, LinearLayout.LayoutParams(dp(62), dp(38)).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        root.addView(windows, FrameLayout.LayoutParams(-2, dp(42), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })
        root.addView(actionButton("CHIUDI") { dialog.dismiss() }, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.END).apply { topMargin = dp(22); rightMargin = dp(22) })
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setLayout(-1, -1)
            setBackgroundDrawable(ColorDrawable(Color.rgb(3, 9, 14)))
            decorView.systemUiVisibility = window.decorView.systemUiVisibility
        }
    }

    private fun showLapReview(session: SavedSession, lap: RecordedLap, onSessionUpdated: (SavedSession) -> Unit = {}) {
        if (lap.samples.size < 3) {
            AlertDialog.Builder(this).setTitle("Mappa giro").setMessage("Questo giro non contiene abbastanza punti GPS per la mappa.").setPositiveButton("OK", null).show()
            return
        }
        val geometry = geometryForSession(session, lap)
        val finish = geometry.finish
        val sectors = geometry.sectors
        val bestLap = session.laps.filter { it.valid }.minByOrNull { it.durationMs }
        val sectorText = sectors.mapIndexed { index, line ->
            val currentElapsed = elapsedAtLine(lap.samples, line)
            val bestElapsed = bestLap?.let { elapsedAtLine(it.samples, line) }
            val delta = if (currentElapsed != null && bestElapsed != null) currentElapsed - bestElapsed else null
            "S${index + 1}  ${currentElapsed?.let(::formatTime) ?: "—"}  ·  Δ ${delta?.let(::formatDelta) ?: "—"}"
        }.joinToString("\n")
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(3, 9, 14))
        }
        val map = TrackMapView(this) { }.apply {
            setTrack(lap.samples.map { TrackPoint(it.lat, it.lon) })
            setTimingLine(finish)
            setSectors(sectors)
            setSpeedMarkers(speedMarkersForLap(lap))
            postDelayed({ fitEntireTrack() }, 500)
        }
        val mapFrame = FrameLayout(this)
        mapFrame.addView(map, FrameLayout.LayoutParams(-1, -1))
        mapFrame.addView(TextView(this).apply {
            text = "SETTORI RILEVATI\n$sectorText"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(235, 247, 250))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = hudPanel(Color.argb(225, 7, 24, 33), Color.rgb(72, 205, 255), 1)
        }, FrameLayout.LayoutParams(dp(220), -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(8), dp(8), dp(10), dp(10))
        })
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(7, 24, 33))
        }
        info.addView(TextView(this).apply {
            text = "LAP ${lap.number}\n${formatTime(lap.durationMs)}"
            textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        })
        info.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        info.addView(actionButton("ANALISI INGEGNERE") {
            showEngineerAnalysis(session, lap, sectors)
        }, LinearLayout.LayoutParams(-1, dp(44)))
        info.addView(actionButton("GRAFICO CONFRONTO VELOCITÀ") {
            showSpeedComparison(session, lap)
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        info.addView(actionButton(if (lap.valid) "GIRO NON VALIDO" else "RIPRISTINA GIRO") {
            val updated = session.copy(laps = session.laps.map { candidate ->
                if (candidate.number == lap.number) candidate.copy(valid = !candidate.valid) else candidate
            })
            sessionStore.save(updated)
            status.text = if (lap.valid) "Lap ${lap.number} escluso da best e statistiche" else "Lap ${lap.number} ripristinato"
            dialog.dismiss()
            onSessionUpdated(updated)
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        info.addView(actionButton("SALVA COME PISTA") {
            saveLapAsTrack(session, lap, finish, sectors)
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        info.addView(actionButton("CHIUDI") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(6); gravity = Gravity.END })
        root.addView(mapFrame, LinearLayout.LayoutParams(0, -1, .72f).apply { rightMargin = dp(6) })
        root.addView(info, LinearLayout.LayoutParams(0, -1, .28f))
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setLayout(-1, -1)
            setBackgroundDrawable(ColorDrawable(Color.rgb(3, 9, 14)))
            decorView.systemUiVisibility = window.decorView.systemUiVisibility
        }
    }

    /** Offline lap-coach comparison: uses only GPS position, speed and timing samples, so it
     * remains honest about what a phone can observe while still isolating useful driving areas. */
    private fun showEngineerAnalysis(session: SavedSession, lap: RecordedLap, sectors: List<TimingLine>) {
        val reference = session.laps.filter { it.valid }.minByOrNull { it.durationMs }
        if (reference == null || reference.samples.size < 3) {
            AlertDialog.Builder(this).setTitle("Analisi ingegnere").setMessage("Serve almeno un giro valido di riferimento.").setPositiveButton("OK", null).show()
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(Color.rgb(3, 9, 14)) }
        val map = TrackMapView(this) { }.apply {
            setTrack(lap.samples.map { TrackPoint(it.lat, it.lon) })
            setTimingLine(geometryForSession(session, lap).finish)
            setSectors(sectors)
            setSpeedMarkers(speedMarkersForLap(lap))
            postDelayed({ fitEntireTrack() }, 500)
        }
        val report = TextView(this).apply {
            text = engineerReport(lap, reference, sectors)
            textSize = 16f
            setTextColor(Color.rgb(225, 240, 244))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(7, 24, 33)) }
        right.addView(ScrollView(this).apply { addView(report) }, LinearLayout.LayoutParams(-1, 0, 1f))
        right.addView(actionButton("CHIUDI") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(12), dp(4), dp(12), dp(12)); gravity = Gravity.END })
        root.addView(map, LinearLayout.LayoutParams(0, -1, .57f).apply { rightMargin = dp(6) })
        root.addView(right, LinearLayout.LayoutParams(0, -1, .43f))
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply { setLayout(-1, -1); setBackgroundDrawable(ColorDrawable(Color.rgb(3, 9, 14))); decorView.systemUiVisibility = window.decorView.systemUiVisibility }
    }

    private fun engineerReport(lap: RecordedLap, reference: RecordedLap, sectors: List<TimingLine>): String {
        val totalDelta = lap.durationMs - reference.durationMs
        val targetSplits = segmentTimes(lap, sectors)
        val referenceSplits = segmentTimes(reference, sectors)
        val splitDeltas = targetSplits.indices.mapNotNull { index ->
            val target = targetSplits[index] ?: return@mapNotNull null
            val best = referenceSplits.getOrNull(index) ?: return@mapNotNull null
            index to (target - best)
        }
        val lost = splitDeltas.maxByOrNull { it.second }
        val gained = splitDeltas.minByOrNull { it.second }
        val targetSummary = speedSummary(lap)
        val referenceSummary = speedSummary(reference)
        val slowMarker = speedMarkersForLap(lap).filter { it.kind == "braking" }.minByOrNull { it.speedKmh }
        val slowReference = slowMarker?.let { marker -> nearestSpeed(reference, marker.point) }
        val fastMarker = speedMarkersForLap(lap).filter { it.kind == "acceleration" }.maxByOrNull { it.speedKmh }
        val fastReference = fastMarker?.let { marker -> nearestSpeed(reference, marker.point) }
        return buildString {
            append("ANALISI INGEGNERE\n\n")
            append("LAP ${lap.number}: ${formatTime(lap.durationMs)}\n")
            append("Best lap: ${formatTime(reference.durationMs)}\n")
            append("DELTA TOTALE  ${formatDelta(totalDelta)}\n\n")
            append("SETTORI\n")
            splitDeltas.forEach { (index, delta) -> append("S${index + 1}  ${formatDelta(delta)}\n") }
            lost?.takeIf { it.second > 0 }?.let { (index, delta) ->
                append("\nAREA DA MIGLIORARE\nPerdita maggiore nel tratto S${index + 1}: ${formatDelta(delta)} rispetto al best.\n")
            }
            gained?.takeIf { it.second < 0 }?.let { (index, delta) ->
                append("\nPUNTO FORTE\nNel tratto S${index + 1} guadagni ${formatDelta(-delta)} sul best.\n")
            }
            append("\nVELOCITÀ\nV max ${targetSummary.maxKmh} km/h (best ${referenceSummary.maxKmh})\n")
            slowMarker?.let { marker ->
                append("Percorrenza lenta: ${marker.speedKmh} km/h")
                slowReference?.let { append(" · best ${it} km/h") }
                append("\n")
            }
            fastMarker?.let { marker ->
                append("Uscita più veloce: ${marker.speedKmh} km/h")
                fastReference?.let { append(" · best ${it} km/h") }
                append("\n")
            }
            if (totalDelta == 0L) append("\nGiro di riferimento: usa i marker MIN/MAX sulla mappa per confermare frenate e uscite.")
        }
    }

    private fun segmentTimes(lap: RecordedLap, sectors: List<TimingLine>): List<Long?> {
        if (sectors.isEmpty()) return emptyList()
        val elapsed = sectors.map { elapsedAtLine(lap.samples, it) }
        val result = mutableListOf<Long?>()
        var previous = 0L
        elapsed.forEach { value ->
            if (value == null) result += null else {
                result += value - previous
                previous = value
            }
        }
        val finalStart = elapsed.lastOrNull()
        result += finalStart?.let { lap.durationMs - it }
        return result
    }

    private fun nearestSpeed(lap: RecordedLap, point: TrackPoint): Int? = lap.samples.minByOrNull {
        Geo.distanceM(it.lat, it.lon, point.lat, point.lon)
    }?.speedMps?.times(3.6f)?.toInt()

    private data class LapSpeedSummary(val maxKmh: Int, val minKmh: Int, val brakingMinKmh: Int, val accelerationMaxKmh: Int)

    /**
     * Finds useful turning points in a speed trace.  Raw phone GPS speed fluctuates, so we
     * smooth it first and only retain extrema that differ meaningfully from both sides.
     * F = end of braking, A = end of the following acceleration.
     */
    private fun speedMarkersForLap(lap: RecordedLap): List<SpeedMarker> {
        val samples = lap.samples
        if (samples.size < 9) return emptyList()
        val raw = samples.map { it.speedMps * 3.6f }
        val smooth = raw.indices.map { index ->
            val from = (index - 2).coerceAtLeast(0)
            val to = (index + 2).coerceAtMost(raw.lastIndex)
            raw.subList(from, to + 1).average().toFloat()
        }
        data class Candidate(val index: Int, val braking: Boolean)
        val candidates = mutableListOf<Candidate>()
        val radius = 2
        for (index in radius until smooth.size - radius) {
            val before = smooth.subList(index - radius, index)
            val after = smooth.subList(index + 1, index + radius + 1)
            val value = smooth[index]
            val isMinimum = value <= before.minOrNull()!! && value <= after.minOrNull()!! &&
                before.maxOrNull()!! - value >= 1f && after.maxOrNull()!! - value >= 1f
            val isMaximum = value >= before.maxOrNull()!! && value >= after.maxOrNull()!! &&
                value - before.minOrNull()!! >= 1f && value - after.minOrNull()!! >= 1f
            if (isMinimum) candidates += Candidate(index, braking = true)
            if (isMaximum) candidates += Candidate(index, braking = false)
        }
        // A flat GPS plateau can produce neighbouring extrema: retain one marker per 1.5 s.
        val selected = mutableListOf<Candidate>()
        candidates.forEach { candidate ->
            val previous = selected.lastOrNull()
            if (previous == null || samples[candidate.index].timeMs - samples[previous.index].timeMs >= 1_500L) {
                selected += candidate
            } else {
                val previousValue = smooth[previous.index]
                val currentValue = smooth[candidate.index]
                val replace = if (candidate.braking) currentValue < previousValue else currentValue > previousValue
                if (replace) selected[selected.lastIndex] = candidate
            }
        }
        // Always show at least a meaningful slow point and a meaningful fast point, even if
        // the phone signal is too smooth to form a textbook local extremum.
        if (selected.none { it.braking }) selected += Candidate(smooth.indices.minByOrNull { smooth[it] }!!, braking = true)
        if (selected.none { !it.braking }) selected += Candidate(smooth.indices.maxByOrNull { smooth[it] }!!, braking = false)
        return selected.distinctBy { it.index to it.braking }.sortedBy { it.index }.take(16).map { candidate ->
            SpeedMarker(
                TrackPoint(samples[candidate.index].lat, samples[candidate.index].lon),
                smooth[candidate.index].toInt(),
                if (candidate.braking) "braking" else "acceleration"
            )
        }
    }

    private fun speedSummary(lap: RecordedLap): LapSpeedSummary {
        val speeds = lap.samples.map { (it.speedMps * 3.6f).toInt() }
        val localMins = speeds.indices.filter { it in 1 until speeds.lastIndex && speeds[it] <= speeds[it - 1] && speeds[it] <= speeds[it + 1] }.map { speeds[it] }
        val localMaxes = speeds.indices.filter { it in 1 until speeds.lastIndex && speeds[it] >= speeds[it - 1] && speeds[it] >= speeds[it + 1] }.map { speeds[it] }
        return LapSpeedSummary(
            speeds.maxOrNull() ?: 0, speeds.minOrNull() ?: 0,
            localMins.minOrNull() ?: speeds.minOrNull() ?: 0,
            localMaxes.maxOrNull() ?: speeds.maxOrNull() ?: 0
        )
    }

    private fun lineFromLap(lap: RecordedLap): TimingLine {
        val samples = lap.samples
        val heading = if (samples.size >= 2) Geo.headingDeg(TrackPoint(samples[0].lat, samples[0].lon), TrackPoint(samples[1].lat, samples[1].lon)) else 0.0
        return Geo.timingLine(TrackPoint(samples.first().lat, samples.first().lon), heading)
    }

    private data class SessionGeometry(val finish: TimingLine, val sectors: List<TimingLine>, val fromSavedTrack: Boolean)

    /** Prefer the edited circuit profile over historical marker coordinates in a session.
     * A name match is preferred; otherwise only a very-close profile is accepted, so nearby
     * circuit variants cannot silently replace the geometry. */
    private fun geometryForSession(session: SavedSession, fallbackLap: RecordedLap): SessionGeometry {
        val firstSample = session.samples.firstOrNull() ?: session.laps.firstOrNull()?.samples?.firstOrNull()
        val locality = session.displayName.substringBefore(" · ").trim()
        val candidates = firstSample?.let { sample -> trackStore.nearby(TrackPoint(sample.lat, sample.lon), radiusM = 500.0) }.orEmpty()
        val saved = candidates.firstOrNull { it.name.equals(locality, ignoreCase = true) }
            ?: candidates.minByOrNull { track ->
                Geo.distanceM(firstSample!!.lat, firstSample.lon, track.center.lat, track.center.lon)
            }
        if (saved != null) return SessionGeometry(saved.finishLine, saved.sectors.take(2), fromSavedTrack = true)
        return SessionGeometry(
            session.timingLine ?: lineFromLap(fallbackLap),
            session.sectorLines.take(2).ifEmpty { deriveSectors(fallbackLap.samples).map { it.line } },
            fromSavedTrack = false
        )
    }

    private fun saveLapAsTrack(session: SavedSession, lap: RecordedLap, finish: TimingLine, sectors: List<TimingLine>) {
        if (!lap.valid) {
            status.text = "Un giro non valido non può diventare una pista"
            return
        }
        val baseName = session.displayName.substringBefore(" · ").ifBlank { "Pista salvata" }
        trackStore.save(SavedTrack("track_${System.currentTimeMillis()}", baseName, System.currentTimeMillis(), lap, finish, sectors))
        status.text = "$baseName salvata come pista"
        speak("Pista salvata. Verrà proposta quando sarai nelle vicinanze", flush = true)
    }

    private fun showSessionAnalysis(session: SavedSession) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val allSamples = session.samples.ifEmpty { session.laps.flatMap { it.samples } }
        val fallbackLap = session.laps.filter { it.valid }.minByOrNull { it.durationMs }
        val geometry = fallbackLap?.let { geometryForSession(session, it) }
        val finish = geometry?.finish
        val sectors = geometry?.sectors.orEmpty()
        val map = TrackMapView(this, ::handleTrackTap).apply {
            setTrack(allSamples.map { TrackPoint(it.lat, it.lon) })
            setTimingLine(finish)
            setSectors(sectors)
            postDelayed({ fitEntireTrack() }, 500)
        }
        val mapFrame = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(-1, -1))
            addView(
                actionButton("ANALISI SESSIONE") { showSessionEngineerAnalysis(session) },
                FrameLayout.LayoutParams(dp(178), dp(42), Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = dp(10); bottomMargin = dp(10)
                }
            )
        }
        val root = LinearLayout(this).apply {
            orientation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(3, 9, 14))
        }
        val analysis = SessionAnalysisView(
            session,
            onClose = { dialog.dismiss() },
            onSessionUpdated = { updated -> dialog.dismiss(); showSessionAnalysis(updated) }
        )
        if (root.orientation == LinearLayout.VERTICAL) {
            root.addView(mapFrame, LinearLayout.LayoutParams(-1, 0, .34f).apply { bottomMargin = dp(6) })
            root.addView(analysis, LinearLayout.LayoutParams(-1, 0, .66f))
        } else {
            root.addView(mapFrame, LinearLayout.LayoutParams(0, -1, .34f).apply { rightMargin = dp(6) })
            root.addView(analysis, LinearLayout.LayoutParams(0, -1, .66f))
        }
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setLayout(-1, -1)
            setBackgroundDrawable(ColorDrawable(Color.rgb(3, 9, 14)))
            decorView.systemUiVisibility = window.decorView.systemUiVisibility
        }
    }

    /** A cautious whole-session report: it turns the recorded GPS facts into actionable
     * priorities, while marking tyre/traffic conclusions as indications rather than certainties. */
    private fun showSessionEngineerAnalysis(session: SavedSession) {
        val validLaps = session.laps.filter { it.valid }.sortedBy { it.number }
        if (validLaps.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Analisi sessione").setMessage("Non ci sono giri validi da analizzare.").setPositiveButton("OK", null).show()
            return
        }
        val best = validLaps.minByOrNull { it.durationMs } ?: return
        val sectors = geometryForSession(session, best).sectors.take(2)
        val report = TextView(this).apply {
            text = sessionEngineerReport(session, validLaps, best, sectors)
            textSize = 16f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(Color.rgb(225, 240, 244))
            setPadding(dp(22), dp(20), dp(22), dp(34))
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(3, 9, 14)) }
        root.addView(ScrollView(this).apply { addView(report) }, FrameLayout.LayoutParams(-1, -1))
        root.addView(actionButton("CHIUDI") { dialog.dismiss() }, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.END).apply { topMargin = dp(14); rightMargin = dp(14) })
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setLayout(-1, -1)
            setBackgroundDrawable(ColorDrawable(Color.rgb(3, 9, 14)))
            decorView.systemUiVisibility = window.decorView.systemUiVisibility
        }
    }

    private fun sessionEngineerReport(session: SavedSession, laps: List<RecordedLap>, best: RecordedLap, sectors: List<TimingLine>): String {
        val durations = laps.map { it.durationMs }
        val average = durations.average().toLong()
        val spread = (durations.maxOrNull() ?: average) - (durations.minOrNull() ?: average)
        val standardDeviation = kotlin.math.sqrt(durations.map { (it - average).toDouble() * (it - average) }.average()).toLong()
        val splitRows = laps.map { lap -> lap to segmentTimes(lap, sectors).map { it ?: 0L } }
        val segmentCount = splitRows.maxOfOrNull { it.second.size } ?: 0
        val segmentLabels = List(segmentCount) { index ->
            when {
                index == 0 -> "SETTORE 1"
                index == 1 -> "SETTORE 2"
                else -> "TRATTO FINALE"
            }
        }
        val segmentAverages = List(segmentCount) { index -> splitRows.mapNotNull { it.second.getOrNull(index)?.takeIf { value -> value > 0 } }.average().toLong() }
        val segmentBests = List(segmentCount) { index ->
            splitRows.mapNotNull { (lap, splits) -> splits.getOrNull(index)?.takeIf { it > 0 }?.let { lap to it } }.minByOrNull { it.second }
        }
        val ideal = segmentBests.mapNotNull { it?.second }.sum()
        val firstGroup = laps.take(maxOf(1, laps.size / 3)).map { it.durationMs }.average().toLong()
        val lastGroup = laps.takeLast(maxOf(1, laps.size / 3)).map { it.durationMs }.average().toLong()
        val phaseDelta = lastGroup - firstGroup
        val worstIndex = segmentAverages.indices.maxByOrNull { segmentAverages[it] - (segmentBests[it]?.second ?: segmentAverages[it]) }
        val strengthIndex = segmentAverages.indices.minByOrNull { segmentAverages[it] - (segmentBests[it]?.second ?: segmentAverages[it]) }
        val summaries = laps.associateWith(::speedSummary)
        val bestSpeed = summaries[best] ?: speedSummary(best)
        val averageVmax = summaries.values.map { it.maxKmh }.average().toInt()
        val brakingRange = summaries.values.map { it.brakingMinKmh }.let { (it.maxOrNull() ?: 0) - (it.minOrNull() ?: 0) }
        val exitRange = summaries.values.map { it.accelerationMaxKmh }.let { (it.maxOrNull() ?: 0) - (it.minOrNull() ?: 0) }
        val gpsAccuracy = session.samples.map { it.accuracyM }.filter { it.isFinite() && it > 0f }.average()
        val bestSectorMargins = segmentBests.mapIndexed { index, record ->
            val bestValue = record?.second ?: 0L
            val gaps = splitRows.mapNotNull { it.second.getOrNull(index)?.takeIf { value -> value > 0 }?.minus(bestValue) }
            gaps.count { it >= 500L }
        }
        val improvingLaps = laps.zipWithNext().count { (previous, current) -> current.durationMs < previous.durationMs }
        val firstBestLap = laps.firstOrNull { it.durationMs == best.durationMs }
        return buildString {
            append("ANALISI INGEGNERE — SESSIONE\n")
            append("${session.displayName.uppercase(Locale.ITALIAN)}\n\n")
            append("RITMO GENERALE\n")
            append("Giri validi: ${laps.size} su ${session.laps.size}\n")
            append("Best lap: ${formatTime(best.durationMs)}  (lap ${best.number})\n")
            append("Media: ${formatTime(average)}  · dispersione: ${formatTime(spread)}\n")
            append("Costanza: deviazione media ${formatTime(standardDeviation)}\n")
            if (ideal > 0) append("Ideal lap: ${formatTime(ideal)}  · potenziale ${formatDelta(ideal - best.durationMs)}\n")
            if (gpsAccuracy > 0.0) append("Qualità GPS media: ±${gpsAccuracy.toInt()} m · ${session.samples.size} punti registrati\n")
            append("Progressione: $improvingLaps miglioramenti tra giri consecutivi · best ottenuto al lap ${firstBestLap?.number ?: best.number}\n")
            append("\nGIRO PER GIRO\n")
            laps.forEach { lap ->
                val summary = summaries[lap] ?: speedSummary(lap)
                val delta = lap.durationMs - best.durationMs
                val splits = splitRows.firstOrNull { it.first == lap }?.second.orEmpty()
                val splitText = splits.mapIndexedNotNull { index, value ->
                    value.takeIf { it > 0 }?.let { "S${index + 1} ${formatTime(it)}" }
                }.joinToString(" · ")
                append("LAP ${lap.number}: ${formatTime(lap.durationMs)}  ${if (lap.valid) "delta ${formatDelta(delta)}" else "NON VALIDO"}  · Vmax ${summary.maxKmh} km/h")
                if (splitText.isNotEmpty()) append("  · $splitText")
                append("\n")
            }
            append("\nEVOLUZIONE SESSIONE\n")
            when {
                phaseDelta <= -300L -> append("La parte finale è più rapida di ${formatTime(-phaseDelta)} rispetto all'inizio: andamento compatibile con maggiore fiducia, pista o gomme in finestra.\n")
                phaseDelta >= 300L -> append("La parte finale è più lenta di ${formatTime(phaseDelta)} rispetto all'inizio: possibile degrado gomme, traffico o calo di costanza; confronta le condizioni in pista.\n")
                else -> append("Il ritmo tra inizio e fine resta stabile (variazione ${formatDelta(phaseDelta)}): buona ripetibilità della sessione.\n")
            }
            append("\nSETTORI E AREE CHIAVE\n")
            segmentLabels.forEachIndexed { index, label ->
                val record = segmentBests[index]
                if (record != null) {
                    val averageGap = segmentAverages[index] - record.second
                    append("$label  best ${formatTime(record.second)} (lap ${record.first.number}) · media ${formatTime(segmentAverages[index])} · margine ${formatDelta(averageGap)}")
                    if (bestSectorMargins[index] > 0) append(" · ${bestSectorMargins[index]} giri oltre mezzo secondo dal record")
                    append("\n")
                }
            }
            if (segmentCount > 0) {
                append("\nRECORD PARZIALI\n")
                segmentBests.forEachIndexed { index, record ->
                    record?.let { append("${segmentLabels[index]}: ${formatTime(it.second)} ottenuto al lap ${it.first.number}; unirlo agli altri record costruisce l'ideal lap.\n") }
                }
            }
            worstIndex?.let { index ->
                val gap = segmentAverages[index] - (segmentBests[index]?.second ?: segmentAverages[index])
                append("\nPRIORITÀ: ${segmentLabels[index]} concentra il maggiore margine medio (${formatDelta(gap)}). Qui gli errori sono più ricorrenti: rivedi punto di frenata, rilascio e uscita curva.\n")
            }
            strengthIndex?.let { index ->
                append("PUNTO FORTE: ${segmentLabels[index]} è il tratto più vicino al suo potenziale. Mantieni questa sequenza di guida come riferimento.\n")
            }
            append("\nVELOCITÀ E GUIDA\n")
            append("Best lap: V max ${bestSpeed.maxKmh} km/h · minima frenata ${bestSpeed.brakingMinKmh} km/h · uscita/accelerazione ${bestSpeed.accelerationMaxKmh} km/h\n")
            append("V max media sessione: $averageVmax km/h\n")
            append("Variabilità minime di frenata: $brakingRange km/h · variabilità uscite: $exitRange km/h\n")
            if (brakingRange > 7 || exitRange > 7) append("Le velocità nei punti chiave variano molto: la ripetibilità di frenata e apertura gas è la prima leva per rendere i giri più costanti.\n")
            else append("Le velocità nei punti chiave sono abbastanza ripetibili: il guadagno principale è nella precisione delle traiettorie e nell'unione dei migliori settori.\n")
            append("\nINDICAZIONE PER IL PROSSIMO STINT\n")
            when {
                worstIndex != null && (segmentAverages[worstIndex] - (segmentBests[worstIndex]?.second ?: segmentAverages[worstIndex])) >= 500L ->
                    append("Concentrati su ${segmentLabels[worstIndex]}: qui c'è il margine più grande e si ripete in più giri. Lavora prima sul punto di frenata e poi sull'uscita; non inseguire la velocità massima sul rettilineo se la curva precedente peggiora.\n")
                phaseDelta <= -300L -> append("Il ritmo sta crescendo: mantieni la stessa sequenza e prova a rifinire il settore più lento senza cambiare tutto il giro.\n")
                brakingRange > 7 -> append("La priorità è rendere più costante la frenata: anticipare o ritardare troppo il punto di staccata sta creando parte della dispersione.\n")
                else -> append("La base è solida: cerca di combinare i tuoi migliori settori nello stesso giro e punta alla pulizia delle traiettorie.\n")
            }
            append("\nNOTA METODO\nLe indicazioni derivano da GPS e velocità del telefono: utili per individuare tendenze e priorità, non sostituiscono sensori ruota, IMU o dati telemetrici professionali.\n")
        }
    }

    /** Full-screen timing table inspired by a race-control/F1 session monitor. */
    private inner class SessionAnalysisView(
        private val session: SavedSession,
        private val onClose: () -> Unit,
        private val onSessionUpdated: (SavedSession) -> Unit
    ) : View(this@MainActivity) {
        private val validLaps = session.laps.filter { it.valid }
        private val geometryLap = validLaps.firstOrNull() ?: session.laps.firstOrNull()
        private val analysisSectors = geometryLap?.let { geometryForSession(session, it).sectors }.orEmpty().take(2)
        private val sectorCount = maxOf(analysisSectors.size, validLaps.maxOfOrNull { it.sectorElapsedMs.size } ?: 0).coerceIn(0, 2)
        private val best = validLaps.minOfOrNull { it.durationMs }
        private fun sectorTimes(lap: RecordedLap): List<Long?> = if (analysisSectors.isNotEmpty()) {
            analysisSectors.map { line -> elapsedAtLine(lap.samples, line) }
        } else lap.sectorElapsedMs.map { it }
        private val bestSectorTimes = List(sectorCount) { sector ->
            validLaps.mapNotNull { lap -> sectorTimes(lap).getOrNull(sector) }.minOrNull()
        }
        /** The ideal lap is the sum of the best independently recorded segments.  A partial
         * lap must not hide it: only the segment that lacks a reading is ignored. */
        private val ideal = if (sectorCount > 0) {
            val segments = (0..sectorCount).mapNotNull { segment ->
                validLaps.mapNotNull { lap ->
                    val sectors = sectorTimes(lap)
                    when {
                        segment < sectorCount && sectors.getOrNull(segment) != null -> {
                            val start = if (segment == 0) 0L else sectors[segment - 1] ?: return@mapNotNull null
                            sectors[segment]!! - start
                        }
                        segment == sectorCount && sectors.getOrNull(segment - 1) != null ->
                            lap.durationMs - sectors[segment - 1]!!
                        else -> null
                    }
                }.minOrNull()
            }
            segments.takeIf { it.size == sectorCount + 1 }?.sum()
        } else null
        private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(18).toFloat(); typeface = Typeface.DEFAULT_BOLD }
        private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 190, 204); textSize = dp(10).toFloat(); typeface = Typeface.DEFAULT_BOLD }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(17).toFloat(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(218, 232, 236); textSize = dp(13).toFloat(); textAlign = Paint.Align.CENTER }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(31, 80, 96); strokeWidth = dp(1).toFloat() }
        private var scroll = 0f
        private var downY = 0f
        private var startScroll = 0f
        private var dragged = false
        private var tableDataTop = 0f
        private var tableRowHeight = 0f

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(3, 9, 14))
            val pad = dp(12).toFloat()
            canvas.drawRect(0f, 0f, width.toFloat(), dp(42).toFloat(), Paint().apply { color = Color.rgb(7, 31, 41) })
            canvas.drawRect(0f, 0f, dp(5).toFloat(), dp(42).toFloat(), Paint().apply { color = Color.rgb(255, 68, 81) })
            canvas.drawText("PIT ENGINEER // ${session.displayName.uppercase(Locale.ITALIAN)}", pad, dp(20).toFloat(), headerPaint)
            canvas.drawText("${SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.ITALIAN).format(Date(session.startedAtMs))} · ${if (session.simulated) "SIMULAZIONE" else "GPS"}", pad, dp(34).toFloat(), smallPaint)
            headerPaint.color = Color.rgb(72, 205, 255)
            headerPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("✕", width - pad, dp(27).toFloat(), headerPaint)
            headerPaint.textAlign = Paint.Align.LEFT
            headerPaint.color = Color.WHITE

            val cardTop = dp(50).toFloat(); val cardBottom = dp(91).toFloat()
            val cardWidth = (width - dp(28)) / 2f
            drawMetric(canvas, pad, cardTop, cardWidth, cardBottom, "BEST LAP", best?.let(::formatTime) ?: "--:--.---", Color.rgb(24, 213, 184))
            drawMetric(canvas, pad + cardWidth + dp(4), cardTop, cardWidth, cardBottom, "IDEAL", ideal?.let(::formatTime) ?: "--:--.---", Color.rgb(174, 119, 255))

            // Speed belongs to the lap that produced it, not to a session-wide summary.
            val tableTop = dp(107).toFloat()
            val headerBottom = tableTop + dp(25)
            canvas.drawRect(0f, tableTop, width.toFloat(), headerBottom, Paint().apply { color = Color.rgb(9, 39, 51) })
            val headers = listOf("LAP", "TEMPO", "DELTA") + (1..sectorCount).map { "S$it" } + listOf("V MAX", "V MIN")
            val columns = FloatArray(headers.size) { index -> width * (index + .5f) / headers.size }
            headers.forEachIndexed { index, label -> canvas.drawText(label, columns[index], tableTop + dp(17), smallPaint.apply { textAlign = Paint.Align.CENTER }) }
            val dataTop = headerBottom + dp(4)
            val rowHeight = dp(24).toFloat()
            tableDataTop = dataTop
            tableRowHeight = rowHeight
            val maxScroll = (session.laps.size * rowHeight - (height - dataTop - dp(8))).coerceAtLeast(0f)
            scroll = scroll.coerceIn(0f, maxScroll)
            session.laps.forEachIndexed { index, lap ->
                val rowTop = dataTop + rowHeight * index - scroll
                val y = rowTop + dp(16)
                if (rowTop < dataTop - rowHeight || rowTop > height + rowHeight) return@forEachIndexed
                val delta = if (lap.valid) best?.let { lap.durationMs - it } ?: 0 else 0
                val rowColor = when {
                    delta == 0L -> Color.rgb(24, 213, 184)
                    delta < 0L -> Color.rgb(91, 220, 135)
                    else -> Color.rgb(255, 112, 112)
                }
                canvas.drawLine(pad, rowTop + rowHeight, width - pad, rowTop + rowHeight, gridPaint)
                rowPaint.color = if (lap.valid) Color.WHITE else Color.rgb(122, 143, 151)
                canvas.drawText(lap.number.toString(), columns[0], y, rowPaint)
                rowPaint.color = if (!lap.valid) Color.rgb(122, 143, 151) else if (delta == 0L) Color.rgb(24, 213, 184) else Color.WHITE
                canvas.drawText(formatTime(lap.durationMs), columns[1], y, rowPaint)
                rowPaint.color = if (lap.valid) rowColor else Color.rgb(122, 143, 151)
                canvas.drawText(if (lap.valid) formatDelta(delta) else "NON VALIDO", columns[2], y, rowPaint)
                rowPaint.color = Color.rgb(255, 205, 90)
                val lapSectors = sectorTimes(lap)
                repeat(sectorCount) { sector ->
                    val value = lapSectors.getOrNull(sector)
                    rowPaint.color = if (lap.valid && value != null && value == bestSectorTimes[sector]) Color.rgb(69, 223, 123) else Color.rgb(255, 205, 90)
                    canvas.drawText(value?.let(::formatTime) ?: "—", columns[3 + sector], y, rowPaint)
                }
                rowPaint.color = Color.rgb(255, 112, 112)
                canvas.drawText(lap.samples.maxOfOrNull { it.speedMps }?.times(3.6f)?.toInt()?.toString() ?: "—", columns[3 + sectorCount], y, rowPaint)
                rowPaint.color = Color.rgb(72, 205, 255)
                canvas.drawText(lap.samples.minOfOrNull { it.speedMps }?.times(3.6f)?.toInt()?.toString() ?: "—", columns[4 + sectorCount], y, rowPaint)
            }
            if (session.laps.isEmpty()) {
                rowPaint.color = Color.LTGRAY
                canvas.drawText("NESSUN GIRO COMPLETO NELLA SESSIONE", width / 2f, dataTop + dp(35), rowPaint)
            }
        }

        private fun drawMetric(canvas: Canvas, left: Float, top: Float, cardWidth: Float, bottom: Float, label: String, value: String, accent: Int) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(7, 24, 33) }
            canvas.drawRoundRect(left, top, left + cardWidth, bottom, dp(4).toFloat(), dp(4).toFloat(), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat(); paint.color = accent
            canvas.drawRoundRect(left, top, left + cardWidth, bottom, dp(4).toFloat(), dp(4).toFloat(), paint)
            smallPaint.color = accent; smallPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(label, left + cardWidth / 2f, top + dp(14), smallPaint)
            valuePaint.color = Color.WHITE
            canvas.drawText(value, left + cardWidth / 2f, bottom - dp(10), valuePaint)
            smallPaint.color = Color.rgb(150, 190, 204); smallPaint.textAlign = Paint.Align.LEFT
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.y; startScroll = scroll; dragged = false; return true }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.y - downY) > dp(5)) dragged = true
                    scroll = (startScroll + downY - event.y).coerceAtLeast(0f)
                    invalidate(); return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged && event.x > width - dp(36) && event.y < dp(50)) onClose()
                    else if (!dragged && event.y >= tableDataTop && tableRowHeight > 0f) {
                        val index = ((event.y - tableDataTop + scroll) / tableRowHeight).toInt()
                        session.laps.getOrNull(index)?.let { showLapReview(session, it, onSessionUpdated) }
                    }
                    return true
                }
            }
            return true
        }
    }

    private fun resetSession(keepTrack: Boolean = false) {
        // A selected saved circuit is a session reference, not data from the just-finished
        // run. Preserve it when AVVIA prepares a new session.
        val savedTrackToKeep = if (keepTrack) activeSavedTrack else null
        if (!keepTrack) activeSavedTrack = null
        if (running) {
            val simulated = simulator != null
            val gpxName = saveCurrentSession()
            saveSessionRecord(simulated, gpxName)
        }
        stopSimulation()
        running = false
        paused = false
        pausedLapElapsedMs = null
        lowSpeedSinceMs = null
        startButton?.text = "AVVIA"
        pauseButton?.text = "PAUSA"
        timing.reset()
        autoFinish.reset()
        liveRoute.clear()
        sessionSamples.clear()
        recordedLaps.clear()
        currentSectorTimes.clear()
        bestSectorSegmentMs.clear()
        pendingSectorBaseline.clear()
        if (savedTrackToKeep == null) trackMap.setTrack(emptyList())
        bestLap = null
        predictor = null
        sectorReferences = emptyList()
        timing.sectors = emptyList()
        liveDeltaMs = null
        lastDeltaAnnouncementElapsedMs = Long.MIN_VALUE
        lastAnnouncedDeltaMs = null
        previousLiveDeltaMs = null
        deltaAnnouncementsSuppressedUntilMs = Long.MIN_VALUE
        if (savedTrackToKeep != null) {
            configureSavedTrack(savedTrackToKeep)
        } else {
            if (!keepTrack) timing.line = null
            dashboard.setTimingLine(timing.line)
            trackMap.setTimingLine(timing.line)
            trackMap.setSectors(emptyList())
        }
        lastLapMs = null
        lastBestReminderLap = 0
        dashboard.setLiveData(latestFix, null, null, 1, null, null, null, false)
        if (!keepTrack) status.text = "Cronometro azzerato"
    }

    private fun speak(text: String, flush: Boolean, silenceAfterMs: Long = 0L) {
        if (!voiceEnabled) return
        val utteranceId = "crono-${System.nanoTime()}"
        if (silenceAfterMs > 0L) speechSilenceAfter += utteranceId
        tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun formatTime(ms: Long) = "%d:%02d.%03d".format(Locale.US, ms / 60_000, (ms / 1_000) % 60, ms % 1_000)

    private fun spokenTime(ms: Long): String {
        return spokenStopwatch(ms)
    }

    private fun spokenDelta(ms: Long): String {
        val state = if (ms < 0) "Delta negativo di" else "Delta positivo di"
        return "$state ${spokenStopwatch(abs(ms))}"
    }

    private fun spokenSectorName(number: Int) = when (number) {
        1 -> "Settore uno"
        2 -> "Settore centrale"
        else -> "Settore $number"
    }

    /** Compact sector call with a natural, unambiguous delta. */
    private fun spokenSectorDelta(ms: Long): String {
        val state = if (ms < 0) "delta negativo di" else "delta positivo di"
        val amount = "%.2f".format(Locale.US, abs(ms) / 1_000.0)
        return "$state $amount secondi"
    }

    /** Concise radio timing: 0.22 -> "zero punto 22", 3.42 -> "tre e 42". */
    private fun spokenStopwatch(ms: Long): String {
        val roundedCentiseconds = ((ms + 5) / 10).coerceAtLeast(0)
        val minutes = roundedCentiseconds / 6_000
        val seconds = (roundedCentiseconds / 100) % 60
        val centiseconds = roundedCentiseconds % 100
        val centsText = "%02d".format(Locale.ITALIAN, centiseconds)
        return when {
            minutes > 0 -> {
                val minuteText = if (minutes == 1L) "un minuto" else "$minutes minuti"
                "$minuteText, $seconds secondi e $centsText centesimi"
            }
            seconds == 0L -> "zero punto $centsText secondi"
            else -> "$seconds secondi e $centsText centesimi"
        }
    }

    private fun engineerDeltaMessage(ms: Long, elapsedMs: Long): String {
        // Qui serve solo la quantità: spokenDelta() contiene già anche la direzione
        // ("sei avanti di" / "sei in ritardo di") e concatenarla ai messaggi
        // dell'ingegnere produceva frasi errate come "Ritardo, sei in ritardo".
        val amount = spokenStopwatch(abs(ms))
        val variants = if (ms < 0) listOf(
            "Delta negativo di $amount",
            "Delta negativo: guadagno di $amount",
            "Delta negativo: recuperi $amount",
            "Delta negativo: best lap battuto di $amount",
            "Delta negativo: sei più rapido di $amount",
            "Delta negativo: hai preso $amount al best lap",
            "Delta negativo: passo migliore di $amount",
            "Delta negativo: vantaggio di $amount",
            "Delta negativo: stai girando $amount più forte",
            "Delta negativo: hai limato $amount",
            "Delta negativo: tempo in verde di $amount",
            "Delta negativo: miglioramento di $amount"
        ) else listOf(
            "Delta positivo di $amount",
            "Delta positivo: stai peggiorando di $amount",
            "Delta positivo: hai perso $amount",
            "Delta positivo: ritardo di $amount",
            "Delta positivo: sei più lento di $amount",
            "Delta positivo: il best lap si allontana di $amount",
            "Delta positivo: perdi $amount sul giro migliore",
            "Delta positivo: tempo in rosso di $amount",
            "Delta positivo: scarto di $amount",
            "Delta positivo: sei sopra di $amount",
            "Delta positivo: calo di $amount",
            "Delta positivo: mancano $amount al best lap"
        )
        val index = ((abs(ms) / 10 + elapsedMs / 1_000) % variants.size).toInt()
        return variants[index]
    }

    private fun recoveryDeltaMessage(currentMs: Long, previousMs: Long, elapsedMs: Long): String {
        val recovered = spokenStopwatch(abs(previousMs - currentMs))
        val current = formatDelta(currentMs)
        val variants = listOf(
            "Delta negativo $current, recuperi $recovered",
            "Delta negativo $current: riduci il ritardo di $recovered",
            "Delta negativo $current: recupero di $recovered",
            "Delta negativo $current: hai ripreso $recovered",
            "Delta negativo $current: migliora di $recovered",
            "Delta negativo $current: riduci lo scarto di $recovered",
            "Delta negativo $current: buon recupero di $recovered",
            "Delta negativo $current: torni verso il best lap, recuperati $recovered",
            "Delta negativo $current: recuperi $recovered sul giro migliore",
            "Delta negativo $current: in calo di $recovered"
        )
        val index = ((currentMs / 10 + elapsedMs / 1_000) % variants.size).toInt()
        return variants[index]
    }

    private fun Float.format1() = "%.1f".format(Locale.US, this)

    private fun formatDelta(ms: Long) = "%+.2f".format(Locale.US, ms / 1_000.0)

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            tts?.language = Locale.ITALIAN
            preferredVoiceName?.let { name -> tts?.voices?.firstOrNull { it.name == name }?.let { tts?.voice = it } }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startGps()
    }

    override fun onDestroy() {
        stopSimulation()
        stopGps()
        tts?.shutdown()
        super.onDestroy()
    }

    private inner class RaceView : View(this) {
        private var liveTimingScale = 1f
        private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(61, 169, 255); strokeWidth = dp(2).toFloat(); style = Paint.Style.STROKE }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; strokeWidth = dp(4).toFloat() }
        private val fixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.FILL }
        private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = dp(48).toFloat(); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val deltaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(25).toFloat(); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        private val bestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(72, 205, 255)
            textSize = dp(22).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val lastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(238, 245, 247)
            textSize = dp(22).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(112, 157, 174); textSize = dp(11).toFloat(); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val hudStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 148, 190); strokeWidth = dp(1).toFloat(); style = Paint.Style.STROKE
        }
        private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 68, 85); strokeWidth = dp(1).toFloat() }
        private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(225, 240, 244); textSize = dp(20).toFloat(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 205, 90); textSize = dp(12).toFloat(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        private var track = emptyList<TrackPoint>()
        private var line: TimingLine? = null
        private var fix: GpsSample? = null
        private var elapsed: Long? = null
        private var delta: Long? = null
        private var lapNumber = 1
        private var last: Long? = null
        private var best: Long? = null
        private var bestLapNumber: Int? = null
        private val sectorResults = mutableMapOf<Int, SectorDisplay>()
        private var recording = false
        private var minLat = 0.0; private var maxLat = 1.0; private var minLon = 0.0; private var maxLon = 1.0
        private var path: Path? = null
        private var cachedWidth = -1; private var cachedHeight = -1
        var onTrackTapped: ((TrackPoint) -> Unit)? = null
        private var zoom = 1.0
        private var panX = 0f
        private var panY = 0f
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragged = false
        private val scaleDetector = ScaleGestureDetector(this@MainActivity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(0.7, 8.0)
                invalidate()
                return true
            }
        })

        fun setTrack(points: List<TrackPoint>) {
            track = points
            minLat = points.minOfOrNull { it.lat } ?: 0.0; maxLat = points.maxOfOrNull { it.lat } ?: 1.0
            minLon = points.minOfOrNull { it.lon } ?: 0.0; maxLon = points.maxOfOrNull { it.lon } ?: 1.0
            path = null
            invalidate()
        }

        fun setTimingLine(value: TimingLine?) { line = value; invalidate() }

        fun setLiveData(
            value: GpsSample?, lapElapsed: Long?, valueDelta: Long?, currentLapNumber: Int,
            lastLap: Long?, bestLap: Long?, bestNumber: Int?, isRecording: Boolean
        ) {
            fix = value; elapsed = lapElapsed; delta = valueDelta; lapNumber = currentLapNumber; last = lastLap; best = bestLap; bestLapNumber = bestNumber; recording = isRecording; invalidate()
        }

        fun setLiveTimingScale(scale: Float) {
            liveTimingScale = scale
            invalidate()
        }

        private fun timingTextSize(baseDp: Int) = dp(baseDp).toFloat() * liveTimingScale

        fun setSectorResult(number: Int, elapsedMs: Long, deltaMs: Long?) {
            sectorResults[number] = SectorDisplay(elapsedMs, deltaMs)
            invalidate()
        }

        fun clearSectorResult() { sectorResults.clear(); invalidate() }

        override fun onDraw(canvas: Canvas) {
            if (drawClassicDashboard(canvas)) return
            canvas.drawColor(Color.rgb(4, 5, 8))
            val margin = dp(9).toFloat()
            val green = Color.rgb(0, 238, 18)
            val red = Color.rgb(255, 43, 56)
            val magenta = Color.rgb(222, 61, 223)
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(126, 135, 148); style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
            val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            val gray = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(54, 60, 71) }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val titleHeight = dp(32).toFloat()
            val headerBottom = margin + titleHeight
            canvas.drawRoundRect(margin, margin, width - margin, headerBottom, dp(5).toFloat(), dp(5).toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green })
            canvas.drawRoundRect(margin, margin, width - margin, headerBottom, dp(5).toFloat(), dp(5).toFloat(), outline)
            text.color = Color.BLACK; text.textAlign = Paint.Align.CENTER; text.textSize = timingTextSize(17)
            canvas.drawText("SECTORS", width / 2f, headerBottom - dp(9), text)

            // Delta is the primary driving cue: it has its own high-contrast card.
            val deltaTop = headerBottom + dp(7)
            val deltaBottom = deltaTop + dp(62)
            canvas.drawRoundRect(margin, deltaTop, width - margin, deltaBottom, dp(5).toFloat(), dp(5).toFloat(), black)
            canvas.drawRoundRect(margin, deltaTop, width - margin, deltaBottom, dp(5).toFloat(), dp(5).toFloat(), outline)
            text.textAlign = Paint.Align.LEFT; text.textSize = timingTextSize(13); text.color = Color.WHITE
            canvas.drawText("DELTA SESSION", margin + dp(13), deltaTop + dp(18), text)
            text.textAlign = Paint.Align.CENTER; text.textSize = timingTextSize(34)
            val sessionDelta = delta
            text.color = when { sessionDelta == null -> Color.LTGRAY; sessionDelta < 0 -> green; else -> red }
            canvas.drawText(sessionDelta?.let(::formatDelta) ?: "--.---", width / 2f, deltaBottom - dp(11), text)

            val summaryTop = deltaBottom + dp(7)
            val summaryBottom = summaryTop + dp(91)
            canvas.drawRoundRect(margin, summaryTop, width - margin, summaryBottom, dp(5).toFloat(), dp(5).toFloat(), black)
            canvas.drawRoundRect(margin, summaryTop, width - margin, summaryBottom, dp(5).toFloat(), dp(5).toFloat(), outline)
            val labelRight = margin + (width - margin * 2) * .27f
            canvas.drawLine(labelRight, summaryTop, labelRight, summaryBottom, outline)
            canvas.drawLine(margin, summaryTop + (summaryBottom - summaryTop) / 2f, labelRight, summaryTop + (summaryBottom - summaryTop) / 2f, outline)
            text.color = Color.rgb(227, 231, 236); text.textAlign = Paint.Align.CENTER; text.textSize = timingTextSize(18)
            canvas.drawText("LAP", (margin + labelRight) / 2f, summaryTop + dp(30), text)
            canvas.drawText("BEST", (margin + labelRight) / 2f, summaryBottom - dp(13), text)
            text.textAlign = Paint.Align.CENTER; text.textSize = timingTextSize(29)
            text.color = Color.WHITE
            canvas.drawText(elapsed?.let(::formatTime) ?: "--:--.---", (labelRight + width - margin) / 2f, summaryTop + dp(34), text)
            text.color = magenta
            canvas.drawText(best?.let(::formatTime) ?: "--:--.---", (labelRight + width - margin) / 2f, summaryBottom - dp(14), text)

            val sectorTop = summaryBottom + dp(8)
            val rowHeight = dp(47).toFloat()
            (1..2).forEach { number ->
                drawSectorDashboardRow(canvas, sectorTop + (number - 1) * rowHeight, rowHeight, number, sectorResults[number], margin, labelRight, green, red, gray, black, outline, text)
            }

            val sessionsRight = width - dp(14).toFloat()
            val sessionsBottom = height - dp(14).toFloat()
            val sessionsLeft = sessionsRight - dp(118)
            val sessionsTop = sessionsBottom - dp(38)
            val sessionsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(7, 31, 41) }
            canvas.drawRoundRect(sessionsLeft, sessionsTop, sessionsRight, sessionsBottom, dp(5).toFloat(), dp(5).toFloat(), sessionsPaint)
            sessionsPaint.style = Paint.Style.STROKE
            sessionsPaint.strokeWidth = dp(1).toFloat()
            sessionsPaint.color = Color.rgb(72, 205, 255)
            canvas.drawRoundRect(sessionsLeft, sessionsTop, sessionsRight, sessionsBottom, dp(5).toFloat(), dp(5).toFloat(), sessionsPaint)
            labelPaint.color = Color.rgb(72, 205, 255)
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("SESSIONI", (sessionsLeft + sessionsRight) / 2f, sessionsTop + dp(24), labelPaint)
            val tracksRight = sessionsLeft - dp(7)
            val tracksLeft = tracksRight - dp(84)
            sessionsPaint.style = Paint.Style.FILL
            sessionsPaint.color = Color.rgb(7, 31, 41)
            canvas.drawRoundRect(tracksLeft, sessionsTop, tracksRight, sessionsBottom, dp(5).toFloat(), dp(5).toFloat(), sessionsPaint)
            sessionsPaint.style = Paint.Style.STROKE
            sessionsPaint.strokeWidth = dp(1).toFloat()
            sessionsPaint.color = Color.rgb(174, 119, 255)
            canvas.drawRoundRect(tracksLeft, sessionsTop, tracksRight, sessionsBottom, dp(5).toFloat(), dp(5).toFloat(), sessionsPaint)
            labelPaint.color = Color.rgb(174, 119, 255)
            canvas.drawText("PISTE", (tracksLeft + tracksRight) / 2f, sessionsTop + dp(24), labelPaint)

        }

        /** Original blue F1-style home HUD, retained as the live-driving dashboard. */
        private fun drawClassicDashboard(canvas: Canvas): Boolean {
            canvas.drawColor(Color.rgb(5, 16, 23))
            val pad = dp(12).toFloat()
            val red = Color.rgb(238, 50, 62)
            val green = Color.rgb(69, 223, 123)
            val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 28, 39) }
            val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 19, 29) }
            val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red }
            val largeOffset = if (liveTimingScale > 1f) dp((10f + (liveTimingScale - 1f) * 45f).toInt()) else 0
            val rowText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = timingTextSize(17); typeface = Typeface.DEFAULT_BOLD }
            val valueText = Paint(rowText).apply { textAlign = Paint.Align.RIGHT; textSize = timingTextSize(23) }

            canvas.drawRect(0f, 0f, width.toFloat(), dp(28).toFloat(), dark)
            canvas.drawRect(0f, 0f, dp(6).toFloat(), dp(28).toFloat(), accent)
            labelPaint.color = Color.rgb(218, 229, 235); labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("PIT ENGINEER  //  LIVE TIMING", pad + dp(8), dp(20).toFloat(), labelPaint)
            labelPaint.textAlign = Paint.Align.RIGHT; labelPaint.textSize = timingTextSize(10)
            canvas.drawText("LAP", width - pad - dp(34), dp(13).toFloat(), labelPaint)
            primaryPaint.color = Color.WHITE; primaryPaint.textAlign = Paint.Align.RIGHT; primaryPaint.textSize = timingTextSize(28)
            canvas.drawText("$lapNumber", width - pad, dp(31).toFloat(), primaryPaint)
            if (recording) {
                labelPaint.color = Color.rgb(255, 82, 92); labelPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("● REC", width * .66f, dp(20).toFloat(), labelPaint)
            }

            val deltaBottom = (dp(98) + largeOffset).toFloat()
            canvas.drawRoundRect(pad, dp(38).toFloat(), width - pad, deltaBottom, dp(4).toFloat(), dp(4).toFloat(), panel)
            canvas.drawRect(pad, dp(38).toFloat(), pad + dp(6), deltaBottom, accent)
            labelPaint.color = Color.rgb(112, 157, 174); labelPaint.textAlign = Paint.Align.LEFT; labelPaint.textSize = dp(11).toFloat()
            canvas.drawText("DELTA vs BEST LAP", pad + dp(18), (if (largeOffset > 0) dp(55) else dp(58)).toFloat(), labelPaint)
            val liveDelta = delta
            deltaPaint.color = when { liveDelta == null -> Color.LTGRAY; liveDelta < 0 -> green; else -> red }
            deltaPaint.textAlign = Paint.Align.LEFT; deltaPaint.textSize = timingTextSize(42)
            canvas.drawText(liveDelta?.let { if (it < 0) "▲ ${formatDelta(it)}" else "▼ ${formatDelta(it)}" } ?: "± 0.00", pad + dp(18), (dp(91) + largeOffset).toFloat(), deltaPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("BEST LAP", width - pad - dp(18), (if (largeOffset > 0) dp(55) else dp(58)).toFloat(), labelPaint)
            bestPaint.color = green; bestPaint.textAlign = Paint.Align.RIGHT; bestPaint.textSize = timingTextSize(36)
            val bestText = best?.let(::formatTime) ?: "--:--.---"
            val bestRight = width - pad - dp(18)
            canvas.drawText(bestText, bestRight, (dp(91) + largeOffset).toFloat(), bestPaint)
            bestLapNumber?.let { number ->
                labelPaint.textAlign = Paint.Align.RIGHT; labelPaint.textSize = timingTextSize(10); labelPaint.color = Color.rgb(184, 223, 235)
                canvas.drawText("($number)", bestRight - bestPaint.measureText(bestText) - dp(8), (dp(88) + largeOffset).toFloat(), labelPaint)
            }

            val sectorHeaderTop = (dp(106) + largeOffset).toFloat()
            canvas.drawRect(pad, sectorHeaderTop, width - pad, sectorHeaderTop + dp(22), dark)
            labelPaint.textAlign = Paint.Align.LEFT; canvas.drawText("SECTOR", pad + dp(12), sectorHeaderTop + dp(17), labelPaint)
            labelPaint.textAlign = Paint.Align.RIGHT; canvas.drawText("TEMPO / DELTA", width - pad - dp(12), sectorHeaderTop + dp(17), labelPaint)
            drawClassicSectorRow(canvas, sectorHeaderTop + dp(24), "S1", sectorResults[1], Color.rgb(70, 205, 255), rowText, valueText, panel)
            drawClassicSectorRow(canvas, sectorHeaderTop + dp(63), "S2", sectorResults[2], Color.rgb(255, 185, 64), rowText, valueText, dark)

            val cardTop = sectorHeaderTop + dp(104)
            val cardBottom = cardTop + dp(if (largeOffset > 0) 65 else 68)
            val gap = dp(7).toFloat(); val mid = width / 2f
            canvas.drawRoundRect(pad, cardTop, mid - gap, cardBottom, dp(4).toFloat(), dp(4).toFloat(), dark)
            canvas.drawRoundRect(mid + gap, cardTop, width - pad, cardBottom, dp(4).toFloat(), dp(4).toFloat(), panel)
            canvas.drawRect(pad, cardTop, pad + dp(5), cardBottom, Paint().apply { color = Color.WHITE })
            canvas.drawRect(mid + gap, cardTop, mid + gap + dp(5), cardBottom, Paint().apply { color = green })
            labelPaint.textAlign = Paint.Align.CENTER; labelPaint.color = Color.rgb(112, 157, 174)
            lastPaint.textSize = timingTextSize(22); lastPaint.color = Color.rgb(238, 245, 247)
            canvas.drawText("LAST LAP", (pad + mid - gap) / 2f, cardTop + dp(17), labelPaint)
            canvas.drawText(last?.let(::formatTime) ?: "--:--.---", (pad + mid - gap) / 2f, cardBottom - dp(10), lastPaint)
            canvas.drawText("CURRENT LAP", (mid + gap + width - pad) / 2f, cardTop + dp(17), labelPaint)
            canvas.drawText(elapsed?.let(::formatTime) ?: "--:--.---", (mid + gap + width - pad) / 2f, cardBottom - dp(10), lastPaint)
            drawClassicFooter(canvas)
            canvas.drawRoundRect(dp(2).toFloat(), dp(2).toFloat(), width - dp(2).toFloat(), height - dp(2).toFloat(), dp(9).toFloat(), dp(9).toFloat(), hudStrokePaint)
            return true
        }

        private fun drawClassicSectorRow(canvas: Canvas, top: Float, name: String, result: SectorDisplay?, accent: Int, namePaint: Paint, valuePaint: Paint, background: Paint) {
            val pad = dp(12).toFloat()
            canvas.drawRect(pad, top, width - pad, top + dp(35), background)
            canvas.drawRect(pad, top, pad + dp(5), top + dp(35), Paint().apply { color = accent })
            namePaint.textAlign = Paint.Align.LEFT; canvas.drawText(name, pad + dp(17), top + dp(25), namePaint)
            valuePaint.color = accent
            val value = result?.let { "${formatTime(it.elapsedMs)} ${it.deltaMs?.let(::formatDelta) ?: ""}".trim() } ?: "--:--.---"
            canvas.drawText(value, width - pad - dp(14), top + dp(26), valuePaint)
        }

        private fun drawClassicFooter(canvas: Canvas) {
            val right = width - dp(14).toFloat(); val bottom = height - dp(14).toFloat(); val left = right - dp(118); val top = bottom - dp(38)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(7, 31, 41) }
            canvas.drawRoundRect(left, top, right, bottom, dp(5).toFloat(), dp(5).toFloat(), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat(); paint.color = Color.rgb(72, 205, 255)
            canvas.drawRoundRect(left, top, right, bottom, dp(5).toFloat(), dp(5).toFloat(), paint)
            labelPaint.color = Color.rgb(72, 205, 255); labelPaint.textAlign = Paint.Align.CENTER; canvas.drawText("SESSIONI", (left + right) / 2f, top + dp(24), labelPaint)
            val tracksRight = left - dp(7); val tracksLeft = tracksRight - dp(84)
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(7, 31, 41); canvas.drawRoundRect(tracksLeft, top, tracksRight, bottom, dp(5).toFloat(), dp(5).toFloat(), paint)
            paint.style = Paint.Style.STROKE; paint.color = Color.rgb(174, 119, 255); canvas.drawRoundRect(tracksLeft, top, tracksRight, bottom, dp(5).toFloat(), dp(5).toFloat(), paint)
            labelPaint.color = Color.rgb(174, 119, 255); canvas.drawText("PISTE", (tracksLeft + tracksRight) / 2f, top + dp(24), labelPaint)
        }

        private fun drawSectorDashboardRow(
            canvas: Canvas, top: Float, height: Float, number: Int, result: SectorDisplay?, left: Float, labelRight: Float,
            green: Int, red: Int, gray: Paint, black: Paint, outline: Paint, text: Paint
        ) {
            canvas.drawRect(left, top, width - left, top + height, black)
            canvas.drawRect(left, top, labelRight, top + height, gray)
            canvas.drawRect(left, top, width - left, top + height, outline)
            text.textAlign = Paint.Align.CENTER; text.textSize = timingTextSize(18); text.color = Color.WHITE
            canvas.drawText("S$number", (left + labelRight) / 2f, top + height * .64f, text)
            val delta = result?.deltaMs
            text.textSize = timingTextSize(17); text.color = when { delta == null -> Color.LTGRAY; delta < 0 -> green; else -> red }
            canvas.drawText(delta?.let(::formatDelta) ?: "--.--", labelRight + (width - left - labelRight) * .27f, top + height * .64f, text)
            text.textAlign = Paint.Align.RIGHT; text.textSize = timingTextSize(12); text.color = Color.WHITE
            canvas.drawText(result?.elapsedMs?.let(::formatTime) ?: "--:--.---", width - left - dp(8), top + height * .43f, text)
            text.color = green
            val reference = result?.let { display -> display.deltaMs?.let { display.elapsedMs - it } }
            canvas.drawText(reference?.let(::formatTime) ?: "--:--.---", width - left - dp(8), top + height * .79f, text)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; lastX = event.x; lastY = event.y; dragged = false
                }
                MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress) {
                    val dx = event.x - lastX; val dy = event.y - lastY
                    if (abs(event.x - downX) > dp(8) || abs(event.y - downY) > dp(8)) dragged = true
                    panX += dx; panY += dy; lastX = event.x; lastY = event.y; invalidate()
                }
                MotionEvent.ACTION_UP -> if (!dragged && !scaleDetector.isInProgress) {
                    if (isTracksButton(event.x, event.y)) showTrackList()
                    else if (isSessionsButton(event.x, event.y)) showSessionHistory()
                    else if (track.isNotEmpty()) onTrackTapped?.invoke(pointAt(event.x, event.y))
                }
            }
            return true
        }

        private fun isSessionsButton(x: Float, y: Float): Boolean =
            x >= width - dp(132) && x <= width - dp(14) && y >= height - dp(52) && y <= height - dp(14)

        private fun isTracksButton(x: Float, y: Float): Boolean =
            x >= width - dp(223) && x <= width - dp(139) && y >= height - dp(52) && y <= height - dp(14)

        private fun ensurePath() {
            if (path != null && cachedWidth == width && cachedHeight == height) return
            cachedWidth = width; cachedHeight = height
            val built = Path()
            track.forEachIndexed { index, point ->
                if (index == 0) built.moveTo(x(point), y(point)) else built.lineTo(x(point), y(point))
            }
            path = built
        }

        private fun x(point: TrackPoint): Float {
            val base = baseX(point)
            return ((base - width / 2.0) * zoom + width / 2.0 + panX).toFloat()
        }
        private fun baseX(point: TrackPoint): Double {
            val scale = scale()
            return (point.lon - minLon) * scale + width * .07
        }
        private fun y(point: TrackPoint): Float {
            val base = baseY(point)
            return ((base - height / 2.0) * zoom + height / 2.0 + panY).toFloat()
        }
        private fun baseY(point: TrackPoint): Double {
            val scale = scale()
            return height * .96 - (point.lat - minLat) * scale
        }
        private fun pointAt(screenX: Float, screenY: Float): TrackPoint {
            val baseX = (screenX - panX - width / 2.0) / zoom + width / 2.0
            val baseY = (screenY - panY - height / 2.0) / zoom + height / 2.0
            val scale = scale()
            return TrackPoint(minLat + (height * .96 - baseY) / scale, minLon + (baseX - width * .07) / scale)
        }
        private fun scale(): Double {
            val sx = width / (maxLon - minLon).coerceAtLeast(0.000001)
            val sy = height / (maxLat - minLat).coerceAtLeast(0.000001)
            return minOf(sx, sy) * .84
        }
        private fun formatDelta(ms: Long) = "%+.2f".format(Locale.US, ms / 1_000.0)
    }

    companion object {
        private const val LOCATION_REQUEST = 10
        private const val GPX_REQUEST = 20
    }
}
