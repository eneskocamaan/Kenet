import asyncpg
from dotenv import load_dotenv
import os
from typing import Optional, Dict, List
from models import ContactModel
from utils import generate_short_id

load_dotenv()
DATABASE_URL = os.getenv("DATABASE_URL")
pool = None

async def connect_db():
    global pool
    try:
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
    p = ''.join(filter(str.isdigit, phone))
    if p.startswith("90") and len(p) > 10:
        p = p[2:]
    if p.startswith("0"):
        p = p[1:]
    return p

# --- KULLANICI İŞLEMLERİ (DAO) ---

async def get_user_by_phone(phone_number: str) -> Optional[Dict]:
    clean_num = sanitize_phone(phone_number)
    query = "SELECT * FROM users WHERE phone_number = $1"
    return await pool.fetchrow(query, clean_num)

async def upsert_otp(phone_number: str, otp_code: str):
    clean_num = sanitize_phone(phone_number)
    user = await get_user_by_phone(clean_num)
    if user:
        query = "UPDATE users SET verification_code = $1 WHERE phone_number = $2"
        await pool.execute(query, otp_code, clean_num)
    else:
        new_user_id = generate_short_id(clean_num)
        query = """
            INSERT INTO users (user_id, phone_number, verification_code, display_name, blood_type)
            VALUES ($1, $2, $3, $4, $5)
        """
        await pool.execute(query, new_user_id, clean_num, otp_code, '', '')

async def activate_new_user(phone_number: str, user_id: str, private_key: str, public_params: str):
    clean_num = sanitize_phone(phone_number)
    query = "UPDATE users SET ibe_private_key = $1, public_params = $2 WHERE phone_number = $3"
    await pool.execute(query, private_key, public_params, clean_num)

async def update_user_profile(phone_number: str, display_name: str, blood_type: str):
    clean_num = sanitize_phone(phone_number)
    query = "UPDATE users SET display_name = $1, blood_type = $2 WHERE phone_number = $3"
    await pool.execute(query, display_name, blood_type, clean_num)

# [YENİ] Konum Güncelleme Fonksiyonu
async def update_user_location(user_id: str, lat: float, lng: float):
    """Kullanıcının latitude ve longitude değerlerini günceller."""
    query = """
        UPDATE users
        SET latitude = $1, longitude = $2
        WHERE user_id = $3
    """
    await pool.execute(query, lat, lng, user_id)

# --- REHBER İŞLEMLERİ ---

async def add_contacts_to_db(owner_phone: str, contacts: List[ContactModel]):
    clean_owner_phone = sanitize_phone(owner_phone)
    owner = await get_user_by_phone(clean_owner_phone)
    if not owner: return

    owner_id = owner['user_id']
    for contact in contacts:
        clean_contact_phone = sanitize_phone(contact.phone_number)
        registered_contact = await get_user_by_phone(clean_contact_phone)
        contact_id = registered_contact['user_id'] if registered_contact else None

        query = """
            INSERT INTO contacts (owner_id, contact_id, phone_number, display_name)
            VALUES ($1, $2, $3, $4)
            ON CONFLICT (owner_id, phone_number)
            DO UPDATE SET display_name = $4, contact_id = $2
        """
        try:
            await pool.execute(query, owner_id, contact_id, clean_contact_phone, contact.display_name)
        except Exception as e:
            print(f"Hata: {e}")

async def get_registered_users(phone_numbers: List[str]) -> List[str]:
    if not phone_numbers: return []
    clean_list = [sanitize_phone(p) for p in phone_numbers]
    query = "SELECT phone_number FROM users WHERE phone_number = ANY($1::text[])"
    rows = await pool.fetch(query, clean_list)
    return [row['phone_number'] for row in rows]

# --- SENKRONİZASYON (GÜNCELLENDİ) ---

async def get_user_contacts(owner_id: str) -> List[Dict]:
    """
    1. ADIM: Eksik ID'leri tamamla.
    2. ADIM: Rehberi çekerken USERS tablosuna JOIN atarak ANLIK KONUMU da çek.
    """
    # Önce ID'leri güncelle (Eskisi gibi)
    update_query = """
        UPDATE contacts c
        SET contact_id = u.user_id
        FROM users u
        WHERE c.owner_id = $1
          AND c.phone_number = u.phone_number
          AND c.contact_id IS NULL
    """
    await pool.execute(update_query, owner_id)

    # [GÜNCELLENDİ] JOIN ile Users tablosundan latitude ve longitude çekiliyor
    select_query = """
        SELECT
            c.contact_id,
            c.phone_number,
            c.display_name,
            u.latitude,
            u.longitude
        FROM contacts c
        LEFT JOIN users u ON c.contact_id = u.user_id
        WHERE c.owner_id = $1
    """
    return await pool.fetch(select_query, owner_id)

async def delete_contact_from_db(owner_phone: str, contact_phone: str):
    owner = await get_user_by_phone(sanitize_phone(owner_phone))
    if not owner: return False
    owner_id = owner['user_id']
    clean_contact_phone = sanitize_phone(contact_phone)
    query = "DELETE FROM contacts WHERE owner_id = $1 AND phone_number = $2"
    await pool.execute(query, owner_id, clean_contact_phone)