import hashlib
import random
import base64
from nacl.public import PrivateKey
# import aiohttp  # PRODUCTION'DA BURAYI AÇ

def generate_short_id(phone: str) -> str:
    """Telefon numarasından unique ID üretir."""
    clean = phone.replace('+', '').replace(' ', '')
    return hashlib.sha256(clean.encode()).hexdigest()[:8].upper()

def generate_user_keys():
    """
    Kullanıcılar için GERÇEK X25519 (IBE mantığı için) anahtar çifti üretir.
    Dönen değerler Base64 formatındadır.
    """
    private_key = PrivateKey.generate()
    public_key = private_key.public_key

    priv_b64 = base64.b64encode(private_key.encode()).decode('utf-8')
    pub_b64 = base64.b64encode(public_key.encode()).decode('utf-8')

    # Bizim mimarimizde public_key, 'public_params' alanında veya rehberde tutulur.
    return priv_b64, pub_b64

def generate_otp() -> str:
    return str(random.randint(1000, 9999))

# Eski mock fonksiyonları uyumluluk için (artık generate_user_keys kullanılıyor)
def generate_mock_key(uid: str):
    priv, _ = generate_user_keys()
    return priv
def generate_public_params():
    _, pub = generate_user_keys()
    return pub

async def send_real_sms_via_provider(target_phone: str, message: str) -> str:
    """
    SMS Sağlayıcı Entegrasyonu (Netgsm / Twilio).
    """
    print(f"📡 [SMS API OUT] Hedef: {target_phone} | Mesaj: '{message}'")

    # --- MOCK MODU (Test İçin) ---
    return "SUCCESS: Mock Provider Accepted"

    # --- NETGSM PRODUCTION MODU (Kullanmak İçin Yorumları Kaldır) ---
    """
    api_url = "https://api.netgsm.com.tr/sms/send/get"
    # Telefon formatı temizliği (5xxxxxxxxx)
    clean_phone = target_phone.replace("+90", "").replace("+", "").replace(" ", "")
    if clean_phone.startswith("0"): clean_phone = clean_phone[1:]

    payload = {
        "usercode": "NETGSM_KULLANICI_ADINIZ",
        "password": "NETGSM_SIFRENIZ",
        "gsmno": clean_phone,
        "message": message,
        "msgheader": "KENET",
        "filter": "0",
        "startdate": ""
    }

    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(api_url, params=payload) as response:
                result = await response.text()
                # 00 ile başlıyorsa başarılıdır
                if result.startswith("00"):
                    return f"SUCCESS: {result}"
                else:
                    return f"FAILED: {result}"
    except Exception as e:
        return f"FAILED: Connection Error {str(e)}"
    """