package it.crono

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
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
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.io.OutputStreamWriter
import kotlin.math.abs

/** Track-day focused MVP: big live data, robust timing-line crossings and voice feedback. */
class MainActivity : Activity(), LocationListener, TextToSpeech.OnInitListener {
    private enum class VoiceBriefingMode { ALL, SECTORS_AND_LAPS, LAPS_ONLY }

    private lateinit var locationManager: LocationManager
    private lateinit var dashboard: RaceView
    private lateinit var trackMap: TrackMapView
    private lateinit var status: TextView
    private var tts: TextToSpeech? = null

    private var route = emptyList<TrackPoint>()
    private var latestFix: GpsSample? = null
    private var previousFix: GpsSample? = null
    private var running = false
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
    private var voiceAlertIntervalMs = 10_000L
    private var voiceBriefingMode = VoiceBriefingMode.ALL
    private val simulationHandler = Handler(Looper.getMainLooper())
    private var simulator: DebugGpsSimulator? = null
    private var testButton: Button? = null
    private var startButton: Button? = null
    private lateinit var sessionStore: SessionStore
    private val recordedLaps = mutableListOf<RecordedLap>()
    private val currentSectorTimes = linkedMapOf<Int, Long>()
    /** Best duration for each individual segment, independent from the best full lap. */
    private val bestSectorSegmentMs = mutableMapOf<Int, Long>()
    private val simulationTick = object : Runnable {
        override fun run() {
            val activeSimulator = simulator ?: return
            processSample(activeSimulator.next())
            simulationHandler.postDelayed(this, DebugGpsSimulator.STEP_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sessionStore = SessionStore(this)
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
        dashboard = RaceView().apply {
            onTrackTapped = ::handleTrackTap
        }
        trackMap = TrackMapView(this, ::handleTrackTap)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(optionsButton, LinearLayout.LayoutParams(dp(58), dp(42)))
            addView(status, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(closeButton, LinearLayout.LayoutParams(dp(48), dp(42)).apply { setMargins(dp(4), 0, 0, 0) })
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
        control(firstControlRow, "TRAGUARDO", 1f) { setTimingLineAtCurrentFix() }
        control(secondControlRow, "INTERMEDIO", 1f) { showMoveSectorMenu() }
        testButton = actionButton("TEST GPS") { toggleSimulation() }
        secondControlRow.addView(testButton, controlParams(1f))
        controls.addView(firstControlRow, LinearLayout.LayoutParams(-1, dp(39)))
        controls.addView(secondControlRow, LinearLayout.LayoutParams(-1, dp(39)))

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mapColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(trackMap, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(-1, dp(80)))
        }
        content.addView(mapColumn, LinearLayout.LayoutParams(0, -1, .35f).apply { setMargins(0, dp(6), dp(5), 0) })
        content.addView(dashboard, LinearLayout.LayoutParams(0, -1, .65f).apply { setMargins(dp(5), dp(6), 0, 0) })
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

    private fun showAdvancedOptions(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(if (voiceEnabled) "Voce: attiva" else "Voce: disattivata")
            menu.add("Avvisi di pista")
            menu.add("Frequenza avvisi vocali")
            menu.add("Inquadra tutta la traccia")
            menu.add("Storico sessioni")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Voce: attiva", "Voce: disattivata" -> {
                        voiceEnabled = !voiceEnabled
                        status.text = if (voiceEnabled) "Messaggi vocali attivati" else "Messaggi vocali disattivati"
                        if (voiceEnabled) speak("Messaggi vocali attivati", flush = true)
                    }
                    "Avvisi di pista" -> showVoiceBriefingMenu()
                    "Frequenza avvisi vocali" -> showVoiceFrequencyMenu()
                    "Inquadra tutta la traccia" -> {
                        trackMap.fitEntireTrack()
                        status.text = "Mappa adattata alla traccia"
                    }
                    "Storico sessioni" -> showSessionHistory()
                }
                true
            }
            show()
        }
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
        route = points
        timing.line = null
        resetSession(keepTrack = true)
        dashboard.setTrack(points)
        trackMap.setTrack(points)
        trackMap.setTimingLine(null)
        status.text = "$name caricato come riferimento visivo"
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
        val nearestSector = timing.sectors.indices.minByOrNull { index ->
            val line = timing.sectors[index]
            Geo.distanceM(tapped.lat, tapped.lon, (line.pointA.lat + line.pointB.lat) / 2.0, (line.pointA.lon + line.pointB.lon) / 2.0)
        }
        val sectorDistance = nearestSector?.let { index ->
            val line = timing.sectors[index]
            Geo.distanceM(tapped.lat, tapped.lon, (line.pointA.lat + line.pointB.lat) / 2.0, (line.pointA.lon + line.pointB.lon) / 2.0)
        } ?: Double.MAX_VALUE
        if (nearestSector != null && sectorDistance <= 50.0) {
            moveSector(nearestSector + 1, tapped)
        } else if (!running) {
            setTimingLineAtMapPoint(tapped)
        } else {
            status.text = "Tocca vicino a un intermedio per spostarlo"
        }
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
        timing.sectors = sectorReferences.map { it.line }
        trackMap.setSectors(timing.sectors)
        status.text = "Intermedio S$number aggiunto${if (running) " · attivo da questo giro" else ""}"
        speak("Intermedio $number aggiunto", flush = true)
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
        timing.sectors = sectorReferences.map { it.line }
        trackMap.setSectors(timing.sectors)
        status.text = "Intermedio S$number spostato · attivo subito"
        speak("Intermedio $number spostato", flush = true)
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
            startButton?.text = "REGISTRAZIONE AVVIATA"
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
        if (running) {
            liveRoute += TrackPoint(sample.lat, sample.lon)
            sessionSamples += sample
            if (liveRoute.size % 3 == 0) {
                trackMap.setTrack(liveRoute)
                if (bestLap == null) trackMap.fitEntireTrack()
            }
            if (timing.line == null) {
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
            timing.currentLapStartMs?.let { latestFix!!.timeMs - it },
            liveDeltaMs,
            timing.currentLapNumber,
            lastLapMs,
            bestLap?.durationMs,
            running
        )
        trackMap.setFix(latestFix)
    }

