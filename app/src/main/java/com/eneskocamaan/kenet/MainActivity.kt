package com.eneskocamaan.kenet

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.MessageEntity
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.util.Date

class MainActivity : AppCompatActivity() {

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    // Cihazın kendi ID'sini SharedPreferences'tan okuyan yardımcı özellik
    // (Bunu RegistrationFragment'ta kaydettiğini varsayıyoruz)
    private val myDeviceId: String
        get() = getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("MY_DEVICE_ID", "MyDevice") ?: "MyDevice"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Wi-Fi Manager Başlatma
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(this, mainLooper, null)

        // Temiz Başlangıç
        deletePersistentGroups()

        // Tasarım Ayarları (Durum çubuğu renkleri)
        window.statusBarColor = getColor(R.color.background_dark)
        window.navigationBarColor = getColor(R.color.background_dark)

        setContentView(R.layout.activity_main)

        // Bottom Navigation Kurulumu (Görünürlük ayarları burada)
        setupBottomNav()

        // --- MERKEZİ MESAJ DİNLEYİCİSİ ---
        setupCentralMessageListener()
    }

    private fun setupCentralMessageListener() {
        SocketManager.onMessageReceived = { incomingText ->
            Log.d("KENET_MAIN", "Mesaj Ana Merkezde Yakalandı: $incomingText")

            // Gelen veriyi JSON olarak parse etmeye çalışıyoruz.
            // Beklenen Format: { "senderId": "CihazID", "msg": "Merhaba" }
            try {
                val jsonObject = JSONObject(incomingText)
                val senderId = jsonObject.optString("senderId", "Unknown_Sender")
                val messageContent = jsonObject.optString("msg", "")

                // Eğer mesaj içeriği boş değilse kaydet
                if (messageContent.isNotEmpty()) {
                    saveMessageToDb(messageContent, senderId)
                }

            } catch (e: JSONException) {
                // Eğer gelen veri JSON formatında değilse (Eski versiyon veya düz metin)
                Log.e("KENET_JSON", "Mesaj JSON değil, düz metin olarak işleniyor.")
                saveMessageToDb(incomingText, null)
            }
        }
    }

    private fun saveMessageToDb(text: String, explicitSenderId: String?) {
        // Eğer JSON içinden ID geldiyse onu kullan, yoksa Global değişkene bak, o da yoksa "Unknown"
        val partnerId = explicitSenderId ?: GlobalVariables.currentPeerAddress ?: "Unknown_Device"

        val db = AppDatabase.getDatabase(this)
        val myId = this.myDeviceId

        val message = MessageEntity(
            senderId = partnerId,
            receiverId = myId,
            chatPartnerId = partnerId, // Sohbet bu ID ile eşleşir
            content = text,
            timestamp = Date().time,
            isSent = false, // Gelen Mesaj
            isRead = false  // Okunmadı
        )

        lifecycleScope.launch(Dispatchers.IO) {
            db.messageDao().insertMessage(message)
            Log.d("KENET_DB", "Mesaj DB'ye yazıldı. Kimden: $partnerId - İçerik: $text")
        }
    }

    private fun setupBottomNav() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)

        bottomNav.setupWithNavController(navController)

        // Navigasyon hedefine göre BottomBar görünürlüğünü ayarla
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                // ilk açılan ekranda gizle
                R.id.splashFragment,

                // 1. Giriş ve Profil Kurulumu (GİZLE)
                R.id.loginFragment,
                R.id.profileSetupFragment,
                R.id.contactSelectionFragment,

                // 2. Sohbet Detayı (GİZLE - Klavye açılacağı için)
                R.id.chatDetailFragment -> {
                    bottomNav.visibility = View.GONE
                }

                // 3. Ana Ekranlar (GÖSTER: Peers, Emergency, Settings)
                else -> {
                    bottomNav.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        deletePersistentGroups()
        SocketManager.close()
    }

    private fun deletePersistentGroups() {
        if (manager != null && channel != null) {
            manager?.removeGroup(channel, null)
        }
    }
}

// Uygulama genelinde anlık bağlandığımız kişinin adresini tutmak için
object GlobalVariables {
    var currentPeerAddress: String? = null
}
