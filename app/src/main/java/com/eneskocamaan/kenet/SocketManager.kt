package com.eneskocamaan.kenet

import android.content.Context
import android.util.Log
import com.eneskocamaan.kenet.data.db.AppDatabase
import com.eneskocamaan.kenet.data.db.MessageEntity
import com.eneskocamaan.kenet.proto.KenetPacket
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

    // --- 1. SERVER ---
    fun startServer() {
        if (isConnected) return
        if (isConnecting.getAndSet(true)) return

        executor.execute {
            try {
                val serverSocket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(8888)) }
                socket = serverSocket.accept()
                setupStreams()
            } catch (e: Exception) {
                Log.e("KENET_SOCKET", "Server Hatası: ${e.message}")
                close()
            } finally {
                isConnecting.set(false)
            }
        }
    }

    // --- 2. CLIENT ---
    fun startClient(hostAddress: String) {
        if (isConnected) return
        if (isConnecting.getAndSet(true)) return

        executor.execute {
            var attempt = 0
            val maxRetries = 10
            try {
                while (attempt < maxRetries && !isConnected) {
                    try {
                        attempt++
                        if (socket != null && !socket!!.isClosed) socket!!.close()
                        socket = Socket()
                        socket?.connect(InetSocketAddress(hostAddress, 8888), 3000)
                        setupStreams()
                        return@execute
                    } catch (e: Exception) {
                        Thread.sleep(1500)
                    }
                }
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
            close()
        }
    }

    private fun startListening() {
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (isConnected) {
            try {
                bytesRead = inputStream?.read(buffer) ?: -1
                if (bytesRead > 0) {
                    processIncomingPacket(buffer.copyOfRange(0, bytesRead))
                } else if (bytesRead == -1) break
            } catch (e: Exception) { break }
        }
        close()
    }

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
                KenetPacket.PacketType.DISCOVERY -> {
                    val disc = packet.discovery
                    if (seenPackets.contains(disc.packetUid)) return
                    seenPackets.add(disc.packetUid)

                    if (disc.targetId.equals(myId, ignoreCase = true)) {
                        val reply = ReplyPacket.newBuilder()
                            .setPacketUid(UUID.randomUUID().toString())
                            .setSenderId(myId).setTargetId(disc.senderId)
                            .setSenderLat(myLat).setSenderLng(myLng)
                            .setTargetLat(disc.senderLat).setTargetLng(disc.senderLng)
                            .setTtl(10).setTimestamp(System.currentTimeMillis())
                            .build()
                        val mainReply = KenetPacket.newBuilder().setType(KenetPacket.PacketType.REPLY).setReply(reply).build()
                        write(mainReply.toByteArray())

                        CoroutineScope(Dispatchers.IO).launch {
                            db.contactDao().updateContactLocation(disc.senderId, disc.senderLat, disc.senderLng)
                        }
                    } else {
                        if (disc.ttl > 0) {
                            val newDisc = disc.toBuilder().setTtl(disc.ttl - 1).build()
                            val newMain = packet.toBuilder().setDiscovery(newDisc).build()
                            write(newMain.toByteArray())
                        }
                    }
                }

                KenetPacket.PacketType.REPLY -> {
                    val reply = packet.reply
                    if (reply.targetId.equals(myId, ignoreCase = true)) {
                        CoroutineScope(Dispatchers.IO).launch {
                            db.contactDao().updateContactLocation(reply.senderId, reply.senderLat, reply.senderLng)
                        }
                    } else {
                        if (reply.ttl > 0) write(data)
                    }
                }

                KenetPacket.PacketType.MESSAGE -> {
                    val msg = packet.message
                    if (msg.targetId == "BROADCAST" || msg.contentText == "B") return

                    if (msg.targetId.equals(myId, ignoreCase = true)) {
                        CoroutineScope(Dispatchers.IO).launch {
                            db.contactDao().updateContactLocation(msg.senderId, msg.senderLat, msg.senderLng)
                            val newMessage = MessageEntity(
                                senderId = msg.senderId, receiverId = myId, chatPartnerId = msg.senderId,
                                content = msg.contentText, timestamp = msg.timestamp, isSent = false, isRead = false
                            )
                            db.messageDao().insertMessage(newMessage)
                        }
                    } else {
                        if (msg.ttl > 0) write(data)
                    }
                }
                else -> {}
            }
        } catch (e: Exception) { Log.e("KENET_PROTO", "Paket Hatası: ${e.message}") }
    }

    fun write(data: ByteArray) {
        if (!isConnected || outputStream == null) return
        executor.execute {
            try { outputStream?.write(data); outputStream?.flush() }
            catch (e: Exception) { close() }
        }
    }

    fun close() {
        try {
            socket?.close()
            socket = null
            isConnecting.set(false)
        } catch (e: Exception) { e.printStackTrace() }
    }
}