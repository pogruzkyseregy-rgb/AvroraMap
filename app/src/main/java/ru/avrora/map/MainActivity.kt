package ru.avrora.map

import android.Manifest
import android.content.Context
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var searchInput: EditText
    private lateinit var searchClear: TextView
    private lateinit var resultsScroll: ScrollView
    private lateinit var resultsList: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelSubtitle: TextView
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private lateinit var listScroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var actions: LinearLayout

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var transit: TransitData? = null

    private lateinit var location: LocationHelper
    private var me: LatLng? = null
    private var centerOnFirstFix = true

    private var searchJob: Job? = null
    private var navJob: Job? = null
    private var navMode = "foot"

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.any { it }) startLocation()
            else toast("Без доступа к геолокации не смогу показать, где ты")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        bindViews()

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        location = LocationHelper(this) { onLocation(it) }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(52.6031, 39.5708)) // центр Липецка
                .zoom(12.5)
                .build()
            m.setStyle(Style.Builder().fromUri(NeonTheme.STYLE_URL)) { s ->
                style = s
                NeonTheme.apply(s)
                MapLayers.setup(s, this)
                m.addOnMapClickListener { p -> onMapClick(m, p) }
                loadTransit(s)
                me?.let { MapLayers.setPoint(s, MapLayers.ME, it) }
            }
        }

        setupSearch()
        findViewById<ImageButton>(R.id.btnLocate).setOnClickListener { locateMe() }
        findViewById<TextView>(R.id.panelClose).setOnClickListener { closePanel() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    resultsScroll.visibility == View.VISIBLE -> hideResults()
                    panel.visibility == View.VISIBLE -> closePanel()
                    else -> finish()
                }
            }
        })

        if (location.hasPermission()) startLocation() else requestLocationPermission()
    }

    private fun bindViews() {
        mapView = findViewById(R.id.mapView)
        searchInput = findViewById(R.id.searchInput)
        searchClear = findViewById(R.id.searchClear)
        resultsScroll = findViewById(R.id.resultsScroll)
        resultsList = findViewById(R.id.resultsList)
        panel = findViewById(R.id.panel)
        panelTitle = findViewById(R.id.panelTitle)
        panelSubtitle = findViewById(R.id.panelSubtitle)
        chipsScroll = findViewById(R.id.chipsScroll)
        chips = findViewById(R.id.chips)
        listScroll = findViewById(R.id.listScroll)
        list = findViewById(R.id.list)
        actions = findViewById(R.id.actions)
    }

    // ---------- Данные ----------

    private fun loadTransit(s: Style) {
        val repo = StopsRepository(this)
        if (!repo.hasCache()) toast("Загружаю остановки и маршруты… (первый раз до минуты)")
        lifecycleScope.launch {
            val data = repo.load()
            if (data == null) {
                toast("Не удалось загрузить остановки. Проверь интернет")
                return@launch
            }
            transit = data
            MapLayers.setStops(s, data.stops.filter { data.routesByStop.containsKey(it.id) }.ifEmpty { data.stops })
        }
    }

    // ---------- Геолокация ----------

    private fun requestLocationPermission() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun startLocation() {
        if (!location.start()) toast("Включи геолокацию на телефоне")
    }

    private fun onLocation(p: LatLng) {
        me = p
        style?.let { MapLayers.setPoint(it, MapLayers.ME, p) }
        if (centerOnFirstFix && map != null) {
            centerOnFirstFix = false
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(p, 15.0))
        }
    }

    private fun locateMe() {
        val p = me
        when {
            !location.hasPermission() -> requestLocationPermission()
            p == null -> { startLocation(); toast("Ищу тебя…") }
            else -> map?.animateCamera(CameraUpdateFactory.newLatLngZoom(p, 16.0))
        }
    }

    // ---------- Поиск ----------

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val q = e?.toString()?.trim().orEmpty()
                searchClear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                searchJob?.cancel()
                if (q.length < 2) { hideResults(); return }
                searchJob = lifecycleScope.launch {
                    delay(400)
                    val local = searchStops(q)
                    showResults(local, emptyList(), loading = true)
                    val remote = runCatching { GeoService.search(q) }.getOrElse { emptyList() }
                    showResults(local, remote, loading = false)
                }
            }
        })
        searchInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }
        searchClear.setOnClickListener {
            searchInput.setText("")
            hideResults()
        }
    }

    private fun searchStops(q: String): List<Stop> {
        val data = transit ?: return emptyList()
        return data.stops
            .filter { it.name.contains(q, ignoreCase = true) && data.routesByStop.containsKey(it.id) }
            .distinctBy { it.name }
            .take(4)
    }

    private fun showResults(stops: List<Stop>, places: List<Place>, loading: Boolean) {
        resultsList.removeAllViews()
        for (s in stops) {
            resultRow("◉  ${s.name}", "Остановка") { hideResults(); showStop(s, moveCamera = true) }
        }
        for (p in places) {
            resultRow(p.title, p.subtitle) { hideResults(); showPlace(p) }
        }
        if (loading) resultRow("Ищу…", null, null)
        else if (stops.isEmpty() && places.isEmpty()) resultRow("Ничего не найдено", null, null)
        resultsScroll.visibility = View.VISIBLE
    }

    private fun resultRow(title: String, subtitle: String?, onClick: (() -> Unit)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 10.dp, 16.dp, 10.dp)
            onClick?.let { c -> setOnClickListener { c() } }
        }
        row.addView(TextView(this).apply {
            text = title; setTextColor(0xFFE6E9FF.toInt()); textSize = 16f
        })
        if (!subtitle.isNullOrBlank()) row.addView(TextView(this).apply {
            text = subtitle; setTextColor(0xFF7F88C8.toInt()); textSize = 13f
        })
        resultsList.addView(row)
    }

    private fun hideResults() {
        resultsScroll.visibility = View.GONE
        hideKeyboard()
    }

    private fun hideKeyboard() {
        searchInput.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // ---------- Клики по карте ----------

    private fun onMapClick(m: MapLibreMap, point: LatLng): Boolean {
        if (resultsScroll.visibility == View.VISIBLE) { hideResults(); return true }
        val p = m.projection.toScreenLocation(point)
        val t = 22.dp.toFloat()
        val f = m.queryRenderedFeatures(RectF(p.x - t, p.y - t, p.x + t, p.y + t), MapLayers.STOPS_LAYER)
            .firstOrNull() ?: return false
        val id = f.getNumberProperty("id")?.toLong() ?: return false
        transit?.stopsById?.get(id)?.let { showStop(it, moveCamera = false) }
        return true
    }

    // ---------- Экраны нижней панели ----------

    private fun showStop(stop: Stop, moveCamera: Boolean) {
        val s = style ?: return
        MapLayers.clear(s, MapLayers.TRANSIT)
        if (moveCamera) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(stop.latLng, 16.0))

        val routes = transit?.routesByStop?.get(stop.id).orEmpty()
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.sortKey }, { it.ref }))

        openPanel(stop.name, if (routes.isEmpty()) "Нет данных о маршрутах" else "Остановка · нажми на номер")
        if (routes.isNotEmpty()) {
            chipsScroll.visibility = View.VISIBLE
            for (r in routes) addChip(r.ref, r.color) { showTransitRoute(r, stop) }
        }
        addAction("Маршрут сюда", primary = true) { buildNav(stop.latLng, stop.name) }
    }

    private fun showTransitRoute(route: TransitRoute, fromStop: Stop?) {
        val s = style ?: return
        val data = transit ?: return
        val siblings = data.routes.filter { it.key == route.key }

        MapLayers.clear(s, MapLayers.NAV)
        MapLayers.setPoint(s, MapLayers.DEST, null)
        MapLayers.setLineColor(s, MapLayers.TRANSIT, route.color)
        MapLayers.setLines(s, MapLayers.TRANSIT, route.lines)
        fit(route.lines.flatten())

        val direction = listOf(route.from, route.to).filter { it.isNotBlank() }.joinToString(" → ")
        openPanel("${route.typeName} ${route.ref}", direction.ifBlank { null })

        val stops = route.stopIds.mapNotNull { data.stopsById[it] }
        if (stops.isNotEmpty()) {
            listScroll.visibility = View.VISIBLE
            stops.forEachIndexed { i, st ->
                val current = st.id == fromStop?.id
                addListItem("${i + 1}. ${st.name}", current) { showStop(st, moveCamera = true) }
            }
            if (stops.size > 6) listScroll.layoutParams = listScroll.layoutParams.apply { height = 220.dp }
        }

        if (siblings.size > 1) {
            addAction("⇄ Обратно") {
                val next = siblings[(siblings.indexOf(route) + 1) % siblings.size]
                showTransitRoute(next, fromStop)
            }
        }
        if (fromStop != null) addAction("← Остановка") { showStop(fromStop, moveCamera = true) }
    }

    private fun showPlace(p: Place) {
        val s = style ?: return
        MapLayers.clear(s, MapLayers.TRANSIT)
        MapLayers.clear(s, MapLayers.NAV)
        MapLayers.setPoint(s, MapLayers.DEST, p.latLng)
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(p.latLng, 16.0))
        openPanel(p.title, p.subtitle.ifBlank { null })
        addAction("Маршрут сюда", primary = true) { buildNav(p.latLng, p.title) }
    }

    private fun buildNav(target: LatLng, name: String) {
        val s = style ?: return
        val from = me
        if (from == null) {
            toast("Сначала нужно твоё местоположение")
            locateMe()
            return
        }
        MapLayers.clear(s, MapLayers.TRANSIT)
        MapLayers.setPoint(s, MapLayers.DEST, target)
        openPanel("Маршрут: $name", "Строю…")

        navJob?.cancel()
        navJob = lifecycleScope.launch {
            val result = runCatching { GeoService.route(from, target, navMode) }
            val r = result.getOrNull()
            if (r == null) {
                panelSubtitle.text = "Не удалось построить маршрут"
                addAction("Повторить", primary = true) { buildNav(target, name) }
                return@launch
            }
            MapLayers.setLines(s, MapLayers.NAV, listOf(r.points))
            fit(r.points + from + target)

            val modeName = if (navMode == "car") "На машине" else "Пешком"
            panelSubtitle.text = "$modeName · ${formatTime(r.duration)} · ${formatDistance(r.distance)}"
            actions.removeAllViews()
            addAction("Пешком", primary = navMode == "foot") { navMode = "foot"; buildNav(target, name) }
            addAction("Машина", primary = navMode == "car") { navMode = "car"; buildNav(target, name) }
            addAction("Сбросить") { closePanel() }
        }
    }

    // ---------- Панель: сборка элементов ----------

    private fun openPanel(title: String, subtitle: String?) {
        panelTitle.text = title
        panelSubtitle.text = subtitle.orEmpty()
        panelSubtitle.visibility = if (subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        chips.removeAllViews()
        list.removeAllViews()
        actions.removeAllViews()
        chipsScroll.visibility = View.GONE
        listScroll.visibility = View.GONE
        listScroll.layoutParams = listScroll.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        panel.visibility = View.VISIBLE
    }

    private fun closePanel() {
        navJob?.cancel()
        panel.visibility = View.GONE
        style?.let {
            MapLayers.clear(it, MapLayers.TRANSIT)
            MapLayers.clear(it, MapLayers.NAV)
            MapLayers.setPoint(it, MapLayers.DEST, null)
        }
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = radiusDp.dp.toFloat()
        setColor(fill)
        setStroke(1.dp, stroke)
    }

    private fun parse(color: String) = android.graphics.Color.parseColor(color)

    private fun addChip(text: String, color: String, onClick: () -> Unit) {
        val c = parse(color)
        chips.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = 48.dp
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            background = rounded((c and 0x00FFFFFF) or 0x33000000, c, 18)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8.dp }
        })
    }

    private fun addListItem(text: String, highlight: Boolean, onClick: () -> Unit) {
        list.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (highlight) 0xFF4FC3FF.toInt() else 0xFFD0D5FF.toInt())
            if (highlight) typeface = Typeface.DEFAULT_BOLD
            setPadding(4.dp, 8.dp, 4.dp, 8.dp)
            setOnClickListener { onClick() }
        })
    }

    private fun addAction(text: String, primary: Boolean = false, onClick: () -> Unit) {
        actions.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            background = if (primary) rounded(0xFF5B3FD1.toInt(), 0xFF9B7BFF.toInt(), 12)
            else rounded(0x00000000, 0xFF4A55A8.toInt(), 12)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = 8.dp }
        })
    }

    // ---------- Утилиты ----------

    private fun fit(points: List<LatLng>) {
        if (points.size < 2) return
        val bounds = runCatching { LatLngBounds.Builder().includes(points).build() }.getOrNull() ?: return
        map?.animateCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, 40.dp, 90.dp, 80.dp, 300.dp)
        )
    }

    private fun formatTime(sec: Double): String {
        val min = (sec / 60).roundToInt().coerceAtLeast(1)
        return if (min < 60) "$min мин" else "${min / 60} ч ${min % 60} мин"
    }

    private fun formatDistance(m: Double) =
        if (m < 1000) "${m.roundToInt()} м"
        else String.format(Locale.forLanguageTag("ru"), "%.1f км", m / 1000)

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------- Жизненный цикл карты ----------

    override fun onStart() {
        super.onStart(); mapView.onStart()
        if (location.hasPermission()) location.start()
    }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() {
        super.onStop(); mapView.onStop()
        location.stop()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState)
    }
}
