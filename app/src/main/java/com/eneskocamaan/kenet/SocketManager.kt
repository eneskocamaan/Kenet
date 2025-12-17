package com.eneskocamaan.kenet

import android.content.Context
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.MessageEntity
import com.eneskocamaan.kenet.proto.AckPacket
import com.eneskocamaan.kenet.proto.KenetPacket
import com.eneskocamaan.kenet.proto.MessagePacket
import com.eneskocamaan.kenet.proto.ReplyPacket
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

    // Deduplication (Tekilleştirme) için
    private val seenPackets = Collections.synchronizedSet(HashSet<String>())

    val isConnected: Boolean
        get() = socket != null && socket!!.isConnected && !socket!!.isClosed

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // --- 1. SERVER BAŞLATMA ---
    fun startServer() {
        if (isConnected || (serverSocket != null && !serverSocket!!.isClosed)) return
        if (isConnecting.getAndSet(true)) return

        DebugLogger.log("SOCKET", "Server Modu: Başlatılıyor...")

        executor.execute {
            try {
                serverSocket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(8888)) }
                socket = serverSocket?.accept() // İstemci bekleniyor...

                DebugLogger.log("SOCKET", "✅ Server: Bir İstemci Bağlandı!")
                setupStreams()
            } catch (e: Exception) {
                DebugLogger.log("ERROR", "Server Hatası: ${e.message}")
                close()
            } finally {
                isConnecting.set(false)
            }
        }
    }

    // --- 2. CLIENT BAŞLATMA ---
    fun startClient(hostAddress: String) {
        if (isConnected) return
        if (isConnecting.getAndSet(true)) return

        DebugLogger.log("SOCKET", "Client Modu: $hostAddress adresine bağlanılıyor...")

        executor.execute {
            try {
                if (socket != null && !socket!!.isClosed) socket!!.close()
                socket = Socket()
                socket?.connect(InetSocketAddress(hostAddress, 8888), 3000)

                DebugLogger.log("SOCKET", "✅ Client: Bağlantı Başarılı!")
                setupStreams()
            } catch (e: Exception) {
                // DebugLogger.log("ERROR", "Client Bağlantı Hatası: ${e.message}") // Çok spam yaparsa kapat
                Thread.sleep(1000)
            } finally {
                isConnecting.set(false)
            }
        }
    }

    private fun setupStreams() {
        try {
            outputStream = socket?.getOutputStream()
            inputStream = socket?.getInputStream()

            // Bağlantı kurulur kurulmaz DTN kontrolü yap
            checkPendingMessages()
            startListening()
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "Stream Hatası: ${e.message}")
            close()
        }
    }

    private fun checkPendingMessages() {
        val context = appContext ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(context)
            val pendingMessages = db.messageDao().getPendingMessages()
            if (pendingMessages.isNotEmpty()) {
                DebugLogger.log("DTN", "📦 ${pendingMessages.size} adet bekleyen mesaj bulundu. Yeniden işleniyor...")
                // İleride buraya retry mantığını ekleyeceksin
            }
        }
    }

    private fun startListening() {
        val buffer = ByteArray(1024 * 16)
        var bytesRead: Int
        DebugLogger.log("SOCKET", "Dinleme döngüsü başladı (Listening)...")

        while (isConnected) {
            try {
                bytesRead = inputStream?.read(buffer) ?: -1
                if (bytesRead > 0) processIncomingPacket(buffer.copyOfRange(0, bytesRead))
                else if (bytesRead == -1) break
            } catch (e: Exception) { break }
        }

        DebugLogger.log("SOCKET", "Dinleme sonlandı. Bağlantı kapandı.")
        close()
    }

    // --- GELİŞMİŞ PAKET İŞLEME ---
    private fun processIncomingPacket(data: ByteArray) {
        val context = appContext ?: return
        val db = AppDatabase.getDatabase(context)

        val myProfile = runBlocking { db.userDao().getUserProfile() }
        val myId = myProfile?.userId ?: "unknown"
        val myLat = myProfile?.latitude ?: 0.0
        val myLng = myProfile?.longitude ?: 0.0

        try {
            val packet = KenetPacket.parseFrom(data)

            when (packet.type) {
                // 1. KEŞİF
                KenetPacket.PacketType.DISCOVERY -> {
                    val disc = packet.discovery
                    if (seenPackets.contains(disc.packetUid)) return
                    seenPackets.add(disc.packetUid)

                    DebugLogger.log("PROTO", "🔍 Keşif Paketi Alındı. Kimden: ${disc.senderId.take(5)}..")
                    RoutingManager.updateNeighbor(disc.senderId, disc.senderLat, disc.senderLng)

                    if (disc.targetId.equals(myId, ignoreCase = true)) {
                        DebugLogger.log("PROTO", "👋 Beni Arıyorlar! Cevap (Reply) dönülüyor.")

                        val reply = ReplyPacket.newBuilder()
                            .setPacketUid(UUID.randomUUID().toString())
                            .setSenderId(myId).setTargetId(disc.senderId)
                            .setSenderLat(myLat).setSenderLng(myLng)
                            .setTargetLat(disc.senderLat).setTargetLng(disc.senderLng)
                            .setTtl(64).setTimestamp(System.currentTimeMillis())
                            .build()
                        write(KenetPacket.newBuilder().setType(KenetPacket.PacketType.REPLY).setReply(reply).build().toByteArray())

                        CoroutineScope(Dispatchers.IO).launch {
                            db.contactDao().updateContactLocation(disc.senderId, disc.senderLat, disc.senderLng)
                        }
                    } else {
                        // Flood
                        if (disc.ttl > 0) {
                            // DebugLogger.log("PROTO", "Keşif paketi yayılıyor (Flood)...")
                            val newDisc = disc.toBuilder().setTtl(disc.ttl - 1).build()
                            val newPacket = packet.toBuilder().setDiscovery(newDisc).build()
                            write(newPacket.toByteArray())
                        }
                    }
                }

                // 2. MESAJ (GPSR + ACK + DTN)
                KenetPacket.PacketType.MESSAGE -> {
                    val msg = packet.message
                    if (msg.targetId == "BROADCAST" || msg.contentText == "B") return // Beacon loglamaya gerek yok

                    RoutingManager.updateNeighbor(msg.senderId, msg.senderLat, msg.senderLng)

                    if (msg.targetId.equals(myId, ignoreCase = true)) {
                        // A) MESAJ BANA GELDİ
                        DebugLogger.log("MSG", "📨 MESAJ ALINDI: ${msg.contentText}")

                        CoroutineScope(Dispatchers.IO).launch {
                            db.contactDao().updateContactLocation(msg.senderId, msg.senderLat, msg.senderLng)
                            val newMessage = MessageEntity(
                                packetUid = msg.packetUid, senderId = msg.senderId, receiverId = myId, chatPartnerId = msg.senderId,
                                content = msg.contentText, timestamp = msg.timestamp, isSent = false, status = 2
                            )
                            db.messageDao().insertMessage(newMessage)
                        }

                        // ACK Gönder
                        DebugLogger.log("ACK", "↩️ Teslim Onayı (ACK) gönderiliyor...")
                        val ack = AckPacket.newBuilder()
                            .setPacketUid(msg.packetUid)
                            .setSenderId(myId)
                            .setTargetId(msg.senderId)
                            .setSenderLat(myLat).setSenderLng(myLng)
                            .setTargetLat(msg.senderLat).setTargetLng(msg.senderLng)
                            .setTimestamp(System.currentTimeMillis())
                            .build()

                        val ackPacket = KenetPacket.newBuilder().setType(KenetPacket.PacketType.ACK).setAck(ack).build()
                        routePacket(ackPacket, msg.senderId, msg.senderLat, msg.senderLng, myLat, myLng)

                    } else {
                        // B) FORWARD (İlet)
                        if (msg.ttl > 0) {
                            DebugLogger.log("ROUTING", "🔀 Mesaj İletiliyor (Hop). Hedef: ${msg.targetId.take(5)}..")
                            val newMsg = msg.toBuilder().setTtl(msg.ttl - 1).build()
                            val newPacket = packet.toBuilder().setMessage(newMsg).build()
                            routePacket(newPacket, msg.targetId, msg.targetLat, msg.targetLng, myLat, myLng)
                        }
                    }
                }

                // 3. ACK (ONAY)
                KenetPacket.PacketType.ACK -> {
                    val ack = packet.ack
                    if (ack.targetId.equals(myId, ignoreCase = true)) {
                        DebugLogger.log("ACK", "✅✅ Mesaj Görüldü/İletildi! (UID: ${ack.packetUid.take(5)}..)")
                        CoroutineScope(Dispatchers.IO).launch {
                            db.messageDao().markMessageAsDelivered(ack.packetUid)
                        }
                    } else {
                        DebugLogger.log("ROUTING", "🔀 ACK İletiliyor...")
                        routePacket(packet, ack.targetId, ack.targetLat, ack.targetLng, myLat, myLng)
                    }
                }

                KenetPacket.PacketType.REPLY -> {
                    val reply = packet.reply
                    if (reply.targetId.equals(myId, ignoreCase = true)) {
                        DebugLogger.log("PROTO", "📍 Hedef Konum Bulundu! (${reply.senderId.take(5)}..)")
                        CoroutineScope(Dispatchers.IO).launch {
                            db.contactDao().updateContactLocation(reply.senderId, reply.senderLat, reply.senderLng)
                        }
                    } else {
                        if (reply.ttl > 0) write(data)
                    }
                }

                else -> {}
            }
        } catch (e: Exception) {
            DebugLogger.log("ERROR", "Paket İşleme Hatası: ${e.message}")
        }
    }

    // --- GPSR & DTN YÖNLENDİRME ---
    private fun routePacket(packet: KenetPacket, targetId: String, tLat: Double, tLng: Double, myLat: Double, myLng: Double) {
        val nextHopId = RoutingManager.getNextHop(tLat, tLng, myLat, myLng)

        if (nextHopId != null) {
            // write fonksiyonu şu an broadcast gibi çalışıyor ama ileride nextHopId'ye özel gönderim yapılabilir
            write(packet.toByteArray())
        } else {
            DebugLogger.log("DTN", "🚧 Yol kapalı/Menzil dışı. Paket DTN havuzunda bekleyecek.")
        }
    }

    fun write(data: ByteArray) {
        if (!isConnected || outputStream == null) return
        executor.execute {
            try {
                outputStream?.write(data)
                outputStream?.flush()
            }
            catch (e: Exception) { close() }
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
        } catch (e: Exception) { e.printStackTrace() }
    }
}