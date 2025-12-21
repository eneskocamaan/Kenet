package com.eneskocamaan.kenet

import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.util.UUID

object PacketUtils {
    // UUID String -> Protobuf ByteString (16 Bayt)
    fun uuidToBytes(uuidString: String): ByteString {
        val uuid = UUID.fromString(uuidString)
        val bb = ByteBuffer.wrap(ByteArray(16))
        bb.putLong(uuid.mostSignificantBits)
        bb.putLong(uuid.leastSignificantBits)
        return ByteString.copyFrom(bb.array())
    }

    // Protobuf ByteString -> UUID String
    fun bytesToUuid(bytes: ByteString): String {
        if (bytes.isEmpty) return ""
        val bb = ByteBuffer.wrap(bytes.toByteArray())
        val high = bb.long
        val low = bb.long
        return UUID(high, low).toString()
    }

    // YENİ EKLENENLER (Hata Çözümü İçin)
    fun byteArrayToByteString(bytes: ByteArray): com.google.protobuf.ByteString {
        return com.google.protobuf.ByteString.copyFrom(bytes)
    }

    fun byteStringToByteArray(byteString: com.google.protobuf.ByteString): ByteArray {
        return byteString.toByteArray()
    }
}