package com.eneskocamaan.kenet.earthquake

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.api.ConfirmedEarthquakeItem
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

class EarthquakeDetailSheet(private val earthquake: ConfirmedEarthquakeItem) : BottomSheetDialogFragment() {

    private lateinit var map: MapView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Configuration.getInstance().load(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()))
        val view = inflater.inflate(R.layout.layout_earthquake_detail, container, false)

        view.findViewById<TextView>(R.id.tvDepthDetail).text = "${earthquake.depth} km"
        view.findViewById<TextView>(R.id.tvCoordsDetail).text = "${earthquake.latitude} N\n${earthquake.longitude} E"

        val timeStr = earthquake.occurredAt.split("T").lastOrNull()?.take(5) ?: ""
        view.findViewById<TextView>(R.id.tvTimeDetail).text = timeStr

        view.findViewById<TextView>(R.id.tvIntensityDetail).text = if (earthquake.magnitude >= 4.0) "GÜÇLÜ" else "HAFİF"
        view.findViewById<View>(R.id.btnClose).setOnClickListener { dismiss() }

        map = view.findViewById(R.id.osmMapView)
        setupStaticMap()
        return view
    }

    private fun setupStaticMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)

        // Harita etkileşimlerini tamamen devre dışı bırak
        map.setMultiTouchControls(false)
        map.setOnTouchListener { _, _ -> true }

        // Türkiye'yi merkez alan sabit bir görünüm ayarla
        val turkeyCenter = GeoPoint(39.93, 32.86) // Ankara'nın koordinatları
        map.controller.setZoom(6.0) // Türkiye'yi gösterecek bir zoom seviyesi
        map.controller.setCenter(turkeyCenter)
        map.minZoomLevel = 6.0 // Minimum zoom seviyesini de sabitle
        map.maxZoomLevel = 6.0 // Maksimum zoom seviyesini de sabitle


        // Karanlık mod filtresi
        map.overlayManager.tilesOverlay.setColorFilter(PorterDuffColorFilter(Color.DKGRAY, PorterDuff.Mode.MULTIPLY))

        val epicenter = GeoPoint(earthquake.latitude, earthquake.longitude)

        // Etki Alanı Dairesi
        val circle = Polygon().apply {
            points = Polygon.pointsAsCircle(epicenter, (earthquake.magnitude * 10000))
            fillPaint.color = Color.parseColor("#40FF3B30")
            outlinePaint.color = Color.parseColor("#FF3B30")
            outlinePaint.strokeWidth = 2f
        }
        map.overlays.add(circle)

        // Deprem merkezini gösteren modern işaretçi
        val marker = Marker(map).apply {
            position = epicenter
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_epicenter)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        map.overlays.add(marker)
        map.invalidate()
    }
}