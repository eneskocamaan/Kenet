package com.eneskocamaan.kenet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkLoginAndStartService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Veritabanı işlemleri için Context
        SocketManager.init(this)

        window.statusBarColor = getColor(R.color.background_dark)
        window.navigationBarColor = getColor(R.color.background_dark)

        setupBottomNav()
        checkPermissionsAndLoginStatus()
    }

    private fun checkPermissionsAndLoginStatus() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            checkLoginAndStartService()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun checkLoginAndStartService() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val myId = db.userDao().getMyUserId()

            if (!myId.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    val serviceIntent = Intent(this@MainActivity, com.eneskocamaan.kenet.service.NetworkService::class.java)
                    startService(serviceIntent)
                }
            }
        }
    }

    private fun setupBottomNav() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
        val navController = navHostFragment.navController
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)

        if (bottomNav != null) {
            bottomNav.setupWithNavController(navController)
            navController.addOnDestinationChangedListener { _, destination, _ ->
                when (destination.id) {
                    R.id.splashFragment,
                    R.id.loginFragment,
                    R.id.profileSetupFragment,
                    R.id.contactSelectionFragment,
                    R.id.chatDetailFragment -> {
                        bottomNav.visibility = View.GONE
                    }
                    else -> {
                        bottomNav.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // --- TEMİZLİK OPERASYONU ---
        // Uygulama kapatıldığında servisi de durdur ki arka planda hayalet bağlantı kalmasın.
        val serviceIntent = Intent(this, com.eneskocamaan.kenet.service.NetworkService::class.java)
        stopService(serviceIntent)

        SocketManager.close()
    }
}