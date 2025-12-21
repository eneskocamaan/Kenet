package com.eneskocamaan.kenet.security

import android.content.Context
import android.util.Base64
import com.eneskocamaan.kenet.DebugLogger
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.utils.Key
import com.goterl.lazysodium.utils.KeyPair
import java.nio.charset.StandardCharsets

object CryptoManager {

    private lateinit var lazySodium: LazySodiumAndroid

    fun init(context: Context) {
        lazySodium = LazySodiumAndroid(SodiumAndroid())
    }

    data class EncryptedBox(
        val encryptedPayload: ByteArray,
        val nonce: ByteArray,
        val ephemeralPublicKey: ByteArray,
        val integrityTag: ByteArray
    )

    // --- 1. MESAJ ŞİFRELEME ---
    fun encrypt(message: String, receiverPubStr: String): EncryptedBox? {
        try {
            DebugLogger.log("CRYPTO_ENC", "🔒 --- ŞİFRELEME BAŞLADI ---")
            DebugLogger.log("CRYPTO_ENC", "📝 Ham Metin: '$message'")
            DebugLogger.log("CRYPTO_ENC", "🔑 Alıcı Public Key: ${receiverPubStr.take(15)}...")

            val box = lazySodium as Box.Lazy

            // 1. Anahtarları Hazırla
            val receiverKeyBytes = Base64.decode(receiverPubStr, Base64.NO_WRAP)
            val receiverKey = Key.fromBytes(receiverKeyBytes)

            val ephemeralKeyPair = box.cryptoBoxKeypair()
            val senderSecretKey = ephemeralKeyPair.secretKey

            // Logla: Geçici (Ephemeral) Anahtarlar
            val ephPubStr = Base64.encodeToString(ephemeralKeyPair.publicKey.asBytes, Base64.NO_WRAP)
            DebugLogger.log("CRYPTO_ENC", "🔑 Ephemeral (Geçici) Public Key: ${ephPubStr.take(15)}...")

            // 2. Nonce Üret
            val nonceBytes = lazySodium.nonce(Box.NONCEBYTES)
            val nonceStr = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)
            DebugLogger.log("CRYPTO_ENC", "🎲 Nonce Üretildi: $nonceStr")

            // 3. ŞİFRELEME
            val encryptionKeyPair = KeyPair(receiverKey, senderSecretKey)

            val cipherTextHex = box.cryptoBoxEasy(
                message,
                nonceBytes,
                encryptionKeyPair
            )

            // 4. Hex -> Bytes ve MAC Ayırma
            val combinedBytes = lazySodium.toBinary(cipherTextHex)
            val macSize = Box.MACBYTES

            if (combinedBytes.size <= macSize) {
                DebugLogger.log("CRYPTO_ERR", "❌ Şifreli veri boyutu çok küçük!")
                return null
            }

            val macBytes = combinedBytes.copyOfRange(0, macSize)
            val cipherBytes = combinedBytes.copyOfRange(macSize, combinedBytes.size)

            // DETAYLI LOGLAMA (Payload)
            val cipherBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            val macBase64 = Base64.encodeToString(macBytes, Base64.NO_WRAP)

            DebugLogger.log("CRYPTO_ENC", "📦 Şifreli Payload (Base64): $cipherBase64")
            DebugLogger.log("CRYPTO_ENC", "🛡️ Integrity Tag (MAC): $macBase64")
            DebugLogger.log("CRYPTO_ENC", "✅ Şifreleme Tamamlandı.")

            return EncryptedBox(
                encryptedPayload = cipherBytes,
                nonce = nonceBytes,
                ephemeralPublicKey = ephemeralKeyPair.publicKey.asBytes,
                integrityTag = macBytes
            )

        } catch (e: Exception) {
            DebugLogger.log("CRYPTO_ERR", "Encrypt Hatası: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    // --- 2. MESAJ ÇÖZME ---
    fun decrypt(
        encryptedPayload: ByteArray,
        integrityTag: ByteArray,
        nonce: ByteArray,
        senderEphemeralPub: ByteArray,
        myPrivateStr: String
    ): String? {
        try {
            DebugLogger.log("CRYPTO_DEC", "🔓 --- ŞİFRE ÇÖZME BAŞLADI ---")

            val ephPubStr = Base64.encodeToString(senderEphemeralPub, Base64.NO_WRAP)
            val payloadStr = Base64.encodeToString(encryptedPayload, Base64.NO_WRAP)

            DebugLogger.log("CRYPTO_DEC", "📦 Gelen Payload: ${payloadStr.take(20)}... (${encryptedPayload.size} bytes)")
            DebugLogger.log("CRYPTO_DEC", "🔑 Sender Ephemeral Key: ${ephPubStr.take(15)}...")
            DebugLogger.log("CRYPTO_DEC", "🔑 Benim Private Key: ${myPrivateStr.take(10)}... (Maskelendi)")

            val box = lazySodium as Box.Lazy

            // 1. Anahtarları Hazırla
            val myPrivBytes = Base64.decode(myPrivateStr, Base64.NO_WRAP)
            val myPrivKey = Key.fromBytes(myPrivBytes)

            val senderPubKey = Key.fromBytes(senderEphemeralPub)

            // 2. MAC + Ciphertext Birleştir
            val combinedBytes = integrityTag + encryptedPayload
            val combinedHex = lazySodium.toHexStr(combinedBytes)

            // 3. ŞİFRE ÇÖZME
            val decryptionKeyPair = KeyPair(senderPubKey, myPrivKey)

            val decrypted = box.cryptoBoxOpenEasy(
                combinedHex,
                nonce,
                decryptionKeyPair
            )

            if (decrypted != null) {
                DebugLogger.log("CRYPTO_DEC", "✅ BAŞARILI! Çözülen Mesaj: '$decrypted'")
            } else {
                DebugLogger.log("CRYPTO_DEC", "❌ Şifre çözülemedi (Sonuç null). Anahtarlar uyuşmuyor olabilir.")
            }

            return decrypted

        } catch (e: Exception) {
            DebugLogger.log("CRYPTO_ERR", "Decrypt Hatası: ${e.message}")
            return null
        }
    }

    // --- 3. DİĞERLERİ ---
    fun signData(data: ByteArray, myPrivateStr: String): ByteArray? {
        try {
            val myPrivBytes = Base64.decode(myPrivateStr, Base64.NO_WRAP)
            val key = Key.fromBytes(myPrivBytes)
            val dataHex = lazySodium.toHexStr(data)
            val hashHex = lazySodium.cryptoGenericHash(dataHex, key)
            return lazySodium.toBinary(hashHex)
        } catch (e: Exception) { return null }
    }

    fun generateKeys(): Pair<String, String> {
        val box = lazySodium as Box.Lazy
        val kp = box.cryptoBoxKeypair()
        val pub = Base64.encodeToString(kp.publicKey.asBytes, Base64.NO_WRAP)
        val priv = Base64.encodeToString(kp.secretKey.asBytes, Base64.NO_WRAP)
        return Pair(pub, priv)
    }

    fun verifySignature(data: ByteArray, signature: ByteArray, senderPubStr: String): Boolean {
        return true
    }
}