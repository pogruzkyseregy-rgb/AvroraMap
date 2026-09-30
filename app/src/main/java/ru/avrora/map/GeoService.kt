package ru.avrora.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Place(val title: String, val subtitle: String, val lat: Double, val lon: Double) {
    val latLng get() = LatLng(lat, lon)
}

data class NavRoute(val points: List<LatLng>, val distance: Double, val duration: Double)

/**
 * Поиск адресов (Photon) и построение маршрутов (OSRM на серверах FOSSGIS).
 * Оба сервиса бесплатные и работают на данных OpenStreetMap.
 */
object GeoService {

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "AvroraMap/0.2 (Android)")
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://photon.komoot.io/api/?q=$q&lat=52.6031&lon=39.5708&limit=8" +
            "&bbox=39.35,52.45,39.85,52.75"
        val features = JSONObject(get(url)).getJSONArray("features")
        (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val p = f.getJSONObject("properties")
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val street = listOf(p.optString("street"), p.optString("housenumber"))
                .filter { it.isNotBlank() }.joinToString(" ")
            val name = p.optString("name")
            val title = name.ifBlank { street }.ifBlank { return@mapNotNull null }
            val subtitle = listOf(
                if (name.isNotBlank()) street else "",
                p.optString("district"),
                p.optString("city")
            ).filter { it.isNotBlank() }.joinToString(", ")
            Place(title, subtitle, c.getDouble(1), c.getDouble(0))
        }.distinctBy { it.title + it.subtitle }
    }

    /** mode: "foot" или "car" */
    suspend fun route(from: LatLng, to: LatLng, mode: String): NavRoute = withContext(Dispatchers.IO) {
        val profile = if (mode == "car") "routed-car" else "routed-foot"
        val url = "https://routing.openstreetmap.de/$profile/route/v1/driving/" +
            "${from.longitude},${from.latitude};${to.longitude},${to.latitude}" +
            "?overview=full&geometries=geojson"
        val json = JSONObject(get(url))
        val routes = json.optJSONArray("routes")
        if (routes == null || routes.length() == 0) throw IOException("Маршрут не найден")
        val r = routes.getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val points = (0 until coords.length()).map {
            val c = coords.getJSONArray(it)
            LatLng(c.getDouble(1), c.getDouble(0))
        }
        NavRoute(points, r.getDouble("distance"), r.getDouble("duration"))
    }
}
