import asyncpg
from dotenv import load_dotenv
import os
from typing import Optional, Dict, List
from models import ContactModel
from utils import generate_short_id

load_dotenv()
DATABASE_URL = os.getenv("DATABASE_URL")
pool = None

# --- BAĞLANTI YÖNETİMİ ---
async def connect_db():
    global pool
    try:
        # Bağlantı havuzu oluşturuluyor
        pool = await asyncpg.create_pool(DATABASE_URL)
        print("INFO: PostgreSQL bağlantısı başarıyla kuruldu.")
    except Exception as e:
        print(f"HATA: Veritabanı bağlantısı kurulamadı: {e}")
        raise

async def close_db():
    if pool:
        await pool.close()
        print("INFO: PostgreSQL bağlantısı kapatıldı.")

def sanitize_phone(phone: str) -> str:
    # Sadece rakamları al
    p = ''.join(filter(str.isdigit, phone))
    # 90 ile başlıyorsa ve 10 haneden uzunsa başındaki 90'ı sil
    if p.startswith("90") and len(p) > 10: p = p[2:]
    # Başında 0 varsa sil (5XX formatına getir)
    if p.startswith("0"): p = p[1:]
    return p

# --- KULLANICI İŞLEMLERİ ---
async def get_user_by_phone(phone_number: str) -> Optional[Dict]:
    clean_num = sanitize_phone(phone_number)
    query = "SELECT * FROM users WHERE phone_number = $1"
    return await pool.fetchrow(query, clean_num)

async def get_user_by_id(user_id: str) -> Optional[Dict]:
    query = "SELECT * FROM users WHERE user_id = $1"
    return await pool.fetchrow(query, user_id)

async def upsert_otp(phone_number: str, otp_code: str):
    clean_num = sanitize_phone(phone_number)
    user = await get_user_by_phone(clean_num)
    if user:
        query = "UPDATE users SET verification_code = $1 WHERE phone_number = $2"
        await pool.execute(query, otp_code, clean_num)
    else:
        new_user_id = generate_short_id(clean_num)
        query = "INSERT INTO users (user_id, phone_number, verification_code, display_name) VALUES ($1, $2, $3, '')"
        await pool.execute(query, new_user_id, clean_num, otp_code)

async def activate_new_user(phone_number: str, user_id: str, private_key: str, public_params: str):
    clean_num = sanitize_phone(phone_number)
    query = "UPDATE users SET ibe_private_key = $1, public_params = $2, user_id = $3 WHERE phone_number = $4"
    await pool.execute(query, private_key, public_params, user_id, clean_num)

async def update_user_profile(phone_number: str, display_name: str, blood_type: str):
    clean_num = sanitize_phone(phone_number)
    query = "UPDATE users SET display_name = $1, blood_type = $2 WHERE phone_number = $3"
    await pool.execute(query, display_name, blood_type, clean_num)

async def update_user_location(user_id: str, lat: float, lng: float):
    query = "UPDATE users SET latitude = $1, longitude = $2 WHERE user_id = $3"
    await pool.execute(query, lat, lng, user_id)

# --- REHBER İŞLEMLERİ ---
async def add_contacts_to_db(owner_phone: str, contacts: List[ContactModel]):
    clean_owner_phone = sanitize_phone(owner_phone)
    owner = await get_user_by_phone(clean_owner_phone)
    if not owner: return

    owner_id = owner['user_id']
    for contact in contacts:
        clean_contact_phone = sanitize_phone(contact.phone_number)

        # Kişinin sistemde kayıtlı olup olmadığını kontrol et
        registered = await get_user_by_phone(clean_contact_phone)
        contact_id = registered['user_id'] if registered else None
        is_registered = registered is not None

        # Contacts tablosuna ekle
        # Eğer varsa güncelle (display_name veya contact_id değişmiş olabilir)
        query = """
            INSERT INTO contacts (owner_id, contact_id, phone_number, display_name, is_registered_user)
            VALUES ($1, $2, $3, $4, $5)
            ON CONFLICT (owner_id, phone_number)
            DO UPDATE SET display_name = $4, contact_id = $2, is_registered_user = $5
        """
        try:
            await pool.execute(query, owner_id, contact_id, clean_contact_phone, contact.display_name, is_registered)
        except Exception as e:
            print(f"Contact Insert Error: {e}")

async def get_registered_users_details(phone_numbers: List[str]) -> List[Dict]:
    if not phone_numbers: return []

    # Numaraları temizle
    clean_list = [sanitize_phone(p) for p in phone_numbers]

    query = """
        SELECT user_id, phone_number, display_name, blood_type,
               public_params, latitude, longitude
        FROM users
        WHERE phone_number = ANY($1::text[])
    """
    return await pool.fetch(query, clean_list)

async def get_user_contacts(owner_id: str) -> List[Dict]:
    # Önce contact_id'leri güncelle (Kayıt olmamış ama sonradan olmuş kişiler için)
    await pool.execute("""
        UPDATE contacts c
        SET contact_id = u.user_id, is_registered_user = TRUE
        FROM users u
        WHERE c.owner_id = $1 AND c.phone_number = u.phone_number AND c.contact_id IS NULL
    """, owner_id)

    # Rehberi çek
    query = """
        SELECT c.contact_id, c.phone_number, c.display_name,
               u.latitude, u.longitude, u.public_params
        FROM contacts c
        LEFT JOIN users u ON c.contact_id = u.user_id
        WHERE c.owner_id = $1
    """
    return await pool.fetch(query, owner_id)

async def delete_contact_from_db(owner_phone: str, contact_phone: str):
    owner = await get_user_by_phone(sanitize_phone(owner_phone))
    if not owner: return
    owner_id = owner['user_id']
    await pool.execute("DELETE FROM contacts WHERE owner_id = $1 AND phone_number = $2", owner_id, sanitize_phone(contact_phone))

# --- SMS GATEWAY & LOGLAMA (DÜZELTİLDİ) ---

async def is_sms_processed(packet_uid: str) -> bool:
    # Aynı paket ID'si ile daha önce işlem yapılmış mı?
    query = "SELECT 1 FROM sms_logs WHERE packet_uid = $1"
    row = await pool.fetchrow(query, packet_uid)
    return row is not None

# [DÜZELTME BURADA] 'content' parametresi eklendi.
async def log_sms_attempt(packet_uid: str, sender: str, target: str, content: str, status: str, response: str):
    # Eğer veritabanında 'message_content' sütunu yoksa, hata vermemesi için
    # try-catch bloğu kullanılabilir veya SQL şeması güncellenmelidir.
    # Varsayılan olarak sütunun var olduğunu varsayıyoruz.

    query = """
        INSERT INTO sms_logs (packet_uid, sender_phone, target_phone, message_content, status, provider_response, created_at)
        VALUES ($1, $2, $3, $4, $5, $6, NOW())
    """
    try:
        await pool.execute(query, packet_uid, sender, target, content, status, response)
    except Exception as e:
        print(f"LOGGING ERROR: {e}")
        # Eğer tablo yapısı eski kaldıysa ve message_content yoksa fallback:
        # (Bu sadece debug amaçlıdır, SQL tablonuzu güncellemeniz önerilir)