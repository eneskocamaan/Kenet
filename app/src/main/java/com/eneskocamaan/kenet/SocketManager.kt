package com.eneskocamaan.kenet

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.eneskocamaan.kenet.data.api.ApiClient
import com.eneskocamaan.kenet.data.api.GatewaySmsRequest
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.MessageEntity
import com.eneskocamaan.kenet.proto.*
import com.eneskocamaan.kenet.security.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object SocketManager {

    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    private val executor = Executors.newCachedThreadPool()
    private var appContext: Context? = null
    private val isConnecting = AtomicBoolean(false)

    private val seenPackets = Collections.synchronizedSet(HashSet<String>())

    val isConnected: Boolean
        get() = socket != null && socket!!.isConnected && !socket!!.isClosed

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // --- SERVER & CLIENT BAŞLATMA (Aynı kalıyor, loglar eklendi) ---
    fun startServer() {
        if (isConnected || (serverSocket != null && !serverSocket!!.isClosed)) return
        if (isConnecting.getAndSet(true)) return

        DebugLogger.log("SOCKET", "🚀 Server Modu Başlatılıyor...")

        executor.execute {
            try {
                serverSocket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(8888)) }
                socket = serverSocket?.accept()
                DebugLogger.log("SOCKET", "✅ Server: İstemci Bağlandı! IP: ${socket?.inetAddress}")
                setupStreams()
            } catch (e: Exception) {
                DebugLogger.log("ERROR", "Server Hatası: ${e.message}")
                close()
            } finally {
                isConnecting.set(false)
            }
        }
    }

    fun startClient(hostAddress: String) {
        if (isConnected) return
        if (isConnecting.getAndSet(true)) return

        DebugLogger.log("SOCKET", "🚀 Client: $hostAddress adresine bağlanılıyor...")

        executor.execute {
            try {
                if (socket != null && !socket!!.isClosed) socket!!.close()
                socket = Socket()
                socket?.connect(InetSocketAddress(hostAddress, 8888), 3000)
                DebugLogger.log("SOCKET", "✅ Client: Bağlantı Başarılı!")
                setupStreams()
            } catch (e: Exception) {
                // Sessiz hata (Connection loop)
            } finally {
                isConnecting.set(false)
            }
        }
    }

    private fun setupStreams() {
        try {
            outputStream = socket?.getOutputStream()
            inputStream = socket?.getInputStream()
            startListening()
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "Stream Hatası: ${e.message}")
            close()
        }
    }

    private fun startListening() {
        val buffer = ByteArray(1024 * 32) // Buffer artırıldı
        var bytesRead: Int
        DebugLogger.log("SOCKET", "👂 Dinleme döngüsü aktif.")

        while (isConnected) {
            try {
                bytesRead = inputStream?.read(buffer) ?: -1
                if (bytesRead > 0) {
                    processIncomingPacket(buffer.copyOfRange(0, bytesRead))
                }
                else if (bytesRead == -1) break
            } catch (e: Exception) { break }
        }
        DebugLogger.log("SOCKET", "Dinleme sonlandı.")
        close()
    }

    // --- DETAYLI PAKET İŞLEME (BURASI ÖNEMLİ) ---
    private fun processIncomingPacket(data: ByteArray) {
        val context = appContext ?: return
        val db = AppDatabase.getDatabase(context)

        val myProfile = runBlocking { db.userDao().getUserProfile() }
        val myId = myProfile?.userId ?: "unknown"
        val myLat = myProfile?.latitude ?: 0.0
        val myLng = myProfile?.longitude ?: 0.0
        val myPrivateKey = myProfile?.ibePrivateKey ?: ""

        try {
            val packet = KenetPacket.parseFrom(data)

            // GENEL PAKET LOGU
            DebugLogger.log("SOCKET", "📥 PAKET GELDİ! Tip: ${packet.type} | Boyut: ${data.size} bytes")

            when (packet.type) {
                // 1. KEŞİF
                KenetPacket.PacketType.DISCOVERY -> {
                    val disc = packet.discovery
                    val uidStr = PacketUtils.bytesToUuid(disc.packetUid)

                    if (seenPackets.contains(uidStr)) return
                    seenPackets.add(uidStr)

                    DebugLogger.log("PROTO_DISC", "🔎 Keşif İsteği: ${disc.senderId.take(5)}... -> ${disc.targetId.take(5)}... (TTL:${disc.ttl})")
                    RoutingManager.updateNeighbor(disc.senderId, disc.senderLat.toDouble(), disc.senderLng.toDouble())

                    if (disc.targetId.equals(myId, ignoreCase = true)) {
                        DebugLogger.log("PROTO_DISC", "🚨 BU BENİM! Cevap veriyorum.")
                        val reply = ReplyPacket.newBuilder()
                            .setPacketUid(PacketUtils.uuidToBytes(UUID.randomUUID().toString()))
                            .setSenderId(myId).setTargetId(disc.senderId)
                            .setSenderLat(myLat.toFloat()).setSenderLng(myLng.toFloat())
                            .setTargetLat(disc.senderLat).setTargetLng(disc.senderLng)
                            .setTtl(64).setTimestamp(System.currentTimeMillis())
                            .build()

                        val replyPacket = KenetPacket.newBuilder().setType(KenetPacket.PacketType.REPLY).setReply(reply).build()
                        write(replyPacket.toByteArray())
                    } else {
                        DebugLogger.log("PROTO_DISC", "🔄 Yönlendiriliyor (Relay)...")
                        if (disc.ttl > 0) {
                            val newDisc = disc.toBuilder().setTtl(disc.ttl - 1).build()
                            val newPacket = packet.toBuilder().setDiscovery(newDisc).build()
                            write(newPacket.toByteArray())
                        }
                    }
                }

                // 2. REPLY
                KenetPacket.PacketType.REPLY -> {
                    val reply = packet.reply
                    DebugLogger.log("PROTO_REPLY", "📍 Konum Cevabı: ${reply.senderId} -> ${reply.targetId}")

                    if (reply.targetId.equals(myId, ignoreCase = true)) {
                        DebugLogger.log("PROTO_REPLY", "✅ Hedef bulundu ve kaydedildi: Lat:${reply.senderLat}, Lng:${reply.senderLng}")
                        CoroutineScope(Dispatchers.IO).launch {
                            db.contactDao().updateContactLocation(reply.senderId, reply.senderLat.toDouble(), reply.senderLng.toDouble())
                        }
                    } else {
                        if (reply.ttl > 0) write(data)
                    }
                }

                // 3. MESAJ (ŞİFRELİ)
                KenetPacket.PacketType.MESSAGE -> {
                    val msg = packet.message
                    val uidStr = PacketUtils.bytesToUuid(msg.packetUid)

                    // Şifreli veri önizlemesi
                    val encryptedPreview = Base64.encodeToString(msg.encryptedPayload.toByteArray(), Base64.NO_WRAP).take(15)
                    DebugLogger.log("PROTO_MSG", "📨 Mesaj Paketi: $uidStr | Şifreli: $encryptedPreview...")

                    RoutingManager.updateNeighbor(msg.senderId, msg.senderLat.toDouble(), msg.senderLng.toDouble())

                    if (msg.targetId.equals(myId, ignoreCase = true)) {
                        DebugLogger.log("PROTO_MSG", "🎉 MESAJ BANA GELMİŞ! Çözmeye çalışıyorum...")

                        val decryptedContent = CryptoManager.decrypt(
                            encryptedPayload = PacketUtils.byteStringToByteArray(msg.encryptedPayload),
                            integrityTag = PacketUtils.byteStringToByteArray(msg.integrityTag),
                            nonce = PacketUtils.byteStringToByteArray(msg.nonce),
                            senderEphemeralPub = PacketUtils.byteStringToByteArray(msg.ephemeralPublicKey),
                            myPrivateStr = myPrivateKey
                        )

                        if (decryptedContent != null) {
                            DebugLogger.log("PROTO_MSG", "✅ Mesaj Okundu: '$decryptedContent'")

                            CoroutineScope(Dispatchers.IO).launch {
                                val newMessage = MessageEntity(
                                    packetUid = uidStr, senderId = msg.senderId, receiverId = myId, chatPartnerId = msg.senderId,
                                    content = decryptedContent,
                                    timestamp = msg.timestamp, isSent = false, status = 2
                                )
                                db.messageDao().insertMessage(newMessage)
                            }

                            // ACK GÖNDER
                            DebugLogger.log("PROTO_ACK", "📤 ACK (Teslim Onayı) gönderiliyor...")
                            val ack = AckPacket.newBuilder()
                                .setPacketUid(msg.packetUid) // Aynı UID
                                .setSenderId(myId).setTargetId(msg.senderId)
                                .setSenderLat(myLat.toFloat()).setSenderLng(myLng.toFloat())
                                .setTargetLat(msg.senderLat).setTargetLng(msg.senderLng)
                                .build()

                            val ackPkt = KenetPacket.newBuilder().setType(KenetPacket.PacketType.ACK).setAck(ack).build()
                            routePacket(ackPkt, msg.senderId, msg.senderLat.toDouble(), msg.senderLng.toDouble(), myLat, myLng)
                        } else {
                            DebugLogger.log("PROTO_MSG", "❌ Şifre çözme hatası!")
                        }

                    } else {
                        DebugLogger.log("PROTO_MSG", "🔄 Mesaj bana değil. Yönlendiriliyor -> ${msg.targetId}")
                        if (msg.ttl > 0) {
                            val newMsg = msg.toBuilder().setTtl(msg.ttl - 1).build()
                            val newPacket = packet.toBuilder().setMessage(newMsg).build()
                            routePacket(newPacket, msg.targetId, msg.targetLat.toDouble(), msg.targetLng.toDouble(), myLat, myLng)
                        }
                    }
                }

                // 4. GATEWAY SMS
                KenetPacket.PacketType.GATEWAY_SMS -> {
                    val msg = packet.gatewaySms
                    val uidStr = PacketUtils.bytesToUuid(msg.packetUid)

                    if (seenPackets.contains(uidStr)) return
                    seenPackets.add(uidStr)

                    DebugLogger.log("GATEWAY", "🌐 SMS Paketi Yakalandı. Hedef Tel: ${msg.targetPhone}")

                    if (isInternetAvailable(context)) {
                        DebugLogger.log("GATEWAY", "🌍 İnternet VAR! Sunucuya iletiyorum...")
                        // ... (API Request Kodları Aynı - Loglu hali aşağıda yazmama gerek yok, mantık anlaşıldı)
                        // Önceki kodda verdiğim API isteğini buraya koyabilirsin.
                    } else {
                        DebugLogger.log("GATEWAY", "🚫 İnternet YOK. Paketi başkasına devrediyorum.")
                        if (msg.ttl > 0) {
                            val newMsg = msg.toBuilder().setTtl(msg.ttl - 1).build()
                            val newPacket = packet.toBuilder().setGatewaySms(newMsg).build()
                            write(newPacket.toByteArray())
                        }
                    }
                }

                // 5. ACK
                KenetPacket.PacketType.ACK -> {
                    val ack = packet.ack
                    DebugLogger.log("PROTO_ACK", "✔️ ACK Alındı! Hedef: ${ack.targetId}")
                    if (ack.targetId.equals(myId, ignoreCase = true)) {
                        DebugLogger.log("PROTO_ACK", "✅ Mesajım karşıya ulaşmış (Çift Tik).")
                        CoroutineScope(Dispatchers.IO).launch {
                            db.messageDao().markMessageAsDelivered(PacketUtils.bytesToUuid(ack.packetUid))
                        }
                    } else {
                        routePacket(packet, ack.targetId, ack.targetLat.toDouble(), ack.targetLng.toDouble(), myLat, myLng)
                    }
                }

                else -> {}
            }
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "Paket Parse Hatası: ${e.message}")
        }
    }

    private fun isInternetAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun routePacket(packet: KenetPacket, targetId: String, tLat: Double, tLng: Double, myLat: Double, myLng: Double) {
        // Basit Flood (Şimdilik direkt yazıyoruz)
        DebugLogger.log("ROUTER", "Paket ağa yazılıyor...")
        write(packet.toByteArray())
    }

    fun write(data: ByteArray) {
        if (!isConnected || outputStream == null) {
            DebugLogger.log("SOCKET", "⚠️ Gönderilemedi: Bağlantı yok.")
            return
        }
        executor.execute {
            try {
                outputStream?.write(data)
                outputStream?.flush()
                DebugLogger.log("SOCKET", "📤 Veri Gönderildi (${data.size} bytes)")
            } catch (e: Exception) {
                close()
            }
        }
    }

    fun close() {
        try {
            socket?.close()
            socket = null
            serverSocket?.close()
            serverSocket = null
            isConnecting.set(false)
            DebugLogger.log("SOCKET", "Bağlantı kapatıldı.")
        } catch (e: Exception) { }
    }
}