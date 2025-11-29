package com.eneskocamaan.kenet

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

object SocketManager {

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val executor = Executors.newCachedThreadPool() // Single yerine Cached kullandık, daha esnek
    private val handler = Handler(Looper.getMainLooper())

    var onMessageReceived: ((String) -> Unit)? = null

    // 1. SERVER BAŞLAT (Host Cihaz)
    fun startServer() {
        // Eğer zaten bir soket varsa ve bağlıysa tekrar açma
        if (socket != null && socket!!.isConnected && !socket!!.isClosed) {
            Log.d("KENET_SOCKET", "Server zaten açık, işlem atlandı.")
            return
        }

        executor.execute {
            try {
                // ServerSocket'i oluştur ve portu yeniden kullanmaya izin ver
                val serverSocket = ServerSocket()
                serverSocket.reuseAddress = true
                serverSocket.bind(InetSocketAddress(8888))

                Log.d("KENET_SOCKET", "Server: 8888 portunda dinleniyor...")

                socket = serverSocket.accept() // Bloklar, bağlantı bekler
                Log.d("KENET_SOCKET", "Server: İstemci Bağlandı! IP: ${socket?.inetAddress?.hostAddress}")

                setupStreams()
            } catch (e: Exception) {
                Log.e("KENET_SOCKET", "Server Başlatma Hatası: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // 2. CLIENT BAŞLAT (Client Cihaz)
    fun startClient(hostAddress: String) {
        if (socket != null && socket!!.isConnected && !socket!!.isClosed) {
            Log.d("KENET_SOCKET", "Client zaten bağlı.")
            return
        }

        executor.execute {
            try {
                socket = Socket()
                Log.d("KENET_SOCKET", "Client: $hostAddress adresine bağlanılıyor...")

                // 5 saniye timeout ile bağlan
                socket?.connect(InetSocketAddress(hostAddress, 8888), 5000)
                Log.d("KENET_SOCKET", "Client: Bağlantı Başarılı!")

                setupStreams()
            } catch (e: Exception) {
                Log.e("KENET_SOCKET", "Client Bağlantı Hatası: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    // 3. AKIŞLARI AYARLA VE DİNLE
    private fun setupStreams() {
        try {
            outputStream = socket?.getOutputStream()
            inputStream = socket?.getInputStream()
            Log.d("KENET_SOCKET", "Stream'ler kuruldu, veri dinleniyor...")

            receiveData()
        } catch (e: Exception) {
            Log.e("KENET_SOCKET", "Stream Hatası: ${e.message}")
        }
    }

    private fun receiveData() {
        val buffer = ByteArray(1024)
        var bytes: Int

        while (socket != null && socket!!.isConnected && !socket!!.isClosed) {
            try {
                bytes = inputStream?.read(buffer) ?: -1

                if (bytes > 0) {
                    val incomingMessage = String(buffer, 0, bytes)
                    Log.d("KENET_SOCKET", "Gelen Veri: $incomingMessage")

                    handler.post {
                        if (onMessageReceived != null) {
                            onMessageReceived?.invoke(incomingMessage)
                        } else {
                            Log.w("KENET_SOCKET", "Mesaj geldi ama UI Listener (ChatDetailFragment) yok!")
                        }
                    }
                } else if (bytes == -1) {
                    Log.d("KENET_SOCKET", "Bağlantı karşı taraftan kapatıldı.")
                    break
                }
            } catch (e: Exception) {
                Log.e("KENET_SOCKET", "Okuma Hatası: ${e.message}")
                break
            }
        }
    }

    // 4. MESAJ GÖNDER (DÜZELTME: FLUSH EKLENDİ)
    fun write(message: String) {
        if (socket == null || outputStream == null) {
            Log.e("KENET_SOCKET", "Hata: Soket bağlı değil, mesaj gönderilemedi.")
            return
        }

        executor.execute {
            try {
                val data = message.toByteArray()
                outputStream?.write(data)
                outputStream?.flush() // <-- EN ÖNEMLİ KISIM: Veriyi zorla gönder
                Log.d("KENET_SOCKET", "Yazıldı (${data.size} byte): $message")
            } catch (e: Exception) {
                Log.e("KENET_SOCKET", "Yazma Hatası: ${e.message}")
            }
        }
    }

    fun close() {
        try {
            socket?.close()
            socket = null
        } catch (e: Exception) { e.printStackTrace() }
    }
}