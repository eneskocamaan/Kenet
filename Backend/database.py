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
    """Veritabanı bağlantı havuzunu başlatır."""
    global pool
    try:
        pool = await asyncpg.create_pool(DATABASE_URL)
        print("INFO: PostgreSQL bağlantısı başarıyla kuruldu.")
    except Exception as e:
        print(f"HATA: Veritabanı bağlantısı kurulamadı: {e}")
        raise

async def close_db():
    """Uygulama kapanırken bağlantı havuzunu kapatır."""
    if pool:
        await pool.close()
        print("INFO: PostgreSQL bağlantısı kapatıldı.")

# --- YARDIMCI: Telefon Temizleme ---
def sanitize_phone(phone: str) -> str:
    """
    Telefon numarasını sadece 10 haneli saf hale getirir.
    Veritabanında standartlaşma sağlar.
    +90532... -> 532...
    0532...   -> 532...
    """
    # Sadece rakamları al
    p = ''.join(filter(str.isdigit, phone))

    # Türkiye ülke kodu (90) varsa ve numara uzunsa sil
    if p.startswith("90") and len(p) > 10:
        p = p[2:]

    # Başındaki '0'ı sil
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
    query = """
        UPDATE users SET ibe_private_key = $1, public_params = $2 WHERE phone_number = $3
    """
    await pool.execute(query, private_key, public_params, clean_num)

async def update_user_profile(phone_number: str, display_name: str, blood_type: str):
    clean_num = sanitize_phone(phone_number)
    query = """
        UPDATE users SET display_name = $1, blood_type = $2 WHERE phone_number = $3
    """
    await pool.execute(query, display_name, blood_type, clean_num)

# --- REHBER İŞLEMLERİ ---

async def add_contacts_to_db(owner_phone: str, contacts: List[ContactModel]):
    """
    Seçilen kişileri 'contacts' tablosuna ekler.
    Numaraları temizler ve o an kayıtlıysa ID'sini ekler.
    """
    clean_owner_phone = sanitize_phone(owner_phone)
    owner = await get_user_by_phone(clean_owner_phone)

    if not owner:
        print(f"HATA: Kullanıcı bulunamadı: {owner_phone}")
        return

    owner_id = owner['user_id']

    for contact in contacts:
        clean_contact_phone = sanitize_phone(contact.phone_number)

        # Bu kişi sistemde kayıtlı mı?
        registered_contact = await get_user_by_phone(clean_contact_phone)

        contact_id = None
        if registered_contact:
            contact_id = registered_contact['user_id']

        # Veritabanına Ekle (Upsert)
        query = """
            INSERT INTO contacts (owner_id, contact_id, phone_number, display_name)
            VALUES ($1, $2, $3, $4)
            ON CONFLICT (owner_id, phone_number)
            DO UPDATE SET display_name = $4, contact_id = $2
        """
        try:
            await pool.execute(query, owner_id, contact_id, clean_contact_phone, contact.display_name)
        except Exception as e:
            print(f"Kişi eklenirken hata ({contact.display_name}): {e}")

async def get_registered_users(phone_numbers: List[str]) -> List[str]:
    """
    Verilen numara listesinden hangilerinin kayıtlı olduğunu bulur.
    'Kenet Kullanıyor' kontrolü için.
    """
    if not phone_numbers:
        return []
    clean_list = [sanitize_phone(p) for p in phone_numbers]
    query = "SELECT phone_number FROM users WHERE phone_number = ANY($1::text[])"
    rows = await pool.fetch(query, clean_list)
    return [row['phone_number'] for row in rows]

# --- SENKRONİZASYON VE EKSİK ID TAMAMLAMA (KRİTİK KISIM) ---

async def get_user_contacts(owner_id: str) -> List[Dict]:
    """
    1. ADIM (GÜNCELLEME): Önce 'contacts' tablosundaki eksik ID'leri tamamla.
       Eğer rehberdeki bir numara 'users' tablosunda artık varsa,
       contacts tablosundaki NULL olan contact_id'yi o kullanıcının ID'si ile güncelle.
    """
    update_query = """
        UPDATE contacts c
        SET contact_id = u.user_id
        FROM users u
        WHERE c.owner_id = $1
          AND c.phone_number = u.phone_number
          AND c.contact_id IS NULL
    """
    await pool.execute(update_query, owner_id)

    """
    2. ADIM (ÇEKME): Artık tablo güncellendiği için en güncel verileri çekip kullanıcıya dön.
    """
    select_query = """
        SELECT contact_id, phone_number, display_name
        FROM contacts
        WHERE owner_id = $1
    """
    return await pool.fetch(select_query, owner_id)

async def delete_contact_from_db(owner_phone: str, contact_phone: str):
    """
    Bir kişiyi rehberden siler.
    """
    # 1. Sahibin ID'sini bul
    owner = await get_user_by_phone(sanitize_phone(owner_phone))
    if not owner:
        return False

    owner_id = owner['user_id']
    clean_contact_phone = sanitize_phone(contact_phone)

    # 2. Silme İşlemi
    query = "DELETE FROM contacts WHERE owner_id = $1 AND phone_number = $2"
    result = await pool.execute(query, owner_id, clean_contact_phone)
    return result # "DELETE 1" gibi bir string döner