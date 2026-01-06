package com.eneskocamaan.kenet.emergency

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.databinding.FragmentEmergencyMapBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

class EmergencyMapFragment : Fragment(R.layout.fragment_emergency_map), SensorEventListener, LocationListener {

    private var _binding: FragmentEmergencyMapBinding? = null
    private val binding get() = _binding!!

    private var myLocation: GeoPoint? = null
    private var mapBoundary: BoundingBox? = null

    // Veri Listeleri
    private var allPeers: List<EmergencyPeer> = emptyList()
    private var currentFilteredPeers: List<EmergencyPeer> = emptyList()
    private var currentFilterMode = "ALL"

    private var peerAdapter: PeerAdapter? = null

    // --- SENSÖR & KONUM ---
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var locationManager: LocationManager

    // Takip ve Popup Durumları
    private var isTrackingActive = false
    private var targetPeerForCompass: EmergencyPeer? = null
    private var activeDialog: AlertDialog? = null
    private var activeDialogDistanceText: TextView? = null
    private var activeCompassView: ImageView? = null
    private var targetBearing: Float = 0f

    // --- İZİN YÖNETİCİSİ ---
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkHardwareAndStart()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext().applicationContext
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))

        _binding = FragmentEmergencyMapBinding.bind(view)

        // Donanım Servislerini Başlat
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Butonlar
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnStopTracking.setOnClickListener { stopLiveTracking() }

        // Arayüzü hazırla
        setupBottomSheetAndList()

        // --- İZİN VE DONANIM KONTROLÜ İLE BAŞLA ---
        checkPermissionsAndStart()
    }

    // 1. ADIM: İZİNLERİ KONTROL ET
    private fun checkPermissionsAndStart() {
        val perms = mutableListOf<String>()

        // Konum İzni (Her sürüm için şart, özellikle BLE taraması için)
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Android 12+ Bluetooth İzinleri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // Android 13+ Wi-Fi ve Bildirim
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (perms.isNotEmpty()) {
            requestPermissionLauncher.launch(perms.toTypedArray())
        } else {
            // İzinler tamsa donanımı kontrol et
            checkHardwareAndStart()
        }
    }

    // 2. ADIM: DONANIMI (BLUETOOTH/GPS) KONTROL ET
    private fun checkHardwareAndStart() {
        val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val isBtOn = bluetoothManager.adapter?.isEnabled == true

        val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val isWifiOn = wifiManager.isWifiEnabled

        val isGpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

        if (!isBtOn || !isGpsOn || !isWifiOn) {
            showHardwareDialog(isBtOn, isWifiOn, isGpsOn)
        } else {
            // HER ŞEY TAMAM, SİSTEMİ BAŞLAT
            startMapAndScanning()
        }
    }

    // 3. ADIM: SİSTEMİ BAŞLAT
    private fun startMapAndScanning() {
        fetchUserLocationFromDB()
        observeRealTimeData()
        startScanningService() // Servisi başlat (Dinleme Modu)
    }

    // --- DİYALOGLAR ---
    private fun showPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("İzin Gerekli")
            .setMessage("Çevredeki acil durum sinyallerini haritada görebilmek için Konum ve Bluetooth izinlerini vermeniz gerekmektedir.")
            .setPositiveButton("Ayarlara Git") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                })
            }
            .setNegativeButton("Geri Dön") { _, _ -> findNavController().popBackStack() }
            .setCancelable(false)
            .show()
    }

    private fun showHardwareDialog(isBtOn: Boolean, isWifiOn: Boolean, isGpsOn: Boolean) {
        val missing = mutableListOf<String>()
        if (!isBtOn) missing.add("Bluetooth")
        if (!isWifiOn) missing.add("Wi-Fi")
        if (!isGpsOn) missing.add("Konum (GPS)")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sinyal Alınamıyor")
            .setMessage("Çevredeki yardım çağrılarını yakalayabilmek için lütfen şunları açın:\n\n👉 ${missing.joinToString(", ")}")
            .setPositiveButton("Ayarlar") { _, _ ->
                // Genel ayarlar ekranına atar, kullanıcı oradan açar
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            .setNegativeButton("Anladım") { _, _ ->
                // İnatla açmazsa haritayı yine de göster ama veri gelmeyeceğini bilsin
                startMapAndScanning()
            }
            .show()
    }

    // --- SERVİS BAŞLATMA ---
    private fun startScanningService() {
        try {
            val intent = Intent(requireContext(), EmergencyService::class.java).apply {
                action = EmergencyService.ACTION_START_LISTENING_ONLY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- LİSTE VE HARİTA KURULUMU ---
    private fun setupBottomSheetAndList() {
        val bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        peerAdapter = PeerAdapter(currentFilteredPeers) { peer ->
            showPeerDetailsDialog(peer)
        }
        binding.rvPeers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPeers.adapter = peerAdapter

        setupFilters()
    }

    // --- VERİTABANI VE İLK KONUM ---
    private fun fetchUserLocationFromDB() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val userProfile = db.userDao().getUserProfile()
                val lat = userProfile?.latitude
                val lng = userProfile?.longitude

                withContext(Dispatchers.Main) {
                    if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                        myLocation = GeoPoint(lat, lng)
                    } else {
                        myLocation = GeoPoint(41.0082, 28.9784)
                    }
                    setupMap()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- CANLI VERİ AKIŞI ---
    private fun observeRealTimeData() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())

            db.discoveredPeerDao().getAllPeers().collect { entities ->

                val mappedPeers = entities.map { entity ->
                    EmergencyPeer(
                        name = entity.displayName,
                        id = entity.userId,
                        status = entity.status,
                        location = GeoPoint(entity.latitude, entity.longitude),
                        bloodType = entity.bloodType,
                        moveScore = entity.movementScore,
                        distance = "Hesaplanıyor...",
                        distanceVal = 0f
                    )
                }

                allPeers = mappedPeers

                if (myLocation != null) {
                    recalculateDistances(myLocation!!)
                } else {
                    applyFilterAndRefresh()
                }
            }
        }
    }

    // --- DİNAMİK KONUM DEĞİŞİMİ ---
    override fun onLocationChanged(location: Location) {
        val newGeo = GeoPoint(location.latitude, location.longitude)
        myLocation = newGeo
        updateMyMarkerOnMap(newGeo)
        recalculateDistances(newGeo)
        saveLocationToDB(location.latitude, location.longitude)
    }

    private fun updateMyMarkerOnMap(newLoc: GeoPoint) {
        drawMarkers(currentFilteredPeers)
        if (isTrackingActive) {
            binding.map.controller.animateTo(newLoc)
        }
    }

    private fun recalculateDistances(myLoc: GeoPoint) {
        allPeers.forEach { peer ->
            val distMeters = calculateDistance(myLoc, peer.location)
            peer.distanceVal = distMeters
            peer.distance = formatDistance(distMeters)
        }
        applyFilterAndRefresh()
        if (activeDialog != null && activeDialog!!.isShowing && activeDialogDistanceText != null) {
            targetPeerForCompass?.let { target ->
                val dist = calculateDistance(myLoc, target.location)
                activeDialogDistanceText?.text = dist.toInt().toString()
            }
        }
    }

    private fun saveLocationToDB(lat: Double, lng: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                db.userDao().updateUserLocation(lat, lng)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // --- TAKİP ---
    private fun startLiveTracking(target: EmergencyPeer) {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Konum izni gerekli!", Toast.LENGTH_SHORT).show()
            return
        }
        isTrackingActive = true
        targetPeerForCompass = target
        binding.btnStopTracking.visibility = View.VISIBLE
        Toast.makeText(context, "${target.name} takip ediliyor. Yürüyün!", Toast.LENGTH_LONG).show()
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, this)
        startSensor()
    }

    private fun stopLiveTracking() {
        isTrackingActive = false
        targetPeerForCompass = null
        binding.btnStopTracking.visibility = View.GONE
        Toast.makeText(context, "Takip durduruldu.", Toast.LENGTH_SHORT).show()
        locationManager.removeUpdates(this)
        stopSensor()
    }

    // --- SENSÖR ---
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()

            if (targetPeerForCompass != null && myLocation != null) {
                val myLoc = Location("").apply { latitude = myLocation!!.latitude; longitude = myLocation!!.longitude }
                val targetLoc = Location("").apply { latitude = targetPeerForCompass!!.location.latitude; longitude = targetPeerForCompass!!.location.longitude }

                targetBearing = myLoc.bearingTo(targetLoc)
                var direction = targetBearing - azimuth
                if (direction < 0) direction += 360
                activeCompassView?.rotation = direction
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startSensor() {
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }
    private fun stopSensor() {
        sensorManager.unregisterListener(this)
        activeCompassView = null
    }

    // --- YARDIMCI ---
    private fun calculateDistance(p1: GeoPoint, p2: GeoPoint): Float {
        val loc1 = Location("ProviderA").apply { latitude = p1.latitude; longitude = p1.longitude }
        val loc2 = Location("ProviderB").apply { latitude = p2.latitude; longitude = p2.longitude }
        return loc1.distanceTo(loc2)
    }

    private fun formatDistance(meters: Float): String {
        return if (meters >= 1000) String.format("%.1fkm", meters / 1000) else "${meters.toInt()}m"
    }

    // --- POPUP ---
    private fun showPeerDetailsDialog(peer: EmergencyPeer) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_peer_details, null)
        val builder = AlertDialog.Builder(requireContext()).setView(dialogView).setCancelable(true)
        activeDialog = builder.create()
        activeDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvHeader = dialogView.findViewById<TextView>(R.id.tvStatusHeader)
        val tvName = dialogView.findViewById<TextView>(R.id.tvDetailName)
        val tvId = dialogView.findViewById<TextView>(R.id.tvDetailId)
        val tvBlood = dialogView.findViewById<TextView>(R.id.tvBloodType)
        val tvDist = dialogView.findViewById<TextView>(R.id.tvDetailDistance)
        activeDialogDistanceText = tvDist

        val progressMove = dialogView.findViewById<ProgressBar>(R.id.progressMovement)
        val tvMoveWarning = dialogView.findViewById<TextView>(R.id.tvMovementWarning)
        val btnClose = dialogView.findViewById<View>(R.id.btnCloseDialog)
        val btnTrack = dialogView.findViewById<View>(R.id.btnTrackTarget)
        val imgCompass = dialogView.findViewById<ImageView>(R.id.imgCompassNeedle)

        tvName.text = peer.name
        tvId.text = "ID: ${peer.id}"
        tvBlood.text = peer.bloodType
        tvDist.text = peer.distanceVal.toInt().toString()
        progressMove.progress = peer.moveScore

        if (peer.status.contains("Kritik", ignoreCase = true)) {
            tvHeader.text = "HAREKETSİZ - KRİTİK SİNYAL"
            tvHeader.setBackgroundColor(Color.parseColor("#FF5252"))
            tvMoveWarning.visibility = View.VISIBLE
        } else {
            tvHeader.text = "HAREKETLİ - ACİL ÇAĞRI"
            tvHeader.setBackgroundColor(Color.parseColor("#FFAB00"))
            tvMoveWarning.visibility = View.GONE
        }

        if (myLocation != null) {
            val myLoc = Location("").apply { latitude = myLocation!!.latitude; longitude = myLocation!!.longitude }
            val targetLoc = Location("").apply { latitude = peer.location.latitude; longitude = peer.location.longitude }
            targetBearing = myLoc.bearingTo(targetLoc)
            activeCompassView = imgCompass
            targetPeerForCompass = peer
            startSensor()
        }

        btnClose.setOnClickListener { activeDialog?.dismiss() }
        btnTrack.setOnClickListener {
            activeDialog?.dismiss()
            startLiveTracking(peer)
        }

        activeDialog?.setOnDismissListener {
            activeDialog = null
            activeCompassView = null
            activeDialogDistanceText = null
            if (!isTrackingActive) {
                stopSensor()
                targetPeerForCompass = null
            }
        }
        activeDialog?.show()
    }

    // --- HARİTA ---
    private fun setupMap() {
        val map = binding.map
        if (myLocation == null) return
        val userPos = myLocation!!

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.setMultiTouchControls(true)
        map.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)

        val radiusDegrees = 0.035
        mapBoundary = BoundingBox(
            userPos.latitude + radiusDegrees, userPos.longitude + radiusDegrees,
            userPos.latitude - radiusDegrees, userPos.longitude - radiusDegrees
        )
        map.setScrollableAreaLimitDouble(mapBoundary)

        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                if (mapBoundary != null) {
                    val center = map.mapCenter
                    if (center.latitude > mapBoundary!!.latNorth || center.latitude < mapBoundary!!.latSouth ||
                        center.longitude > mapBoundary!!.lonEast || center.longitude < mapBoundary!!.lonWest) {
                        val newLat = center.latitude.coerceIn(mapBoundary!!.latSouth, mapBoundary!!.latNorth)
                        val newLon = center.longitude.coerceIn(mapBoundary!!.lonWest, mapBoundary!!.lonEast)
                        map.controller.setCenter(GeoPoint(newLat, newLon))
                        return true
                    }
                }
                return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean = false
        })

        map.minZoomLevel = 15.0
        map.maxZoomLevel = 21.0
        val mapController = map.controller
        mapController.setZoom(16.5)
        mapController.setCenter(userPos)

        drawMarkers(currentFilteredPeers)
    }

    private fun drawMarkers(peersToShow: List<EmergencyPeer>) {
        val map = binding.map
        map.overlays.clear()

        if (myLocation != null) {
            val myMarker = Marker(map)
            myMarker.position = myLocation
            myMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            myMarker.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_user_location_dot)
            myMarker.title = "Sizin Konumunuz"
            myMarker.infoWindow = null
            map.overlays.add(myMarker)
        }

        peersToShow.forEach { peer ->
            val marker = Marker(map)
            marker.position = peer.location
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            val iconRes = if (peer.status.contains("Kritik", ignoreCase = true)) R.drawable.ic_dot_critical else R.drawable.ic_dot_urgent
            marker.icon = ContextCompat.getDrawable(requireContext(), iconRes)
            marker.setOnMarkerClickListener { _, _ -> showPeerDetailsDialog(peer); true }
            map.overlays.add(marker)
        }
        map.invalidate()
    }

    // --- FİLTRELEME ---
    private fun setupFilters() {
        binding.chipAll.setOnClickListener { currentFilterMode = "ALL"; applyFilterAndRefresh() }
        binding.chipCritical.setOnClickListener { currentFilterMode = "CRITICAL"; applyFilterAndRefresh() }
        binding.chipUrgent.setOnClickListener { currentFilterMode = "URGENT"; applyFilterAndRefresh() }
    }

    private fun applyFilterAndRefresh() {
        currentFilteredPeers = when (currentFilterMode) {
            "CRITICAL" -> allPeers.filter { it.status.contains("Kritik", ignoreCase = true) }
            "URGENT" -> allPeers.filter { it.status.contains("Acil", ignoreCase = true) }
            else -> allPeers
        }
        drawMarkers(currentFilteredPeers)
        peerAdapter?.updateList(currentFilteredPeers)
    }

    // --- ADAPTER ---
    class PeerAdapter(
        private var items: List<EmergencyPeer>,
        private val onItemClick: (EmergencyPeer) -> Unit
    ) : RecyclerView.Adapter<PeerAdapter.ViewHolder>() {

        fun updateList(newItems: List<EmergencyPeer>) {
            items = newItems
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvPeerName)
            val tvId: TextView = view.findViewById(R.id.tvPeerId)
            val tvDistance: TextView = view.findViewById(R.id.tvPeerDistance)
            val chipStatus: com.google.android.material.chip.Chip = view.findViewById(R.id.chipStatus)
            val iconView: ImageView = view.findViewById(R.id.imgPeerIcon)
            val statusStrip: View = view.findViewById(R.id.viewStatusStrip)
            val viewIconBg: View = view.findViewById(R.id.viewIconBg)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_emergency_peer, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvId.text = "ID: ${item.id}"
            holder.tvDistance.text = item.distance
            holder.chipStatus.text = item.status.uppercase()

            val context = holder.itemView.context
            val isCritical = item.status.contains("Kritik", ignoreCase = true)
            val colorRes = if (isCritical) R.color.sos_red else R.color.warning_orange
            val bgDrawableRes = if (isCritical) R.drawable.bg_circle_dark_red else R.drawable.bg_circle_orange
            val color = ContextCompat.getColor(context, colorRes)

            holder.chipStatus.setChipBackgroundColorResource(colorRes)
            holder.chipStatus.setChipStrokeColorResource(colorRes)
            holder.iconView.setColorFilter(color)
            holder.statusStrip.setBackgroundColor(color)
            holder.viewIconBg.setBackgroundResource(bgDrawableRes)

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = items.size
    }

    data class EmergencyPeer(
        val name: String,
        val id: String,
        val status: String,
        var distance: String,
        val location: GeoPoint,
        val bloodType: String,
        val moveScore: Int,
        var distanceVal: Float = 0f
    )

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        // Sayfaya geri dönünce donanımı tekrar kontrol et (Belki kullanıcı ayarlardan açıp gelmiştir)
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // Sadece donanım kontrolü yap, zaten başlatılmışsa tekrar başlatmaz
            val bluetoothManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val isBtOn = bluetoothManager.adapter?.isEnabled == true
            val isGpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if(!isBtOn || !isGpsOn) {
                // showHardwareDialog(isBtOn, true, isGpsOn) // Kullanıcıyı çok darlamamak için burası opsiyonel
            }
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        if (!isTrackingActive) {
            locationManager.removeUpdates(this)
            stopSensor()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        locationManager.removeUpdates(this)
        stopSensor()
    }
}