package it.crono

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/** A significant braking minimum or acceleration maximum shown in lap analysis. */
data class SpeedMarker(val point: TrackPoint, val speedKmh: Int, val kind: String)

/** Lightweight OpenStreetMap + Leaflet map. Tiles require a data connection. */
@SuppressLint("SetJavaScriptEnabled")
class TrackMapView(context: Context, private val onTrackTap: (TrackPoint) -> Unit) : WebView(context) {
    private var isReady = false
    private var queuedTrack: List<TrackPoint> = emptyList()
    private var queuedFix: GpsSample? = null
    private var queuedLine: TimingLine? = null
    private var queuedSectors: List<TimingLine> = emptyList()
    private var queuedSpeedMarkers: List<SpeedMarker> = emptyList()

    init {
        setBackgroundColor(Color.rgb(20, 25, 30))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // The standard OSM tile service rejects anonymous WebView traffic.  Keep this
        // stable and app-specific; do not impersonate a browser or another map app.
        settings.userAgentString = "Crono/0.3 (Android GPS lap timer; package it.crono)"
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                isReady = true
                renderAll()
                postDelayed({ evaluateJavascript("window.refreshMap()", null) }, 250)
            }
        }
        addJavascriptInterface(Bridge(), "Crono")
        loadDataWithBaseURL("https://www.openstreetmap.org/", MAP_HTML, "text/html", "UTF-8", null)
    }

    fun setTrack(points: List<TrackPoint>) { queuedTrack = points; renderAll() }
    fun setFix(fix: GpsSample?) { queuedFix = fix; renderAll() }
    fun setTimingLine(line: TimingLine?) { queuedLine = line; renderAll() }
    fun setSectors(sectors: List<TimingLine>) { queuedSectors = sectors; renderAll() }
    fun setSpeedMarkers(markers: List<SpeedMarker>) { queuedSpeedMarkers = markers; renderAll() }
    fun fitEntireTrack() {
        if (isReady) evaluateJavascript("window.fitEntireTrack()", null)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (isReady && (width != oldWidth || height != oldHeight)) post { evaluateJavascript("window.refreshMap()", null) }
    }

    private fun renderAll() {
        if (!isReady) return
        if (queuedTrack.isEmpty()) {
            evaluateJavascript("window.clearTrack()", null)
        } else {
            val values = JSONArray()
            val stride = (queuedTrack.size / 2_000).coerceAtLeast(1)
            queuedTrack.filterIndexed { index, _ -> index % stride == 0 || index == queuedTrack.lastIndex }.forEach {
                values.put(JSONArray().put(it.lat).put(it.lon))
            }
            evaluateJavascript("window.setTrack($values)", null)
        }
        queuedLine?.let {
            evaluateJavascript("window.setLine([[${it.pointA.lat},${it.pointA.lon}],[${it.pointB.lat},${it.pointB.lon}]])", null)
        } ?: evaluateJavascript("window.setLine(null)", null)
        val sectors = JSONArray()
        queuedSectors.forEach { sectors.put(JSONArray().put(JSONArray().put(it.pointA.lat).put(it.pointA.lon)).put(JSONArray().put(it.pointB.lat).put(it.pointB.lon))) }
        evaluateJavascript("window.setSectors($sectors)", null)
        val speedMarkers = JSONArray()
        queuedSpeedMarkers.forEach { marker ->
            speedMarkers.put(JSONObject().apply {
                put("lat", marker.point.lat); put("lon", marker.point.lon)
                put("speed", marker.speedKmh); put("kind", marker.kind)
            })
        }
        evaluateJavascript("window.setSpeedMarkers($speedMarkers)", null)
        // Render the current position last so it is always above route, finish and sectors.
        queuedFix?.let { evaluateJavascript("window.setFix(${it.lat},${it.lon})", null) }
    }

    private inner class Bridge {
        @JavascriptInterface fun onMapTap(lat: Double, lon: Double) {
            post { onTrackTap(TrackPoint(lat, lon)) }
        }
    }

    companion object {
        private const val MAP_HTML = """
<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>
<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>
<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>
<style>html,body,#map{margin:0;width:100%;height:100%;background:#071720}.leaflet-tile-pane{filter:saturate(.62) brightness(.78) contrast(1.12)}.leaflet-control-attribution{font-size:9px;background:rgba(4,14,20,.78)!important;color:#b8d7e2}.leaflet-control-zoom a{background:#081c26!important;color:#48cdff!important;border-color:#1c94be!important}.speed-label{background:rgba(3,14,20,.92)!important;border:1px solid #d8f3ff!important;border-radius:3px!important;color:#fff!important;font-weight:bold!important;font-size:10px!important;padding:2px 4px!important;box-shadow:none!important}.speed-label:before{display:none!important}</style></head>
<body><div id='map'></div><script>
const map=L.map('map',{zoomControl:true}).setView([45.656,8.493],14);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap contributors'}).addTo(map);
let track,line,fix,sectorLines=[],speedMarkers=[],hasFit=false;
window.clearTrack=function(){if(track){map.removeLayer(track);track=null;}hasFit=false;};
window.setTrack=function(points){if(track)map.removeLayer(track);track=L.polyline(points,{color:'#32a9ff',weight:4}).addTo(map);};
window.fitEntireTrack=function(){if(track){map.fitBounds(track.getBounds(),{padding:[42,42],maxZoom:18});hasFit=true;}};
window.refreshMap=function(){map.invalidateSize(false);};
window.setFix=function(lat,lon){if(!fix){fix=L.circleMarker([lat,lon],{radius:7,color:'#fff',weight:2,fillColor:'#f33',fillOpacity:1}).addTo(map);if(!hasFit)map.setView([lat,lon],17);}else fix.setLatLng([lat,lon]);fix.bringToFront();};
window.setLine=function(points){if(line)map.removeLayer(line);line=null;if(points)line=L.polyline(points,{color:'#ffe000',weight:6}).bindTooltip('TRAGUARDO',{permanent:true,direction:'top',className:'speed-label'}).addTo(map);};
window.setSectors=function(sectors){sectorLines.forEach(s=>map.removeLayer(s));sectorLines=sectors.map((points,index)=>L.polyline(points,{color:index===0?'#ff9f1c':'#b983ff',weight:5,dashArray:'8 7'}).bindTooltip('S'+(index+1),{permanent:true,direction:'top',className:'speed-label'}).addTo(map));};
window.setSpeedMarkers=function(markers){speedMarkers.forEach(m=>map.removeLayer(m));speedMarkers=markers.map(item=>{const braking=item.kind==='braking';const color=braking?'#ff9f1c':'#25d7ad';const label=(braking?'F ':'A ')+item.speed+' km/h';return L.circleMarker([item.lat,item.lon],{radius:6,color:'#fff',weight:1.5,fillColor:color,fillOpacity:1}).bindTooltip(label,{permanent:true,direction:'top',offset:[0,-7],className:'speed-label'}).addTo(map);});};
map.on('click',function(e){if(window.Crono)Crono.onMapTap(e.latlng.lat,e.latlng.lng);});
</script></body></html>"""
    }
}
