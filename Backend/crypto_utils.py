import os
import base64
from nacl.public import PrivateKey, PublicKey, Box
from nacl.encoding import Base64Encoder
from dotenv import load_dotenv

load_dotenv()

# .env dosyasından Private Key'i güvenli şekilde al
SERVER_PRIVATE_KEY_B64 = os.getenv("SERVER_PRIVATE_KEY")

def decrypt_gateway_message(
    encrypted_payload_b64: str,
    nonce_b64: str,
    ephemeral_key_b64: str,
    integrity_tag_b64: str
) -> str:
    """
    Android'den gelen X25519 + ChaCha20-Poly1305 şifreli paketi çözer.
    """
    try:
        if not SERVER_PRIVATE_KEY_B64:
            print("❌ HATA: SERVER_PRIVATE_KEY .env dosyasında bulunamadı!")
            return "[SERVER CONFIG ERROR]"

        # 1. Base64 Decode (String -> Bytes)
        try:
            cipher_bytes = base64.b64decode(encrypted_payload_b64)
            nonce_bytes = base64.b64decode(nonce_b64)
            sender_pub_bytes = base64.b64decode(ephemeral_key_b64)
            tag_bytes = base64.b64decode(integrity_tag_b64)
        except Exception:
            print("❌ Base64 Decode Hatası")
            return "[FORMAT HATASI]"

        # 2. Anahtarları Oluştur
        server_priv = PrivateKey(SERVER_PRIVATE_KEY_B64, encoder=Base64Encoder)
        sender_pub = PublicKey(sender_pub_bytes) # Raw bytes

        # 3. Kripto Kutusu (Box) Oluştur
        # Bu işlem, Curve25519 üzerinde Diffie-Hellman anahtar değişimini simüle eder.
        box = Box(server_priv, sender_pub)

        # 4. Veriyi Birleştir (Libsodium Standardı)
        # Android'de Lazysodium MAC ve Ciphertext'i ayırmıştı.
        # Python decrypt fonksiyonu bunları birleşik ister: [MAC (16 Byte) + Ciphertext]
        combined_ciphertext = tag_bytes + cipher_bytes

        # 5. Şifreyi Çöz
        # decrypt metodu, MAC (Integrity Tag) kontrolünü OTOMATİK yapar.
        # Eğer veri yolda 1 bit bile değiştiyse, CryptoError fırlatır ve şifreyi açmaz.
        plaintext_bytes = box.decrypt(combined_ciphertext, nonce=nonce_bytes)

        return plaintext_bytes.decode('utf-8')

    except Exception as e:
        print(f"⚠️ Şifre Çözme Başarısız: {e}")
        return "[ŞİFRE ÇÖZÜLEMEDİ]"