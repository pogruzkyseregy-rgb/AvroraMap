package ru.avrora.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*

/**
 * Перекрашивает стандартный стиль OpenFreeMap в неоновую тему Авроры.
 * Все цвета темы — здесь, меняй под себя.
 */
object NeonTheme {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    const val BG = "#070A1A"
    const val WATER = "#0B1640"
    const val GREEN = "#0A1428"
    const val BUILDING = "#141A3A"
    const val ROAD_MAIN = "#7C5CFF"
    const val ROAD = "#2E3A8C"
    const val RAIL = "#3B2E6E"
    const val LABEL = "#8F9BFF"

    fun apply(style: Style) {
        for (layer in style.layers) {
            val id = layer.id.lowercase()
            when (layer) {
                is BackgroundLayer -> layer.setProperties(backgroundColor(BG))
                is FillLayer -> layer.setProperties(fillColor(fillFor(id)))
                is FillExtrusionLayer -> layer.setProperties(
                    fillExtrusionColor(BUILDING), fillExtrusionOpacity(0.85f)
                )
                is LineLayer -> layer.setProperties(lineColor(lineFor(id)))
                is SymbolLayer -> {
                    if (id.startsWith("poi")) {
                        layer.setProperties(visibility(Property.NONE)) // убираем лишние POI
                    } else {
                        layer.setProperties(textColor(LABEL), textHaloColor(BG))
                    }
                }
                is RasterLayer, is HillshadeLayer ->
                    layer.setProperties(visibility(Property.NONE))
            }
        }
    }

    private fun fillFor(id: String) = when {
        "water" in id -> WATER
        "building" in id -> BUILDING
        "park" in id || "wood" in id || "grass" in id || "landcover" in id -> GREEN
        else -> BG
    }

    private fun lineFor(id: String) = when {
        "water" in id -> WATER
        "rail" in id -> RAIL
        "motorway" in id || "trunk" in id || "primary" in id -> ROAD_MAIN
        "boundary" in id -> RAIL
        else -> ROAD
    }
}
