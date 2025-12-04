import uuid
import hashlib
import random

def generate_short_id(phone_number: str) -> str:
    """
    Telefon numarasından minimum boyutta (8 karakter) ve benzersiz (milyonlarca kullanıcı için)
    sabit bir ID üretir. SHA256 kullanıp Base62 ile kısaltmayı simüle eder.
    """
    # Telefon numarasını temizle ve SHA256 ile hash'le
    clean_number = phone_number.replace('+', '').replace(' ', '')
    hashed = hashlib.sha256(clean_number.encode('utf-8')).hexdigest()

    # Hash'in ilk 8 karakterini al (Yüksek benzersizlik ve minimum boyut)
    return hashed[:8].upper()

def generate_mock_key(user_id: str) -> str:
    """IBE Private Key'i simüle eder."""
    return f"IBE_PK_{user_id}_{random.randint(1000, 9999)}"

def generate_public_params() -> str:
    """Sistemin IBE Public Parameters'ını simüle eder."""
    return "MPK_KENET_SYS_PARAMS_V2"

def generate_otp() -> str:
    """Mock SMS kodu üretir."""
    return str(random.randint(1000, 9999))