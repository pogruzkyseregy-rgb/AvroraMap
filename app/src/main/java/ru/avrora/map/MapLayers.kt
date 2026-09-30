package ru.avrora.map

import android.content.Context
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/** Все свои слои поверх карты. */
object MapLayers {
    const val STOPS = "stops"
    const val STOPS_LAYER = "stops-layer"
    const val TRANSIT = "transit-line"
    const val NAV = "nav-line"
    const val ME = "me"
    const val DEST = "dest"

    fun setup(style: Style, ctx: Context) {
        style.addImage("stop-icon", MarkerFactory.stop(ctx))
        style.addImage("me-icon", MarkerFactory.myLocation(ctx))
        style.addImage("dest-icon", MarkerFactory.destination(ctx))

        for (id in listOf(STOPS, TRANSIT, NAV, ME, DEST)) style.addSource(GeoJsonSource(id, empty()))

        glowLine(style, TRANSIT, "#9B7BFF")
        glowLine(style, NAV, "#4FC3FF")

        style.addLayer(
            SymbolLayer(STOPS_LAYER, STOPS).withProperties(
                iconImage("stop-icon"),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
            ).apply { minZoom = 13.5f }
        )
        style.addLayer(
            SymbolLayer("dest-layer", DEST).withProperties(
                iconImage("dest-icon"), iconAllowOverlap(true), iconIgnorePlacement(true)
            )
        )
        style.addLayer(
            SymbolLayer("me-layer", ME).withProperties(
                iconImage("me-icon"), iconAllowOverlap(true), iconIgnorePlacement(true)
            )
        )
    }

    private fun glowLine(style: Style, src: String, color: String) {
        style.addLayer(
            LineLayer("$src-glow", src).withProperties(
                lineColor(color), lineWidth(14f), lineOpacity(0.25f), lineBlur(6f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        style.addLayer(
            LineLayer("$src-core", src).withProperties(
                lineColor(color), lineWidth(4.5f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    fun setLineColor(style: Style, src: String, color: String) {
        style.getLayer("$src-glow")?.setProperties(lineColor(color))
        style.getLayer("$src-core")?.setProperties(lineColor(color))
    }

    fun setStops(style: Style, stops: List<Stop>) {
        source(style, STOPS)?.setGeoJson(FeatureCollection.fromFeatures(stops.map { s ->
            Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply { addNumberProperty("id", s.id) }
        }))
    }

    fun setLines(style: Style, src: String, lines: List<List<LatLng>>) {
        val geom = MultiLineString.fromLngLats(lines.map { l -> l.map { Point.fromLngLat(it.longitude, it.latitude) } })
        source(style, src)?.setGeoJson(FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(geom))))
    }

    fun setPoint(style: Style, src: String, p: LatLng?) {
        val features = if (p == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(p.longitude, p.latitude)))
        source(style, src)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun clear(style: Style, src: String) {
        source(style, src)?.setGeoJson(empty())
    }

    private fun source(style: Style, id: String) = style.getSourceAs<GeoJsonSource>(id)
    private fun empty() = FeatureCollection.fromFeatures(emptyList<Feature>())
}
