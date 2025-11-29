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
import java.util.Date

class MainActivity : AppCompatActivity() {

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Wi-Fi Manager Başlatma
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(this, mainLooper, null)

        // Temiz Başlangıç
        deletePersistentGroups()

        // Tasarım Ayarları
        window.statusBarColor = getColor(R.color.background_dark)
        window.navigationBarColor = getColor(R.color.background_dark)

        setContentView(R.layout.activity_main)
        setupBottomNav()

        // --- MERKEZİ MESAJ DİNLEYİCİSİ (HEARTBEAT) ---
        // Uygulama açık olduğu sürece mesajları dinler ve DB'ye yazar.
        setupCentralMessageListener()
    }

    private fun setupCentralMessageListener() {
        SocketManager.onMessageReceived = { incomingText ->
            Log.d("KENET_MAIN", "Mesaj Ana Merkezde Yakalandı: $incomingText")

            // Mesajın kimden geldiğini bulmamız lazım.
            // Şimdilik P2P yapısında tek bir bağlantı olduğu için aktif bağlantıyı varsayabiliriz
            // veya mesaj paketinin içine senderID koyabiliriz.
            // Şimdilik "Client" veya "Host" ayrımı olmadan, SocketManager'ın bağlı olduğu adresi almamız lazım.
            // Basitlik adına, PeersFragment'ta belirlediğimiz logic üzerinden gideceğiz ama
            // en doğrusu paketin içinde ID olmasıdır. Şimdilik "Unknown" veya varsa kayıtlı ID'yi kullanalım.

            // Geçici Çözüm: Mesajı kaydedelim. ChatPartnerId'yi daha akıllıca bulmak gerekebilir.
            // Ama şimdilik, PeersFragment connection info'yu biliyor.
            // Burada basitçe DB'ye yazacağız. SenderID'yi SocketManager'dan almaya çalışalım veya
            // "CurrentChat" mantığı kuralım.

            // Not: En sağlamı mesajın içinde JSON olarak {sender: "...", msg: "..."} olmasıdır.
            // Şimdilik düz metin olduğu için varsayılan bir ID kullanacağız veya
            // PeersFragment'taki currentHostAddress'i buraya taşıyacağız.

            // ACİL ÇÖZÜM: Mesajı veritabanına "Gelen" olarak kaydet.
            // ChatDetailFragment bu veriyi DB'den okuyacak.
            saveMessageToDb(incomingText)
        }
    }

    private fun saveMessageToDb(text: String) {
        // Not: P2P ağlarında karşı tarafın IP/MAC adresini dinamik almak zordur.
        // Bu örnekte karşı tarafın ID'sini şimdilik "TargetDevice" olarak sabitliyoruz.
        // Gerçek uygulamada el sıkışma (Handshake) sırasında bu ID alınır.

        // Önemli: ChatDetailFragment'ta deviceAddress neyse burada da o olmalı.
        // Wi-Fi Direct IP'leri (192.168.49.x) değişkendir.
        // Şimdilik test için "192.168.49.1" (Genelde Host IP'si) varsayalım veya
        // Fragment'tan gelen argümanı Global bir değişkene atayalım.

        // Bu sorunu aşmak için Global bir "ConnectedDeviceAddress" değişkeni kullanabiliriz.
        val partnerId = GlobalVariables.currentPeerAddress ?: "Unknown_Device"

        val db = AppDatabase.getDatabase(this)
        val myId = this.myDeviceId

        val message = MessageEntity(
            senderId = partnerId,
            receiverId = myId,
            chatPartnerId = partnerId,
            content = text,
            timestamp = Date().time,
            isSent = false, // Gelen Mesaj
            isRead = false  // Okunmadı
        )

        lifecycleScope.launch(Dispatchers.IO) {
            db.messageDao().insertMessage(message)
            Log.d("KENET_DB", "Mesaj DB'ye yazıldı: $text")
        }
    }

    private fun setupBottomNav() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)

        bottomNav.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.chatDetailFragment -> bottomNav.visibility = View.GONE
                else -> bottomNav.visibility = View.VISIBLE
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

// Basit bir Global Değişken (IP Adresini Tutmak İçin)
object GlobalVariables {
    var currentPeerAddress: String? = null
}