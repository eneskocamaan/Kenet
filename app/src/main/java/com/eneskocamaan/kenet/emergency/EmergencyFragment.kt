package com.eneskocamaan.kenet.emergency

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.eneskocamaan.kenet.R
import com.eneskocamaan.kenet.databinding.FragmentEmergencyBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class EmergencyFragment : Fragment(R.layout.fragment_emergency) {
    private var _binding: FragmentEmergencyBinding? = null
    private val binding get() = _binding!!
    private val TAG = "KENET_LOG_FRAGMENT"
    private var pulseAnimatorSet: AnimatorSet? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isHeldDown = false
    private var isBroadcastingJustStarted = false
    private val HOLD_DURATION = 3000L
    private var selectedModeId = R.id.btnBle
    private var pendingAction: (() -> Unit)? = null

    // İzin sonucu geldiğinde çalışacak kod
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val anyDenied = permissions.entries.any { !it.value }
        if (!anyDenied) {
            Log.i(TAG, "✅ Tüm izinler verildi, işlem başlatılıyor.")
            pendingAction?.invoke()
            pendingAction = null
        } else {
            Log.e(TAG, "❌ Bazı izinler reddedildi.")
            showSettingsDialog()
        }
    }

    private val longPressRunnable = Runnable { if (isHeldDown) startEmergency() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentEmergencyBinding.bind(view)
        setupButtons(); setupTools()
    }

    override fun onResume() {
        super.onResume(); restoreState(); updateConnectionStatusIcon()
    }

    // --- İZİN KONTROLÜ ---
    private fun checkPermissionsAndRun(action: () -> Unit) {
        val perms = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (perms.isNotEmpty()) {
            Log.w(TAG, "⚠️ Eksik izinler var: $perms")
            pendingAction = action
            requestPermissionLauncher.launch(perms.toTypedArray())
        } else {
            action()
        }
    }

    private fun showSettingsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("İzin Gerekli")
            .setMessage("Bu özelliği kullanabilmek için gerekli izinleri vermelisiniz.")
            .setPositiveButton("Ayarlar") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // --- ARAÇLARIN KURULUMU ---
    private fun setupTools() {
        binding.btnFlashlight.setOnClickListener { checkPermissionsAndRun { toggleFlashlightAction() } }
        binding.btnWhistle.setOnClickListener { checkPermissionsAndRun { toggleWhistleAction() } }

        // YENİ ÖZELLİKLER
        binding.btnScreenStrobe.setOnClickListener { toggleScreenStrobe() }
        binding.btnLocationCard.setOnClickListener { checkPermissionsAndRun { showLocationCard() } }
    }

    // 1. FENER
    private fun toggleFlashlightAction() {
        val intent = Intent(requireContext(), EmergencyService::class.java).apply { action = EmergencyService.ACTION_TOGGLE_FLASHLIGHT }
        startServiceSafe(intent)
        handler.postDelayed({ updateToolStyle(binding.btnFlashlight, EmergencyService.isFlashlightOn) }, 100)
    }

    // 2. DÜDÜK
    private fun toggleWhistleAction() {
        val intent = Intent(requireContext(), EmergencyService::class.java).apply { action = EmergencyService.ACTION_TOGGLE_WHISTLE }
        startServiceSafe(intent)
        handler.postDelayed({ updateToolStyle(binding.btnWhistle, EmergencyService.isWhistleOn) }, 100)
    }

    // 3. EKRAN FLAŞÖRÜ (Screen Strobe)
    private var isStrobeOn = false
    private var strobeDialog: android.app.Dialog? = null

    private fun toggleScreenStrobe() {
        if (isStrobeOn) {
            // Açıksa kapat
            strobeDialog?.dismiss()
            strobeDialog = null
            isStrobeOn = false
            updateToolStyle(binding.btnScreenStrobe, false)
        } else {
            // Kapalıysa aç
            isStrobeOn = true
            updateToolStyle(binding.btnScreenStrobe, true)

            // Tam ekran diyalog oluştur
            strobeDialog = android.app.Dialog(requireContext(), android.R.style.Theme_NoTitleBar_Fullscreen)

            // Tıklamayı algılayacak olan ana kapsayıcı (Root View) oluşturuyoruz
            val rootLayout = android.widget.FrameLayout(requireContext())
            rootLayout.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Kapatmak için tıklama özelliğini BURAYA veriyoruz (Kesin çözüm)
            rootLayout.isClickable = true
            rootLayout.isFocusable = true
            rootLayout.setOnClickListener {
                strobeDialog?.dismiss()
            }

            strobeDialog?.setContentView(rootLayout)

            // Ekran parlaklığını %100 yap
            val layoutParams = strobeDialog?.window?.attributes
            layoutParams?.screenBrightness = 1f
            strobeDialog?.window?.attributes = layoutParams

            strobeDialog?.show()

            // Yanıp sönme döngüsü
            val colors = listOf(Color.RED, Color.WHITE)
            var colorIndex = 0
            val strobeHandler = Handler(Looper.getMainLooper())

            val strobeRunnable = object : Runnable {
                override fun run() {
                    if (!isStrobeOn || strobeDialog?.isShowing != true) return

                    // Rengi doğrudan oluşturduğumuz layout'a veriyoruz
                    rootLayout.setBackgroundColor(colors[colorIndex])

                    colorIndex = (colorIndex + 1) % colors.size
                    strobeHandler.postDelayed(this, 300) // 300ms hızla yanıp sön
                }
            }
            strobeHandler.post(strobeRunnable)

            // Diyalog kapandığında (geri tuşu veya tıklama ile) temizlik yap
            strobeDialog?.setOnDismissListener {
                isStrobeOn = false
                updateToolStyle(binding.btnScreenStrobe, false)
                strobeHandler.removeCallbacks(strobeRunnable)
            }

            Toast.makeText(requireContext(), "Kapatmak için ekrana dokunun", Toast.LENGTH_SHORT).show()
        }
    }

    // 4. KONUM KARTI
    private fun showLocationCard() {
        val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

        @SuppressLint("MissingPermission")
        val loc = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

        val lat = loc?.latitude ?: 0.0
        val lng = loc?.longitude ?: 0.0
        val acc = loc?.accuracy ?: 0f

        val coordText = String.format("%.5f, %.5f", lat, lng)
        val msg = if (lat != 0.0)
            "📍 **$coordText**\n(Hata Payı: ${acc.toInt()}m)\n\nBu koordinatları arama kurtarma ekiplerine bildirin."
        else
            "Konum alınıyor, lütfen açık havaya çıkın..."

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Mevcut Koordinatlar")
            .setMessage(msg)
            .setIcon(R.drawable.ic_location)
            .setPositiveButton("Kopyala") { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Konum", coordText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Koordinatlar kopyalandı!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    // --- YARDIMCI METOTLAR ---
    private fun startServiceSafe(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) requireContext().startForegroundService(intent)
        else requireContext().startService(intent)
    }

    private fun restoreState() {
        if (EmergencyService.isBroadcasting) { startPulseAnimation(); binding.tvSosStatus.text = "DURDURMAK\nİÇİN DOKUN" }
        else binding.tvSosStatus.text = "BASILI TUT"
        updateToolStyle(binding.btnFlashlight, EmergencyService.isFlashlightOn)
        updateToolStyle(binding.btnWhistle, EmergencyService.isWhistleOn)
        updateToolStyle(binding.btnScreenStrobe, isStrobeOn)
    }

    private fun updateConnectionStatusIcon() {
        val isBleMode = selectedModeId == R.id.btnBle
        val iconRes = if (isBleMode) R.drawable.ic_bluetooth else R.drawable.ic_wifi
        val isEnabled = checkHardwareStatus(isBleMode)
        val colorRes = if (isEnabled) R.color.primary_color else R.color.text_gray
        binding.btnConnectionStatus.setImageResource(iconRes)
        binding.btnConnectionStatus.setColorFilter(ContextCompat.getColor(requireContext(), colorRes))
    }

    private fun checkHardwareStatus(isBleMode: Boolean): Boolean {
        return if (isBleMode) {
            (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
        } else {
            (requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupButtons() {
        binding.cardModeSelector.post { initializeModeSelector() }

        binding.btnInfo.setOnClickListener { showInfoDialog() }

        binding.btnConnectionStatus.setOnClickListener {
            val isBleMode = selectedModeId == R.id.btnBle
            val isEnabled = checkHardwareStatus(isBleMode)
            Toast.makeText(requireContext(), "${if(isBleMode) "Bluetooth" else "Wi-Fi"} ${if(isEnabled) "AÇIK" else "KAPALI"}", Toast.LENGTH_SHORT).show()
        }
        binding.btnBle.setOnClickListener { handleModeChange(R.id.btnBle) }
        binding.btnWifi.setOnClickListener { handleModeChange(R.id.btnWifi) }

        binding.btnSosContainer.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (EmergencyService.isBroadcasting) return@setOnTouchListener true
                    isHeldDown = true
                    handler.postDelayed(longPressRunnable, HOLD_DURATION)
                    binding.btnSosContainer.animate().scaleX(0.9f).scaleY(0.9f).setDuration(200).start()
                    binding.tvSosStatus.text = "BEKLE..."
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isBroadcastingJustStarted) {
                        isBroadcastingJustStarted = false
                        binding.btnSosContainer.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                        return@setOnTouchListener true
                    }
                    if (EmergencyService.isBroadcasting) {
                        stopEmergency()
                        binding.btnSosContainer.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                        return@setOnTouchListener true
                    }
                    if (isHeldDown) {
                        isHeldDown = false
                        handler.removeCallbacks(longPressRunnable)
                        binding.btnSosContainer.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                        binding.tvSosStatus.text = "BASILI TUT"
                    }
                    view.performClick()
                    true
                }
                else -> false
            }
        }
        binding.cardRadar.setOnClickListener { findNavController().navigate(R.id.action_emergencyFragment_to_emergencyMapFragment) }
    }

    private fun startEmergency() {
        val isBleMode = selectedModeId == R.id.btnBle
        if (!checkHardwareStatus(isBleMode)) {
            isHeldDown = false
            binding.btnSosContainer.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            binding.tvSosStatus.text = "BASILI TUT"
            Toast.makeText(context, "Lütfen önce bağlantıyı açın!", Toast.LENGTH_LONG).show()
            return
        }

        checkPermissionsAndRun {
            isHeldDown = false; isBroadcastingJustStarted = true
            val intent = Intent(requireContext(), EmergencyService::class.java).apply {
                action = EmergencyService.ACTION_START_BROADCAST
                putExtra(EmergencyService.EXTRA_MODE, if (isBleMode) "BLE" else "WIFI")
            }
            startServiceSafe(intent)
            startPulseAnimation()
            binding.btnSosContainer.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            binding.tvSosStatus.text = "DURDURMAK\nİÇİN DOKUN"
        }
    }

    private fun stopEmergency() {
        isBroadcastingJustStarted = false
        startServiceSafe(Intent(requireContext(), EmergencyService::class.java).apply { action = EmergencyService.ACTION_STOP_BROADCAST })
        pulseAnimatorSet?.cancel()
        listOf(binding.rippleView1, binding.rippleView2, binding.rippleView3).forEach { it.alpha = 0f; it.scaleX = 1f; it.scaleY = 1f }
        binding.tvSosStatus.text = "BASILI TUT"
    }

    private fun initializeModeSelector() {
        val width = binding.cardModeSelector.width
        binding.viewSelectionBackground.layoutParams.width = width / 2
        updateConnectionStatusIcon()
        val isBle = selectedModeId == R.id.btnBle
        binding.viewSelectionBackground.translationX = if (isBle) 0f else (width / 2).toFloat()
        val white = ContextCompat.getColor(requireContext(), R.color.white)
        val gray = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        binding.btnBle.setTextColor(if (isBle) white else gray)
        binding.btnWifi.setTextColor(if (isBle) gray else white)
    }

    private fun handleModeChange(newId: Int) {
        if (selectedModeId == newId) return
        selectedModeId = newId
        animateModeSelector(newId)
        updateConnectionStatusIcon()
        if (EmergencyService.isBroadcasting) stopEmergency()
    }

    private fun animateModeSelector(id: Int) {
        val targetX = if (id == R.id.btnBle) 0f else binding.cardModeSelector.width.toFloat() / 2
        binding.viewSelectionBackground.animate().translationX(targetX).setDuration(250).setInterpolator(AccelerateDecelerateInterpolator()).start()
        val white = ContextCompat.getColor(requireContext(), R.color.white)
        val gray = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        binding.btnBle.setTextColor(if (id == R.id.btnBle) white else gray)
        binding.btnWifi.setTextColor(if (id == R.id.btnBle) gray else white)
    }

    private fun startPulseAnimation() {
        pulseAnimatorSet?.cancel(); pulseAnimatorSet = AnimatorSet()
        val animators = mutableListOf<android.animation.Animator>()
        listOf(binding.rippleView1, binding.rippleView2, binding.rippleView3).forEachIndexed { i, v ->
            v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f
            animators.add(ObjectAnimator.ofFloat(v, "scaleX", 1f, 15f).apply { repeatCount = ObjectAnimator.INFINITE; startDelay = i * 1000L })
            animators.add(ObjectAnimator.ofFloat(v, "scaleY", 1f, 15f).apply { repeatCount = ObjectAnimator.INFINITE; startDelay = i * 1000L })
            animators.add(ObjectAnimator.ofFloat(v, "alpha", 0.6f, 0f).apply { repeatCount = ObjectAnimator.INFINITE; startDelay = i * 1000L })
        }
        pulseAnimatorSet?.apply { playTogether(animators); duration = 3000L; interpolator = LinearInterpolator(); start() }
    }

    private fun updateToolStyle(btn: MaterialButton, isOn: Boolean) {
        val primary = ContextCompat.getColor(requireContext(), R.color.primary_color)
        val trans = ContextCompat.getColor(requireContext(), android.R.color.transparent)
        val sec = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        val white = ContextCompat.getColor(requireContext(), R.color.white)
        btn.backgroundTintList = ColorStateList.valueOf(if (isOn) primary else trans)
        btn.iconTint = ColorStateList.valueOf(white)
        btn.strokeColor = ColorStateList.valueOf(if (isOn) primary else sec)
    }

    private fun showInfoDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Acil Durum Sistemi")
            .setIcon(R.drawable.ic_info_outline)
            .setMessage(
                "Bu sistem, şebeke ve internet olmadan çevredeki diğer KENET kullanıcılarıyla iletişim kurmanızı sağlar.\n\n" +
                        "🆘 **SOS Modu:** Butona 3 saniye basılı tutun. Telefonunuz çevredeki kullanıcılara 'Acil Durum' sinyali yayar.\n\n" +
                        "📡 **Bluetooth:** Düşük güç tüketimi, orta menzil.\n" +
                        "🛜 **Wi-Fi:** Yüksek güç tüketimi, geniş menzil.\n\n" +
                        "Harita ekranına geçerek çevredeki yardım çağrılarını görebilirsiniz."
            )
            .setPositiveButton("Tamam", null)
            .show()
    }

    override fun onDestroyView() { super.onDestroyView(); pulseAnimatorSet?.cancel(); _binding = null; handler.removeCallbacksAndMessages(null) }
}