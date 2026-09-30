package ru.avrora.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.hypot

data class Stop(val id: Long, val name: String, val lat: Double, val lon: Double) {
    val latLng get() = LatLng(lat, lon)
}

data class TransitRoute(
    val id: Long,
    val type: String,
    val ref: String,
    val from: String,
    val to: String,
    val lines: List<List<LatLng>>,
    val stopIds: List<Long>
) {
    val key get() = "$type:$ref"
    val typeName
        get() = when (type) {
            "bus" -> "Автобус"
            "trolleybus" -> "Троллейбус"
            "tram" -> "Трамвай"
            "share_taxi", "minibus" -> "Маршрутка"
            else -> "Маршрут"
        }
    val color
        get() = when (type) {
            "tram" -> "#4FC3FF"
            "trolleybus" -> "#3DDC97"
            "share_taxi", "minibus" -> "#FF6FD8"
            else -> "#9B7BFF"
        }
    val sortKey get() = ref.takeWhile { it.isDigit() }.toIntOrNull() ?: 9999
}

class TransitData(val stops: List<Stop>, val routes: List<TransitRoute>) {
    val stopsById = stops.associateBy { it.id }
    val routesByStop: Map<Long, List<TransitRoute>> = HashMap<Long, MutableList<TransitRoute>>().apply {
        for (r in routes) for (id in r.stopIds.toSet()) getOrPut(id) { mutableListOf() }.add(r)
    }
}

/**
 * Остановки и маршруты Липецка из OpenStreetMap (Overpass API).
 * Всё кэшируется на 7 дней.
 */
class StopsRepository(ctx: Context) {
    private val cache = File(ctx.filesDir, "transit_v2.json")

    fun hasCache() = cache.exists()

    suspend fun load(): TransitData? = withContext(Dispatchers.IO) {
        val week = 7L * 24 * 60 * 60 * 1000
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < week
        val json = if (fresh) cache.readText() else try {
            fetch().also { cache.writeText(it) }
        } catch (e: Exception) {
            if (cache.exists()) cache.readText() else return@withContext null
        }
        withContext(Dispatchers.Default) { parse(json) }
    }

    private fun fetch(): String {
        val q = """
            [out:json][timeout:120];
            (
              node["highway"="bus_stop"]($BBOX);
              node["public_transport"="platform"]($BBOX);
              relation["type"="route"]["route"~"^(bus|trolleybus|tram|share_taxi|minibus)$"]($BBOX);
            );
            out geom;
        """.trimIndent()
        val conn = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 150_000
        conn.setRequestProperty("User-Agent", "AvroraMap/0.2 (Android)")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
        if (conn.responseCode != 200) throw IOException("Overpass: ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun parse(json: String): TransitData {
        val elements = JSONObject(json).getJSONArray("elements")
        val stops = HashMap<Long, Stop>()
        val relations = mutableListOf<JSONObject>()

        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            when (e.optString("type")) {
                "node" -> {
                    val name = e.optJSONObject("tags")?.optString("name").orEmpty()
                    stops[e.getLong("id")] = Stop(
                        e.getLong("id"),
                        name.ifBlank { "Остановка" },
                        e.getDouble("lat"),
                        e.getDouble("lon")
                    )
                }
                "relation" -> relations += e
            }
        }

        val grid = StopGrid(stops.values)
        val routes = relations.mapNotNull { rel -> parseRoute(rel, stops, grid) }
        return TransitData(stops.values.toList(), routes)
    }

    private fun parseRoute(rel: JSONObject, stops: Map<Long, Stop>, grid: StopGrid): TransitRoute? {
        val tags = rel.optJSONObject("tags") ?: return null
        val ref = tags.optString("ref").ifBlank { tags.optString("name") }.ifBlank { return null }
        val members = rel.optJSONArray("members") ?: JSONArray()

        val lines = mutableListOf<List<LatLng>>()
        val platforms = mutableListOf<JSONObject>()
        val stopPositions = mutableListOf<JSONObject>()

        for (i in 0 until members.length()) {
            val m = members.getJSONObject(i)
            val role = m.optString("role")
            when (m.optString("type")) {
                "way" -> if (!role.startsWith("platform")) {
                    val g = m.optJSONArray("geometry") ?: continue
                    lines += (0 until g.length()).map {
                        val p = g.getJSONObject(it)
                        LatLng(p.getDouble("lat"), p.getDouble("lon"))
                    }
                }
                "node" -> when {
                    role.startsWith("platform") -> platforms += m
                    role.startsWith("stop") -> stopPositions += m
                }
            }
        }

        val stopMembers = platforms.ifEmpty { stopPositions }
        val stopIds = mutableListOf<Long>()
        for (m in stopMembers) {
            val id = m.getLong("ref")
            val resolved = when {
                stops.containsKey(id) -> id
                m.has("lat") -> grid.nearest(m.getDouble("lat"), m.getDouble("lon"), 60.0)?.id
                else -> null
            } ?: continue
            if (stopIds.lastOrNull() != resolved) stopIds += resolved
        }

        return TransitRoute(
            id = rel.getLong("id"),
            type = tags.optString("route"),
            ref = ref,
            from = tags.optString("from"),
            to = tags.optString("to"),
            lines = lines,
            stopIds = stopIds
        )
    }

    /** Быстрый поиск ближайшей остановки. */
    private class StopGrid(stops: Collection<Stop>) {
        private val cells = HashMap<Long, MutableList<Stop>>()
        private fun key(x: Int, y: Int) = x.toLong() shl 32 or (y.toLong() and 0xffffffffL)
        private fun cx(lat: Double) = (lat * 200).toInt()
        private fun cy(lon: Double) = (lon * 120).toInt()

        init {
            for (s in stops) cells.getOrPut(key(cx(s.lat), cy(s.lon))) { mutableListOf() }.add(s)
        }

        fun nearest(lat: Double, lon: Double, maxMeters: Double): Stop? {
            var best: Stop? = null
            var bestD = maxMeters
            val k = cos(Math.toRadians(lat))
            for (dx in -1..1) for (dy in -1..1) {
                val list = cells[key(cx(lat) + dx, cy(lon) + dy)] ?: continue
                for (s in list) {
                    val d = hypot((s.lat - lat) * 111_320, (s.lon - lon) * 111_320 * k)
                    if (d < bestD) { bestD = d; best = s }
                }
            }
            return best
        }
    }

    companion object {
        // юг, запад, север, восток — рамка вокруг Липецка
        const val BBOX = "52.52,39.45,52.68,39.75"
    }
}
