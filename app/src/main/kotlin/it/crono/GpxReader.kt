package it.crono

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object GpxReader {
    fun read(input: InputStream): List<TrackPoint> {
        val parser = Xml.newPullParser().apply {
            setInput(input, "UTF-8")
        }
        val result = mutableListOf<TrackPoint>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("trkpt", true)) {
                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                if (lat != null && lon != null) result += TrackPoint(lat, lon)
            }
            event = parser.next()
        }
        return result
    }
}
