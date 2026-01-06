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
        print("INFO: PostgreSQL Bağlantısı Başarılı.")
    except Exception as e:
        print(f"HATA: DB Bağlantı Hatası: {e}")

async def close_db():
    if pool: await pool.close()

def sanitize_phone(phone: str) -> str:
    p = ''.join(filter(str.isdigit, phone))
    if p.startswith("90") and len(p) > 10: p = p[2:]
    if p.startswith("0"): p = p[1:]
    return p

# --- KULLANICI İŞLEMLERİ ---
async def get_user_by_phone(phone: str) -> Optional[Dict]:
    return await pool.fetchrow("SELECT * FROM users WHERE phone_number = $1", sanitize_phone(phone))

async def get_user_by_id(uid: str) -> Optional[Dict]:
    return await pool.fetchrow("SELECT * FROM users WHERE user_id = $1", uid)

async def upsert_otp(phone: str, otp: str):
    clean = sanitize_phone(phone)
    user = await get_user_by_phone(clean)
    if user:
        await pool.execute("UPDATE users SET verification_code = $1 WHERE phone_number = $2", otp, clean)
    else:
        new_id = generate_short_id(clean)
        await pool.execute("INSERT INTO users (user_id, phone_number, verification_code, display_name) VALUES ($1, $2, $3, '')", new_id, clean, otp)

async def activate_new_user(phone: str, uid: str, priv: str, pub: str):
    await pool.execute("UPDATE users SET ibe_private_key = $1, public_params = $2, user_id = $3 WHERE phone_number = $4", priv, pub, uid, sanitize_phone(phone))

async def update_user_profile(phone: str, name: str, blood: str):
    await pool.execute("UPDATE users SET display_name = $1, blood_type = $2 WHERE phone_number = $3", name, blood, sanitize_phone(phone))

async def update_user_location(user_id: str, lat: float, lng: float):
    # last_seen'i güncellemek hayati önem taşır (Aktif kullanıcı sayımı için)
    await pool.execute("UPDATE users SET latitude = $1, longitude = $2, last_seen = NOW() WHERE user_id = $3", lat, lng, user_id)

# --- REHBER & SMS ---
async def add_contacts_to_db(owner_phone: str, contacts: List[ContactModel]):
    clean_owner = sanitize_phone(owner_phone)
    owner = await get_user_by_phone(clean_owner)
    if not owner: return
    owner_id = owner['user_id']
    for c in contacts:
        clean_c = sanitize_phone(c.phone_number)
        reg = await get_user_by_phone(clean_c)
        c_id = reg['user_id'] if reg else None
        is_reg = reg is not None
        await pool.execute("""
            INSERT INTO contacts (owner_id, contact_id, phone_number, display_name, is_registered_user)
            VALUES ($1, $2, $3, $4, $5)
            ON CONFLICT (owner_id, phone_number) DO UPDATE SET display_name = $4, contact_id = $2, is_registered_user = $5
        """, owner_id, c_id, clean_c, c.display_name, is_reg)

async def get_registered_users_details(phones: List[str]) -> List[Dict]:
    clean = [sanitize_phone(p) for p in phones]
    return await pool.fetch("SELECT * FROM users WHERE phone_number = ANY($1::text[])", clean)

async def get_user_contacts(uid: str) -> List[Dict]:
    return await pool.fetch("SELECT c.*, u.latitude, u.longitude, u.public_params FROM contacts c LEFT JOIN users u ON c.contact_id = u.user_id WHERE c.owner_id = $1", uid)

async def delete_contact_from_db(o_phone: str, c_phone: str):
    owner = await get_user_by_phone(o_phone)
    if owner: await pool.execute("DELETE FROM contacts WHERE owner_id = $1 AND phone_number = $2", owner['user_id'], sanitize_phone(c_phone))

async def is_sms_processed(uid: str) -> bool:
    return await pool.fetchval("SELECT 1 FROM sms_logs WHERE packet_uid = $1", uid) is not None