    private fun toggleSimulation() {
        if (simulator != null) {
            status.text = "Test già attivo · premi REGISTRAZIONE AVVIATA per fermarlo"
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
        startButton?.text = "REGISTRAZIONE AVVIATA"
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
                status.text = "Giro armato · riferimento non ancora disponibile"
                speak("Cronometro armato", flush = true)
            }
            is TimingEvent.SectorCompleted -> {
                val previousElapsed = currentSectorTimes[event.number - 1] ?: 0L
                val segmentMs = (event.elapsedMs - previousElapsed).coerceAtLeast(0L)
                val previousBestSegment = bestSectorSegmentMs[event.number]
                val isSegmentRecord = previousBestSegment == null || segmentMs < previousBestSegment
                if (isSegmentRecord) bestSectorSegmentMs[event.number] = segmentMs
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
                    speak("Intermedio ${event.number}. ${spokenTime(event.elapsedMs)}$deltaPart$recordPart", flush = true)
                }
                // Sector calls always win over predictive-delta calls, including the samples just after it.
                deltaAnnouncementsSuppressedUntilMs = (latestFix?.timeMs ?: System.currentTimeMillis()) + 3_000L
                status.text = "Intermedio ${event.number}: ${formatTime(event.elapsedMs)}${delta?.let { " · ${formatDelta(it)}" } ?: ""}"
            }
            is TimingEvent.LapCompleted -> {
                lastLapMs = event.lap.durationMs
                val finalSegmentNumber = sectorReferences.size + 1
                val finalSegmentStart = currentSectorTimes[sectorReferences.size] ?: 0L
                val finalSegmentMs = (event.lap.durationMs - finalSegmentStart).coerceAtLeast(0L)
                val previousFinalBest = bestSectorSegmentMs[finalSegmentNumber]
                val isFinalSegmentRecord = sectorReferences.isNotEmpty() && (previousFinalBest == null || finalSegmentMs < previousFinalBest)
                if (isFinalSegmentRecord) bestSectorSegmentMs[finalSegmentNumber] = finalSegmentMs
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
                        // Announce the gap to the record on every completed lap, including a new best.
                        append(" ${spokenDelta(event.lap.durationMs - it.durationMs)} rispetto al record.")
                    } ?: append(" Riferimento impostato.")
                    if (isBest) append(" Miglior giro.")
                    else oldBest?.let {
                        if (event.lap.number - lastBestReminderLap >= 3) {
                            append(" Riferimento ${spokenTime(it.durationMs)}.")
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
        val fix = latestFix ?: return
        val lineText = if (timing.line == null) if (running) "ricerca giro" else "automatico" else "traguardo pronto"
        val runText = when {
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
        1 -> "S uno record. ${spokenTime(segmentMs)}"
        2 -> "Tratto S uno S due record. ${spokenTime(segmentMs)}"
        else -> "Tratto dopo S${number - 1} record. ${spokenTime(segmentMs)}"
    }

    private fun finalSectorRecordMessage(segmentMs: Long) = "Ultimo settore record. ${spokenTime(segmentMs)}"

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
        startButton?.text = "AVVIA"
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
                minSpeedMps = sessionSamples.minOfOrNull { it.speedMps }
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
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN)
        val labels = sessions.map { session ->
            val best = session.laps.minOfOrNull { it.durationMs }?.let(::formatTime) ?: "nessun giro"
            "${session.displayName} · best $best"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Storico sessioni")
            .setItems(labels) { _, index -> showSessionAnalysis(sessions[index]) }
            .setNegativeButton("CHIUDI", null)
            .show()
    }

    private fun showSessionAnalysis(session: SavedSession) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(SessionAnalysisView(session) { dialog.dismiss() })
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
        private val onClose: () -> Unit
    ) : View(this@MainActivity) {
        private val best = session.laps.minOfOrNull { it.durationMs }
        private val average = session.laps.takeIf { it.isNotEmpty() }?.map { it.durationMs }?.average()?.toLong()
        private val ideal = if (session.laps.isNotEmpty() && session.laps.all { it.sectorElapsedMs.size >= 2 }) {
            session.laps.minOf { it.sectorElapsedMs[0] } +
                session.laps.minOf { it.sectorElapsedMs[1] - it.sectorElapsedMs[0] } +
                session.laps.minOf { it.durationMs - it.sectorElapsedMs[1] }
        } else null
        private val maxSpeedKmh = session.maxSpeedMps?.times(3.6f)?.toInt()
        private val minSpeedKmh = session.minSpeedMps?.times(3.6f)?.toInt()
        private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(18).toFloat(); typeface = Typeface.DEFAULT_BOLD }
        private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 190, 204); textSize = dp(10).toFloat(); typeface = Typeface.DEFAULT_BOLD }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(17).toFloat(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(218, 232, 236); textSize = dp(13).toFloat(); textAlign = Paint.Align.CENTER }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(31, 80, 96); strokeWidth = dp(1).toFloat() }
        private var scroll = 0f
        private var downY = 0f
        private var startScroll = 0f
        private var dragged = false

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
            val cardWidth = (width - dp(32)) / 4f
            drawMetric(canvas, pad, cardTop, cardWidth, cardBottom, "BEST LAP", best?.let(::formatTime) ?: "--:--.---", Color.rgb(24, 213, 184))
            drawMetric(canvas, pad + cardWidth + dp(3), cardTop, cardWidth, cardBottom, "LAST LAP", session.laps.lastOrNull()?.durationMs?.let(::formatTime) ?: "--:--.---", Color.rgb(72, 205, 255))
            drawMetric(canvas, pad + (cardWidth + dp(3)) * 2, cardTop, cardWidth, cardBottom, "MEDIA", average?.let(::formatTime) ?: "--:--.---", Color.rgb(255, 185, 64))
            drawMetric(canvas, pad + (cardWidth + dp(3)) * 3, cardTop, cardWidth, cardBottom, "IDEAL", ideal?.let(::formatTime) ?: "--:--.---", Color.rgb(174, 119, 255))

            val speedTop = dp(99).toFloat(); val speedBottom = dp(136).toFloat()
            val speedWidth = (width - dp(30)) / 2f
            drawMetric(canvas, pad, speedTop, speedWidth, speedBottom, "V MAX", maxSpeedKmh?.let { "$it km/h" } ?: "—", Color.rgb(255, 112, 112))
            drawMetric(canvas, pad + speedWidth + dp(6), speedTop, speedWidth, speedBottom, "V MIN", minSpeedKmh?.let { "$it km/h" } ?: "—", Color.rgb(72, 205, 255))

            val tableTop = dp(153).toFloat()
            val headerBottom = tableTop + dp(25)
            canvas.drawRect(0f, tableTop, width.toFloat(), headerBottom, Paint().apply { color = Color.rgb(9, 39, 51) })
            val columns = floatArrayOf(width * .06f, width * .22f, width * .39f, width * .56f, width * .71f, width * .89f)
            listOf("LAP", "TEMPO", "DELTA", "S1", "S2", "STATO").forEachIndexed { index, label -> canvas.drawText(label, columns[index], tableTop + dp(17), smallPaint.apply { textAlign = Paint.Align.CENTER }) }
            val dataTop = headerBottom + dp(4)
            val rowHeight = dp(22).toFloat()
            val maxScroll = (session.laps.size * rowHeight - (height - dataTop - dp(8))).coerceAtLeast(0f)
            scroll = scroll.coerceIn(0f, maxScroll)
            session.laps.forEachIndexed { index, lap ->
                val rowTop = dataTop + rowHeight * index - scroll
                val y = rowTop + dp(16)
                if (rowTop < dataTop - rowHeight || rowTop > height + rowHeight) return@forEachIndexed
                val delta = best?.let { lap.durationMs - it } ?: 0
                val rowColor = when {
                    delta == 0L -> Color.rgb(24, 213, 184)
                    delta < 0L -> Color.rgb(91, 220, 135)
                    else -> Color.rgb(255, 112, 112)
                }
                canvas.drawLine(pad, rowTop + rowHeight, width - pad, rowTop + rowHeight, gridPaint)
                rowPaint.color = Color.WHITE
                canvas.drawText(lap.number.toString(), columns[0], y, rowPaint)
                rowPaint.color = if (delta == 0L) Color.rgb(24, 213, 184) else Color.WHITE
                canvas.drawText(formatTime(lap.durationMs), columns[1], y, rowPaint)
                rowPaint.color = rowColor
                canvas.drawText(formatDelta(delta), columns[2], y, rowPaint)
                rowPaint.color = Color.rgb(255, 205, 90)
                canvas.drawText(lap.sectorElapsedMs.getOrNull(0)?.let(::formatTime) ?: "—", columns[3], y, rowPaint)
                canvas.drawText(lap.sectorElapsedMs.getOrNull(1)?.let(::formatTime) ?: "—", columns[4], y, rowPaint)
                rowPaint.color = rowColor
                canvas.drawText(if (delta == 0L) "BEST" else if (delta < 0L) "UP" else "DOWN", columns[5], y, rowPaint)
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
                    if (!dragged && event.x > width - dp(90) && event.y < dp(50)) onClose()
                    return true
                }
            }
            return true
        }
    }

    private fun resetSession(keepTrack: Boolean = false) {
        if (running) {
            val simulated = simulator != null
            val gpxName = saveCurrentSession()
            saveSessionRecord(simulated, gpxName)
        }
        stopSimulation()
        running = false
        startButton?.text = "AVVIA"
        timing.reset()
        autoFinish.reset()
        liveRoute.clear()
        sessionSamples.clear()
        recordedLaps.clear()
        currentSectorTimes.clear()
        bestSectorSegmentMs.clear()
        trackMap.setTrack(emptyList())
        bestLap = null
        predictor = null
        sectorReferences = emptyList()
        timing.sectors = emptyList()
        liveDeltaMs = null
        lastDeltaAnnouncementElapsedMs = Long.MIN_VALUE
        lastAnnouncedDeltaMs = null
        previousLiveDeltaMs = null
        deltaAnnouncementsSuppressedUntilMs = Long.MIN_VALUE
        if (!keepTrack) timing.line = null
        dashboard.setTimingLine(timing.line)
        trackMap.setTimingLine(timing.line)
        trackMap.setSectors(emptyList())
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
            "Riferimento battuto di $amount",
            "Sei più rapido di $amount",
            "Hai preso $amount al riferimento",
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
            "Il riferimento si allontana di $amount",
            "Perdi $amount sul giro migliore",
            "Tempo in rosso: $amount",
            "Scarto di $amount",
            "Sei sopra di $amount",
            "Calo di $amount",
            "Manca $amount al riferimento"
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
            "Stai tornando verso il riferimento: $recovered recuperati",
            "Recuperi $recovered sul giro migliore",
            "Delta in calo di $recovered, ora $current"
        )
        val index = ((currentMs / 10 + elapsedMs / 1_000) % variants.size).toInt()
        return variants[index]
    }

    private fun Float.format1() = "%.1f".format(Locale.US, this)

    private fun formatDelta(ms: Long) = "%+.2f".format(Locale.US, ms / 1_000.0)

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) tts?.language = Locale.ITALIAN
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

        fun setSectorResult(number: Int, elapsedMs: Long, deltaMs: Long?) {
            sectorTexts[number] = "S$number  ${formatTime(elapsedMs)}${deltaMs?.let { " ${formatDelta(it)}" } ?: ""}"
            invalidate()
        }

        fun clearSectorResult() { sectorTexts.clear(); invalidate() }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(5, 16, 23))
            canvas.drawRoundRect(dp(2).toFloat(), dp(2).toFloat(), width - dp(2).toFloat(), height - dp(2).toFloat(), dp(9).toFloat(), dp(9).toFloat(), hudStrokePaint)
            val pad = dp(12).toFloat()
            val fullWidth = width - pad * 2
            val red = Color.rgb(238, 50, 62)
            val green = Color.rgb(69, 223, 123)
            val yellow = Color.rgb(247, 219, 74)
            val deltaColor = when { delta == null -> Color.LTGRAY; delta!! < 0 -> green; else -> red }
            val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 28, 39) }
            val darkPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 19, 29) }
            val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red }
            val rowText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = dp(17).toFloat(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val valueText = Paint(rowText).apply { textAlign = Paint.Align.RIGHT; textSize = dp(23).toFloat() }

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

            canvas.drawRoundRect(pad, dp(38).toFloat(), width - pad, dp(98).toFloat(), dp(4).toFloat(), dp(4).toFloat(), panelPaint)
            canvas.drawRect(pad, dp(38).toFloat(), pad + dp(6), dp(98).toFloat(), accentPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("DELTA vs RIFERIMENTO", pad + dp(18), dp(58).toFloat(), labelPaint)
            val deltaText = delta?.let { if (it < 0) "▲ ${formatDelta(it)}" else "▼ ${formatDelta(it)}" } ?: "± 0.00"
            deltaPaint.color = deltaColor
            deltaPaint.textAlign = Paint.Align.LEFT
            deltaPaint.textSize = dp(34).toFloat()
            canvas.drawText(deltaText, pad + dp(18), dp(91).toFloat(), deltaPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("BEST LAP · RIFERIMENTO", width - pad - dp(18), dp(58).toFloat(), labelPaint)
            bestPaint.color = Color.rgb(69, 223, 123)
            bestPaint.textAlign = Paint.Align.RIGHT
            bestPaint.textSize = dp(29).toFloat()
            canvas.drawText(best?.let(::formatTime) ?: "--:--.---", width - pad - dp(18), dp(91).toFloat(), bestPaint)

            canvas.drawRect(pad, dp(106).toFloat(), width - pad, dp(128).toFloat(), darkPanelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("INTERMEDIO", pad + dp(12), dp(123).toFloat(), labelPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("TEMPO / DELTA", width - pad - dp(12), dp(123).toFloat(), labelPaint)
            drawTimingRow(canvas, dp(130).toFloat(), "S1", sectorTexts[1] ?: "--:--.---", Color.rgb(70, 205, 255), rowText, valueText, panelPaint)
            drawTimingRow(canvas, dp(169).toFloat(), "S2", sectorTexts[2] ?: "--:--.---", Color.rgb(255, 185, 64), rowText, valueText, darkPanelPaint)

            // Extra vertical space keeps label and large time separate on compact Pixel screens.
            val cardTop = dp(210).toFloat()
            val cardBottom = dp(278).toFloat()
            val gap = dp(7).toFloat()
            val mid = width / 2f
            canvas.drawRoundRect(pad, cardTop, mid - gap, cardBottom, dp(4).toFloat(), dp(4).toFloat(), darkPanelPaint)
            canvas.drawRoundRect(mid + gap, cardTop, width - pad, cardBottom, dp(4).toFloat(), dp(4).toFloat(), panelPaint)
            canvas.drawRect(pad, cardTop, pad + dp(5), cardBottom, Paint().apply { color = Color.rgb(238, 245, 247) })
            canvas.drawRect(mid + gap, cardTop, mid + gap + dp(5), cardBottom, Paint().apply { color = green })
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("LAST LAP", (pad + mid - gap) / 2f, cardTop + dp(17), labelPaint)
            canvas.drawText(last?.let(::formatTime) ?: "--:--.---", (pad + mid - gap) / 2f, cardBottom - dp(10), lastPaint)
            canvas.drawText("CURRENT LAP", (mid + gap + width - pad) / 2f, cardTop + dp(17), labelPaint)
            val currentTime = elapsed?.let(::formatTime) ?: "--:--.---"
            lastPaint.color = Color.rgb(238, 245, 247)
            canvas.drawText(currentTime, (mid + gap + width - pad) / 2f, cardBottom - dp(10), lastPaint)
            primaryPaint.textAlign = Paint.Align.CENTER
            primaryPaint.textSize = dp(48).toFloat()
            deltaPaint.textAlign = Paint.Align.CENTER
            deltaPaint.textSize = dp(25).toFloat()
            bestPaint.textAlign = Paint.Align.CENTER
            bestPaint.textSize = dp(22).toFloat()
            labelPaint.color = Color.rgb(112, 157, 174)
            labelPaint.textAlign = Paint.Align.CENTER
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
                MotionEvent.ACTION_UP -> if (!dragged && !scaleDetector.isInProgress && track.isNotEmpty()) {
                    onTrackTapped?.invoke(pointAt(event.x, event.y))
                }
            }
            return true
        }

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
