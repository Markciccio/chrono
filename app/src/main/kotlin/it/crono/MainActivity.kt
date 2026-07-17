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

    private lateinit var locationManager: LocationManager
    private lateinit var dashboard: RaceView
    private lateinit var trackMap: TrackMapView
    private lateinit var status: TextView
    private var tts: TextToSpeech? = null

    private var route = emptyList<TrackPoint>()
    private var latestFix: GpsSample? = null
    private var previousFix: GpsSample? = null
    private var running = false
    private var paused = false
    private var pausedLapElapsedMs: Long? = null
    private var lowSpeedSinceMs: Long? = null
    private var screenLockOverlay: View? = null
    private var activeSavedTrack: SavedTrack? = null
    private var lastSuggestedTrackIds = emptySet<String>()
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
        text = label
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        minHeight = dp(44)
        val accent = when (label) {
            "AVVIA" -> Color.rgb(24, 213, 184)
            "RESET" -> Color.rgb(255, 91, 100)
            "SPOSTA TRAGUARDO" -> Color.rgb(174, 119, 255)
            else -> Color.rgb(255, 185, 64)
        }
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
        var downX = 0f
        var downY = 0f
        screenLockOverlay = View(this).apply {
            // Nearly transparent on purpose: numbers stay readable, but no control receives taps.
            setBackgroundColor(Color.argb(8, 0, 0, 0))
            isClickable = true
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; true }
                    MotionEvent.ACTION_UP -> {
                        if (event.x - downX > dp(150) && abs(event.y - downY) < dp(90)) unlockScreen()
                        true
                    }
                    else -> true
                }
            }
        }
        addContentView(screenLockOverlay, ViewGroup.LayoutParams(-1, -1))
        status.text = "🔒 SCHERMO BLOCCATO · scorri verso destra o premi un tasto volume"
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
        val dialog = AlertDialog.Builder(this).setTitle("Elenco piste").setView(scroll).setNegativeButton("CHIUDI", null).create()
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
        val sectorIndexes = track.sectors.map { Geo.nearestRouteIndex(lineCenter(it), points) }.toMutableList()
        var selection = -1 // -1 finish; otherwise index in sectorIndexes
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(Color.rgb(3, 9, 14)) }
        lateinit var map: TrackMapView
        lateinit var selectedLabel: TextView
        lateinit var addSectorButton: Button

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
            selectedLabel.text = "${selectedText()} · centro rosso · − / + spostano di 20 m lungo la traccia"
            addSectorButton.text = if (sectorIndexes.size >= 3) "LIMITE: 3 SETTORI" else "AGGIUNGI SETTORE (${sectorIndexes.size}/3)"
            addSectorButton.isEnabled = sectorIndexes.size < 3
        }
        fun chooseMarker() {
            val choices = mutableListOf("TRAGUARDO")
            sectorIndexes.indices.forEach { choices += "SETTORE ${it + 1}" }
            AlertDialog.Builder(this).setTitle("Seleziona elemento da spostare")
                .setItems(choices.toTypedArray()) { _, which -> selection = which - 1; redraw() }.show()
        }
        fun shiftAlongTrack(index: Int, direction: Int, targetDistanceM: Double = 20.0): Int {
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
            if (sectorIndexes.size >= 3) {
                status.text = "Una pista può avere al massimo 3 settori"
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
        selectedLabel = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(184, 223, 235)); setPadding(0, dp(14), 0, dp(10)) }
        info.addView(selectedLabel)
        info.addView(actionButton("SELEZIONA") { chooseMarker() }, LinearLayout.LayoutParams(-1, dp(42)))
        val moveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        moveRow.addView(actionButton("− 20 m") { shiftMarker(-1) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(3) })
        moveRow.addView(actionButton("+ 20 m") { shiftMarker(1) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(3) })
        info.addView(moveRow)
        addSectorButton = actionButton("AGGIUNGI SETTORE") { addSector() }
        info.addView(addSectorButton, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
        info.addView(actionButton("RIMUOVI SETTORE") {
            if (selection >= 0) { sectorIndexes.removeAt(selection); selection = -1; redraw() }
            else status.text = "Seleziona un settore da rimuovere"
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
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
        root.addView(info, LinearLayout.LayoutParams(0, -1, .38f))
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
        val choices = mutableListOf("Aggiungi intermedio qui")
        sectorReferences.forEach { reference -> choices += "Sposta S${reference.number} qui" }
        AlertDialog.Builder(this)
            .setTitle("Intermedi · posizione GPS attuale")
            .setItems(choices.toTypedArray()) { _, which ->
                val point = TrackPoint(fix.lat, fix.lon)
                if (which == 0) addSector(point) else moveSector(sectorReferences[which - 1].number, point)
            }
            .show()
    }

    /** Adds a sector at the current position; it is safe to call while a lap is running. */
    private fun addSector(center: TrackPoint) {
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
            resetSession(keepTrack = true)
            running = true
            paused = false
            pauseButton?.text = "PAUSA"
            startButton?.text = "FERMA"
            startGps()
            if (timing.line == null) {
                status.text = "Registrazione attiva · primo giro in apprendimento"
                speak("Registrazione avviata. Primo giro in apprendimento", flush = true)
            } else {
                status.text = "Registrazione attiva · attraversa il traguardo per armare il giro"
                speak("Registrazione avviata. Attraversa il traguardo per armare il giro", flush = true)
            }
        } else {
            finishAndAnalyzeSession()
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
        if (!running) offerNearbySavedTracks(sample)
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
            running && !paused
        )
        trackMap.setFix(latestFix)
    }

    private fun offerNearbySavedTracks(sample: GpsSample) {
        val nearby = trackStore.nearby(TrackPoint(sample.lat, sample.lon))
        val ids = nearby.map { it.id }.toSet()
        if (ids.isEmpty()) {
            lastSuggestedTrackIds = emptySet()
            return
        }
        if (ids == lastSuggestedTrackIds) return
        lastSuggestedTrackIds = ids
        val labels = nearby.map { "${it.name} · ${formatTime(it.lap.durationMs)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Piste nelle vicinanze")
            .setMessage("Scegli una pista salvata per caricare traguardo e settori.")
            .setItems(labels) { _, which -> activateSavedTrack(nearby[which]) }
            .setNegativeButton("NON ORA", null)
            .show()
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
        timing.sectors = track.sectors
        sectorReferences = track.sectors.mapIndexed { index, line ->
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
                val deltaPart = delta?.let { ". ${spokenDelta(it)}" } ?: ""
                if (voiceBriefingMode != VoiceBriefingMode.LAPS_ONLY) {
                    val recordPart = if (isSegmentRecord) ". ${sectorRecordMessage(event.number, segmentMs)}" else ""
                    val sectorCall = if (isSegmentRecord) "Fucsia. Settore ${event.number}" else "Settore ${event.number}"
                    speak("$sectorCall. ${spokenTime(event.elapsedMs)}$deltaPart$recordPart", flush = true)
                }
                // Sector calls always win over predictive-delta calls, including the samples just after it.
                deltaAnnouncementsSuppressedUntilMs = (latestFix?.timeMs ?: System.currentTimeMillis()) + 3_000L
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
                speak(message, flush = true)
                val sectorNotice = if (defaultSectorsAdded) " · S1 e S2 automatici pronti" else ""
                status.text = if (isBest) "Giro ${event.lap.number}: miglior giro ${formatTime(event.lap.durationMs)}$sectorNotice" else "Giro ${event.lap.number}: ${formatTime(event.lap.durationMs)}$sectorNotice"
                predictor?.reset()
                dashboard.clearSectorResult()
                lastDeltaAnnouncementElapsedMs = Long.MIN_VALUE
                lastAnnouncedDeltaMs = null
                previousLiveDeltaMs = null
                // Do not let a new-lap delta interrupt or follow the lap result immediately.
                deltaAnnouncementsSuppressedUntilMs = (latestFix?.timeMs ?: System.currentTimeMillis()) + 5_000L
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
            status.text = "🔒 SCHERMO BLOCCATO · scorri verso destra o premi un tasto volume"
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
        if (simulator != null || sessionSamples.size < 2) return null
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
        status.text = if (saved != null) "Sessione salvata · analisi disponibile" else "Registrazione fermata · nessun dato sufficiente"
        speak(if (saved != null) "Registrazione fermata. Sessione salvata" else "Registrazione fermata", flush = true)
        saved?.let(::resolveSessionLocationName)
        saved?.let(::showSessionAnalysis)
    }

    private fun saveSessionRecord(simulated: Boolean, gpxName: String?): SavedSession? {
        val first = sessionSamples.firstOrNull() ?: return null
        val last = sessionSamples.lastOrNull() ?: return null
        if (sessionSamples.size < 2) return null
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
                sectorLines = timing.sectors,
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
            .setNegativeButton("CHIUDI", null)
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

    private fun showLapReview(session: SavedSession, lap: RecordedLap, onSessionUpdated: (SavedSession) -> Unit = {}) {
        if (lap.samples.size < 3) {
            AlertDialog.Builder(this).setTitle("Mappa giro").setMessage("Questo giro non contiene abbastanza punti GPS per la mappa.").setPositiveButton("OK", null).show()
            return
        }
        val geometry = geometryForSession(session, lap)
        val finish = geometry.finish
        val sectors = geometry.sectors
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
        val summary = speedSummary(lap)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(7, 24, 33))
        }
        info.addView(TextView(this).apply {
            text = "LAP ${lap.number}\n${formatTime(lap.durationMs)}"
            textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        })
        info.addView(TextView(this).apply {
            text = "\nV MAX  ${summary.maxKmh} km/h\nV MIN  ${summary.minKmh} km/h\n\nSULLA MAPPA\nF = fine frenata · A = fine accelerazione\n\nFRENATA  ${summary.brakingMinKmh} km/h\nUSCITA CURVA  ${summary.accelerationMaxKmh} km/h"
            textSize = 16f; setTextColor(Color.rgb(184, 223, 235))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        info.addView(actionButton("SALVA COME PISTA") {
            saveLapAsTrack(session, lap, finish, sectors)
        }, LinearLayout.LayoutParams(-1, dp(44)))
        info.addView(actionButton(if (lap.valid) "GIRO NON VALIDO" else "RIPRISTINA GIRO") {
            val updated = session.copy(laps = session.laps.map { candidate ->
                if (candidate.number == lap.number) candidate.copy(valid = !candidate.valid) else candidate
            })
            sessionStore.save(updated)
            status.text = if (lap.valid) "Lap ${lap.number} escluso da best e statistiche" else "Lap ${lap.number} ripristinato"
            dialog.dismiss()
            onSessionUpdated(updated)
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        info.addView(actionButton("CHIUDI") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        root.addView(map, LinearLayout.LayoutParams(0, -1, .58f).apply { rightMargin = dp(6) })
        root.addView(info, LinearLayout.LayoutParams(0, -1, .42f))
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setLayout(-1, -1)
            setBackgroundDrawable(ColorDrawable(Color.rgb(3, 9, 14)))
            decorView.systemUiVisibility = window.decorView.systemUiVisibility
        }
    }

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
        val radius = 3
        for (index in radius until smooth.size - radius) {
            val before = smooth.subList(index - radius, index)
            val after = smooth.subList(index + 1, index + radius + 1)
            val value = smooth[index]
            val isMinimum = value <= before.minOrNull()!! && value <= after.minOrNull()!! &&
                before.maxOrNull()!! - value >= 4f && after.maxOrNull()!! - value >= 4f
            val isMaximum = value >= before.maxOrNull()!! && value >= after.maxOrNull()!! &&
                value - before.minOrNull()!! >= 4f && value - after.minOrNull()!! >= 4f
            if (isMinimum) candidates += Candidate(index, braking = true)
            if (isMaximum) candidates += Candidate(index, braking = false)
        }
        // A flat GPS plateau can produce neighbouring extrema: retain one marker per 2.5 s.
        val selected = mutableListOf<Candidate>()
        candidates.forEach { candidate ->
            val previous = selected.lastOrNull()
            if (previous == null || samples[candidate.index].timeMs - samples[previous.index].timeMs >= 2_500L) {
                selected += candidate
            } else {
                val previousValue = smooth[previous.index]
                val currentValue = smooth[candidate.index]
                val replace = if (candidate.braking) currentValue < previousValue else currentValue > previousValue
                if (replace) selected[selected.lastIndex] = candidate
            }
        }
        return selected.map { candidate ->
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
        if (saved != null) return SessionGeometry(saved.finishLine, saved.sectors, fromSavedTrack = true)
        return SessionGeometry(
            session.timingLine ?: lineFromLap(fallbackLap),
            session.sectorLines.ifEmpty { deriveSectors(fallbackLap.samples).map { it.line } },
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
            root.addView(map, LinearLayout.LayoutParams(-1, 0, .34f).apply { bottomMargin = dp(6) })
            root.addView(analysis, LinearLayout.LayoutParams(-1, 0, .66f))
        } else {
            root.addView(map, LinearLayout.LayoutParams(0, -1, .34f).apply { rightMargin = dp(6) })
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

    /** Full-screen timing table inspired by a race-control/F1 session monitor. */
    private inner class SessionAnalysisView(
        private val session: SavedSession,
        private val onClose: () -> Unit,
        private val onSessionUpdated: (SavedSession) -> Unit
    ) : View(this@MainActivity) {
        private val validLaps = session.laps.filter { it.valid }
        private val sectorCount = maxOf(session.sectorLines.size, validLaps.maxOfOrNull { it.sectorElapsedMs.size } ?: 0).coerceIn(0, 3)
        private val best = validLaps.minOfOrNull { it.durationMs }
        private val average = validLaps.takeIf { it.isNotEmpty() }?.map { it.durationMs }?.average()?.toLong()
        /** The ideal lap is the sum of the best independently recorded segments.  A partial
         * lap must not hide it: only the segment that lacks a reading is ignored. */
        private val ideal = if (sectorCount > 0) {
            val segments = (0..sectorCount).mapNotNull { segment ->
                validLaps.mapNotNull { lap ->
                    when {
                        segment < sectorCount && lap.sectorElapsedMs.size > segment -> {
                            val start = if (segment == 0) 0L else lap.sectorElapsedMs[segment - 1]
                            lap.sectorElapsedMs[segment] - start
                        }
                        segment == sectorCount && lap.sectorElapsedMs.size >= sectorCount ->
                            lap.durationMs - lap.sectorElapsedMs[segment - 1]
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
            val cardWidth = (width - dp(24)) / 3f
            drawMetric(canvas, pad, cardTop, cardWidth, cardBottom, "BEST LAP", best?.let(::formatTime) ?: "--:--.---", Color.rgb(24, 213, 184))
            drawMetric(canvas, pad + cardWidth + dp(3), cardTop, cardWidth, cardBottom, "MEDIA", average?.let(::formatTime) ?: "--:--.---", Color.rgb(255, 185, 64))
            drawMetric(canvas, pad + (cardWidth + dp(3)) * 2, cardTop, cardWidth, cardBottom, "IDEAL", ideal?.let(::formatTime) ?: "--:--.---", Color.rgb(174, 119, 255))

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
                repeat(sectorCount) { sector ->
                    canvas.drawText(lap.sectorElapsedMs.getOrNull(sector)?.let(::formatTime) ?: "—", columns[3 + sector], y, rowPaint)
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
        dashboard.setLiveData(latestFix, null, null, 1, null, null, false)
        if (!keepTrack) status.text = "Cronometro azzerato"
    }

    private fun speak(text: String, flush: Boolean) {
        if (!voiceEnabled) return
        tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "crono-${System.nanoTime()}")
    }

    private fun formatTime(ms: Long) = "%d:%02d.%03d".format(Locale.US, ms / 60_000, (ms / 1_000) % 60, ms % 1_000)

    private fun spokenTime(ms: Long): String {
        return spokenStopwatch(ms)
    }

    private fun spokenDelta(ms: Long): String {
        val state = if (ms < 0) "Avanti di" else "Sei dietro di"
        return "$state ${spokenStopwatch(abs(ms))}"
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
                "$minuteText, $seconds e $centsText"
            }
            seconds == 0L -> "zero punto $centsText"
            else -> "$seconds e $centsText"
        }
    }

    private fun engineerDeltaMessage(ms: Long, elapsedMs: Long): String {
        val amount = spokenDelta(ms)
            .removePrefix("Avanti di ")
            .removePrefix("Sei dietro di ")
        val variants = if (ms < 0) listOf(
            "Delta negativo di $amount",
            "Guadagno di $amount",
            "Stai recuperando $amount",
            "Best lap battuto di $amount",
            "Sei più rapido di $amount",
            "Hai preso $amount al best lap",
            "Passo migliore di $amount",
            "Vantaggio di $amount",
            "Stai girando $amount più forte",
            "Hai limato $amount",
            "Tempo in verde: $amount",
            "Miglioramento di $amount"
        ) else listOf(
            "Delta positivo di $amount",
            "Stai peggiorando di $amount",
            "Hai perso $amount",
            "Ritardo di $amount",
            "Sei più lento di $amount",
            "Il best lap si allontana di $amount",
            "Perdi $amount sul giro migliore",
            "Tempo in rosso: $amount",
            "Scarto di $amount",
            "Sei sopra di $amount",
            "Calo di $amount",
            "Manca $amount al best lap"
        )
        val index = ((abs(ms) / 10 + elapsedMs / 1_000) % variants.size).toInt()
        return variants[index]
    }

    private fun recoveryDeltaMessage(currentMs: Long, previousMs: Long, elapsedMs: Long): String {
        val recovered = spokenDelta(previousMs - currentMs).removePrefix("Sei dietro di ")
        val current = formatDelta(currentMs)
        val variants = listOf(
            "Delta $current, recuperi $recovered",
            "Stai riducendo il ritardo di $recovered. Delta $current",
            "Recupero di $recovered, ora delta $current",
            "Hai ripreso $recovered. Delta positivo $current",
            "Il delta migliora di $recovered, resta $current",
            "Riduci lo scarto di $recovered. Delta $current",
            "Buon recupero: $recovered. Delta $current",
            "Stai tornando verso il best lap: $recovered recuperati",
            "Recuperi $recovered sul giro migliore",
            "Delta in calo di $recovered, ora $current"
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
        private val sectorTexts = mutableMapOf<Int, String>()
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
            lastLap: Long?, bestLap: Long?, isRecording: Boolean
        ) {
            fix = value; elapsed = lapElapsed; delta = valueDelta; lapNumber = currentLapNumber; last = lastLap; best = bestLap; recording = isRecording; invalidate()
        }

        fun setLiveTimingScale(scale: Float) {
            liveTimingScale = scale
            invalidate()
        }

        private fun timingTextSize(baseDp: Int) = dp(baseDp).toFloat() * liveTimingScale

        fun setSectorResult(number: Int, elapsedMs: Long, deltaMs: Long?) {
            sectorTexts[number] = "S$number  ${formatTime(elapsedMs)}${deltaMs?.let { " ${formatDelta(it)}" } ?: ""}"
            invalidate()
        }

        fun clearSectorResult() { sectorTexts.clear(); invalidate() }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(5, 16, 23))
            val pad = dp(12).toFloat()
            val fullWidth = width - pad * 2
            val red = Color.rgb(238, 50, 62)
            val green = Color.rgb(69, 223, 123)
            val yellow = Color.rgb(247, 219, 74)
            val deltaColor = when { delta == null -> Color.LTGRAY; delta!! < 0 -> green; else -> red }
            val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 28, 39) }
            val darkPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 19, 29) }
            val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red }
            // Large live timing needs extra vertical room: otherwise the larger delta ascenders
            // collide with the label above it.
            val largeTextOffset = if (liveTimingScale > 1f) {
                // Extra space grows more slowly than glyph size, keeping the final lap cards
                // safely inside the fixed-height HUD even at the new larger setting.
                dp((10f + (liveTimingScale - 1f) * 45f).toInt())
            } else 0
            val rowText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = timingTextSize(17); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val valueText = Paint(rowText).apply { textAlign = Paint.Align.RIGHT; textSize = timingTextSize(23) }

            canvas.drawRect(0f, 0f, width.toFloat(), dp(28).toFloat(), darkPanelPaint)
            canvas.drawRect(0f, 0f, dp(6).toFloat(), dp(28).toFloat(), accentPaint)
            labelPaint.color = Color.rgb(218, 229, 235)
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("PIT ENGINEER  //  LIVE TIMING", pad + dp(8), dp(20).toFloat(), labelPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("GIRO $lapNumber", width - pad, dp(20).toFloat(), labelPaint)
            if (recording) {
                labelPaint.color = Color.rgb(255, 82, 92)
                labelPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("● REC", width * .66f, dp(20).toFloat(), labelPaint)
                labelPaint.color = Color.rgb(112, 157, 174)
            }

            canvas.drawRoundRect(pad, dp(38).toFloat(), width - pad, (dp(98) + largeTextOffset).toFloat(), dp(4).toFloat(), dp(4).toFloat(), panelPaint)
            canvas.drawRect(pad, dp(38).toFloat(), pad + dp(6), (dp(98) + largeTextOffset).toFloat(), accentPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("DELTA vs BEST LAP", pad + dp(18), (if (largeTextOffset > 0) dp(55) else dp(58)).toFloat(), labelPaint)
            val deltaText = delta?.let { if (it < 0) "▲ ${formatDelta(it)}" else "▼ ${formatDelta(it)}" } ?: "± 0.00"
            deltaPaint.color = deltaColor
            deltaPaint.textAlign = Paint.Align.LEFT
            deltaPaint.textSize = timingTextSize(42)
            canvas.drawText(deltaText, pad + dp(18), (dp(91) + largeTextOffset).toFloat(), deltaPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("BEST LAP", width - pad - dp(18), (if (largeTextOffset > 0) dp(55) else dp(58)).toFloat(), labelPaint)
            bestPaint.color = Color.rgb(69, 223, 123)
            bestPaint.textAlign = Paint.Align.RIGHT
            bestPaint.textSize = timingTextSize(36)
            canvas.drawText(best?.let(::formatTime) ?: "--:--.---", width - pad - dp(18), (dp(91) + largeTextOffset).toFloat(), bestPaint)

            canvas.drawRect(pad, (dp(106) + largeTextOffset).toFloat(), width - pad, (dp(128) + largeTextOffset).toFloat(), darkPanelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("SECTOR", pad + dp(12), (dp(123) + largeTextOffset).toFloat(), labelPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("TEMPO / DELTA", width - pad - dp(12), (dp(123) + largeTextOffset).toFloat(), labelPaint)
            drawTimingRow(canvas, (dp(130) + largeTextOffset).toFloat(), "S1", sectorTexts[1] ?: "--:--.---", Color.rgb(70, 205, 255), rowText, valueText, panelPaint)
            drawTimingRow(canvas, (dp(169) + largeTextOffset).toFloat(), "S2", sectorTexts[2] ?: "--:--.---", Color.rgb(255, 185, 64), rowText, valueText, darkPanelPaint)

            // Extra vertical space keeps label and large time separate on compact Pixel screens.
            val cardTop = (dp(210) + largeTextOffset).toFloat()
            val cardBottom = cardTop + dp(if (largeTextOffset > 0) 65 else 68)
            val gap = dp(7).toFloat()
            val mid = width / 2f
            canvas.drawRoundRect(pad, cardTop, mid - gap, cardBottom, dp(4).toFloat(), dp(4).toFloat(), darkPanelPaint)
            canvas.drawRoundRect(mid + gap, cardTop, width - pad, cardBottom, dp(4).toFloat(), dp(4).toFloat(), panelPaint)
            canvas.drawRect(pad, cardTop, pad + dp(5), cardBottom, Paint().apply { color = Color.rgb(238, 245, 247) })
            canvas.drawRect(mid + gap, cardTop, mid + gap + dp(5), cardBottom, Paint().apply { color = green })
            labelPaint.textAlign = Paint.Align.CENTER
            lastPaint.textSize = timingTextSize(22)
            canvas.drawText("LAST LAP", (pad + mid - gap) / 2f, cardTop + dp(17), labelPaint)
            canvas.drawText(last?.let(::formatTime) ?: "--:--.---", (pad + mid - gap) / 2f, cardBottom - dp(10), lastPaint)
            canvas.drawText("CURRENT LAP", (mid + gap + width - pad) / 2f, cardTop + dp(17), labelPaint)
            val currentTime = elapsed?.let(::formatTime) ?: "--:--.---"
            lastPaint.color = Color.rgb(238, 245, 247)
            canvas.drawText(currentTime, (mid + gap + width - pad) / 2f, cardBottom - dp(10), lastPaint)

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

            primaryPaint.textAlign = Paint.Align.CENTER
            primaryPaint.textSize = timingTextSize(48)
            deltaPaint.textAlign = Paint.Align.CENTER
            deltaPaint.textSize = timingTextSize(25)
            bestPaint.textAlign = Paint.Align.CENTER
            bestPaint.textSize = timingTextSize(22)
            labelPaint.color = Color.rgb(112, 157, 174)
            labelPaint.textAlign = Paint.Align.CENTER
            // Draw the outer frame last: the top live-timing bar otherwise paints over its top edge.
            canvas.drawRoundRect(dp(2).toFloat(), dp(2).toFloat(), width - dp(2).toFloat(), height - dp(2).toFloat(), dp(9).toFloat(), dp(9).toFloat(), hudStrokePaint)
        }

        private fun drawTimingRow(
            canvas: Canvas, top: Float, name: String, value: String, accent: Int,
            namePaint: Paint, valuePaint: Paint, background: Paint
        ) {
            val pad = dp(12).toFloat()
            canvas.drawRect(pad, top, width - pad, top + dp(35), background)
            canvas.drawRect(pad, top, pad + dp(5), top + dp(35), Paint().apply { color = accent })
            namePaint.textAlign = Paint.Align.LEFT
            canvas.drawText(name, pad + dp(17), top + dp(25), namePaint)
            valuePaint.color = accent
            canvas.drawText(value, width - pad - dp(14), top + dp(26), valuePaint)
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