async def log_sms_attempt(uid, sender, target, content, status, resp):
    await pool.execute("INSERT INTO sms_logs (packet_uid, sender_phone, target_phone, message_content, status, provider_response) VALUES ($1, $2, $3, $4, $5, $6)", uid, sender, target, content, status, resp)

# ==================================================
#      DEPREM ALGORİTMASI VERİTABANI İŞLEMLERİ
# ==================================================

async def insert_seismic_signal(user_id: str, pga: float, lat: float, lng: float):
    """Telefondan gelen ham titreşim verisini kaydeder."""
    await pool.execute("""
        INSERT INTO seismic_signals (user_id, pga, latitude, longitude, created_at)
        VALUES ($1, $2, $3, $4, NOW())
    """, user_id, pga, lat, lng)

async def get_nearby_users_count(lat: float, lng: float, radius_km: int) -> int:
    """O bölgedeki aktif (son 1 saatte sinyal/konum atmış) kullanıcı sayısı."""
    query = """
        SELECT COUNT(*) FROM users
        WHERE last_seen > NOW() - INTERVAL '1 hour'
        AND ST_DWithin(location, ST_SetSRID(ST_MakePoint($1, $2), 4326)::geography, $3)
    """
    # $1=lng, $2=lat, $3=meters
    count = await pool.fetchval(query, lng, lat, radius_km * 1000)
    return count if count else 0

async def get_recent_signals_stats(lat: float, lng: float, radius_km: int, seconds: int):
    """Son X saniyede, X km yarıçapındaki sinyalleri analiz eder."""
    query = """
        SELECT COUNT(DISTINCT user_id) as signal_count, AVG(pga) as avg_pga, AVG(latitude) as center_lat, AVG(longitude) as center_lng
        FROM seismic_signals
        WHERE created_at > (NOW() - ($4 || ' seconds')::INTERVAL)
        AND ST_DWithin(location, ST_SetSRID(ST_MakePoint($1, $2), 4326)::geography, $3)
    """
    return await pool.fetchrow(query, lng, lat, radius_km * 1000, str(seconds))

async def create_app_detected_event(lat: float, lng: float, intensity: str, max_pga: float, user_count: int):
    """
    Kenet algoritmasının tespit ettiği depremi kaydeder.
    Spam Koruması: Aynı bölgede son 5 dakikada kayıt varsa tekrar oluşturmaz.
    """
    exists = await pool.fetchrow("""
        SELECT id FROM app_detected_events
        WHERE created_at > NOW() - INTERVAL '5 minutes'
        AND ST_DWithin(location, ST_SetSRID(ST_MakePoint($1, $2), 4326)::geography, 20000)
    """, lng, lat)

    if exists: return exists['id']

    return await pool.fetchval("""
        INSERT INTO app_detected_events (latitude, longitude, intensity_label, max_pga, participating_users, created_at)
        VALUES ($1, $2, $3, $4, $5, NOW()) RETURNING id
    """, lat, lng, intensity, max_pga, user_count)

async def get_app_detected_events_db(hours: int):
    """Mobil uygulama 1. Sekme (Polling) için."""
    return await pool.fetch("""
        SELECT * FROM app_detected_events
        WHERE created_at > NOW() - ($1 || ' hours')::INTERVAL
        ORDER BY created_at DESC
    """, str(hours))

async def get_confirmed_earthquakes_db(hours: int):
    """Mobil uygulama 2. Sekme (Resmi Veri) için."""
    return await pool.fetch("""
        SELECT * FROM confirmed_earthquakes
        WHERE occurred_at > NOW() - ($1 || ' hours')::INTERVAL
        ORDER BY occurred_at DESC
    """, str(hours))

async def get_confirmed_earthquakes_db(hours: int = 24):
    """
    Son X saatte gerçekleşen resmi depremleri getirir.
    En yeniden en eskiye sıralar.
    """
    query = """
        SELECT * FROM confirmed_earthquakes
        WHERE occurred_at > NOW() - ($1 || ' hours')::INTERVAL
        ORDER BY occurred_at DESC
    """
    return await pool.fetch(query, str(hours))