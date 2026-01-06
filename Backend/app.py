from fastapi import FastAPI, HTTPException, BackgroundTasks
from typing import List
from models import *
from database import *
from utils import generate_user_keys, generate_otp, send_real_sms_via_provider
from crypto_utils import decrypt_gateway_message
import logging
from apscheduler.schedulers.asyncio import AsyncIOScheduler

# YANINDAKİ DOSYADAN İMPORT EDİYORUZ (Klasör adı yok)
from kandilli_service import fetch_and_store_kandilli_data

# Loglama Ayarları
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("KENET-CORE")

app = FastAPI(title="KENET Secure Gateway & DEUS", version="5.0")

# ==========================================
#      SİSTEM SABİTLERİ VE AYARLAR
# ==========================================

FUSION_RADIUS_KM = 20       # Analiz yarıçapı
FUSION_TIME_WINDOW_SEC = 30 # Son 30 saniye
MIN_SIGNAL_COUNT = 1        # Test için 1
MAX_REALISTIC_PGA = 5.0     # Filtre (2.5 olacak)

# Zamanlayıcı
scheduler = AsyncIOScheduler()

# ==========================================
#      BAŞLANGIÇ VE KAPANIŞ
# ==========================================

@app.on_event("startup")
async def startup_event():
    await connect_db()
    logger.info("✅ Veritabanı bağlantısı başarılı.")

    # Zamanlayıcıyı başlat (60 saniyede bir yan dosyayı çalıştır)
    scheduler.add_job(fetch_and_store_kandilli_data, 'interval', seconds=60)
    scheduler.start()

    # İlk sefer hemen çalıştır
    await fetch_and_store_kandilli_data()
    logger.info("🚀 Kandilli Takip Servisi Başlatıldı.")

@app.on_event("shutdown")
async def shutdown_event():
    scheduler.shutdown()
    await close_db()
    logger.info("🛑 Sistem kapatıldı.")

# ==========================================
#      KULLANICI VE OTP İŞLEMLERİ
# ==========================================

@app.post("/request_otp", response_model=StatusResponse)
async def request_otp_endpoint(request: RequestOtpRequest):
    phone = request.phone_number.strip()
    otp_code = generate_otp()
    await upsert_otp(phone, otp_code)
    print(f"\n--- SMS DOĞRULAMA KODU: {otp_code} ---\n", flush=True)
    return StatusResponse(message="Kod gönderildi.")

@app.post("/verify_otp", response_model=VerifyOtpResponse)
async def verify_otp_endpoint(request: VerifyOtpRequest):
    user = await get_user_by_phone(request.phone_number)
    if not user or str(user.get('verification_code')) != str(request.code):
        raise HTTPException(status_code=401, detail="Hatalı doğrulama kodu.")
    current_priv = user.get('ibe_private_key')
    is_new = current_priv is None or str(current_priv).strip() == ""
    priv_key, pub_key = ("", "")
    if is_new:
        priv_key, pub_key = generate_user_keys()
        await activate_new_user(request.phone_number, user['user_id'], priv_key, pub_key)
    else:
        priv_key = str(user['ibe_private_key'])
        pub_key = str(user['public_params'])
    return VerifyOtpResponse(
        is_new_user=bool(is_new), user_id=str(user['user_id']), phone_number=str(request.phone_number),
        display_name=str(user.get('display_name') or ""), blood_type=str(user.get('blood_type') or ""),
        ibe_private_key=str(priv_key), public_params=str(pub_key)
    )

@app.post("/complete_profile", response_model=StatusResponse)
async def complete_profile_endpoint(request: CompleteProfileRequest):
    await update_user_profile(request.phone_number, request.display_name, request.blood_type)
    if request.contacts: await add_contacts_to_db(request.phone_number, request.contacts)
    return StatusResponse(message="Profil tamamlandı.")

@app.post("/update_location", response_model=StatusResponse)
async def update_location_endpoint(request: UpdateLocationRequest):
    await update_user_location(request.user_id, request.latitude, request.longitude)
    return StatusResponse(message="Konum güncellendi.")

# ==========================================
#      REHBER VE SMS GATEWAY
# ==========================================

@app.post("/check_contacts", response_model=CheckContactsResponse)
async def check_contacts_endpoint(request: CheckContactsRequest):
    rows = await get_registered_users_details(request.phone_numbers)
    users = [RegisteredUserItem(user_id=r['user_id'], phone_number=r['phone_number'], display_name=r['display_name'] or "", blood_type=r['blood_type'], public_key=r['public_params'], latitude=r['latitude'], longitude=r['longitude']) for r in rows]
    return CheckContactsResponse(registered_users=users)

@app.post("/sync_contacts", response_model=SyncContactsResponse)
async def sync_contacts_endpoint(request: SyncContactsRequest):
    rows = await get_user_contacts(request.user_id)
    contacts = [SyncContactItem(contact_id=r['contact_id'], phone_number=r['phone_number'], display_name=r['display_name'], latitude=r['latitude'], longitude=r['longitude'], public_key=r['public_params']) for r in rows]
    return SyncContactsResponse(contacts=contacts)

@app.post("/delete_contact", response_model=StatusResponse)
async def delete_contact_endpoint(request: DeleteContactRequest):
    await delete_contact_from_db(request.owner_phone, request.contact_phone)
    return StatusResponse(message="Silindi.")

@app.post("/send_gateway_sms", response_model=StatusResponse)
async def send_gateway_sms_endpoint(request: GatewaySmsRequest):
    if await is_sms_processed(request.packet_uid): return StatusResponse(message="Duplicate.")
    sender_user = await get_user_by_id(request.sender_id)
    if not sender_user: return StatusResponse(message="User not found.")
    plaintext_msg = decrypt_gateway_message(request.encrypted_payload, request.nonce, request.ephemeral_key, request.integrity_tag)
    if "HATASI" in plaintext_msg: return StatusResponse(message="Decryption Failed")
    final_msg = f"{plaintext_msg}\n--\nKimden: {sender_user['phone_number']} (KENET)"
    provider_resp = await send_real_sms_via_provider(request.target_phone, final_msg)
    await log_sms_attempt(request.packet_uid, sender_user['phone_number'], request.target_phone, "ENCRYPTED", "SENT", provider_resp)
    return StatusResponse(message="Secure SMS Sent.")

# =================================================================
#      DEPREM ANALİZ MOTORU
# =================================================================

def calculate_mmi_intensity(pga_g: float) -> str:
    if pga_g < 0.0005: return "HİSSEDİLMEZ"
    elif pga_g < 0.003: return "ZAYIF"
    elif pga_g < 0.028: return "HAFİF"
    elif pga_g < 0.062: return "ORTA"
    elif pga_g < 0.12:  return "GÜÇLÜ"
    elif pga_g < 0.22:  return "ÇOK GÜÇLÜ"
    elif pga_g < 0.40:  return "YIKICI"
    elif pga_g < 0.75:  return "ÇOK YIKICI"
    else: return "EKSTREM"

def get_dynamic_threshold(total_users: int) -> float:
    if total_users <= 5: return 0.60
    if total_users <= 20: return 0.40
    if total_users <= 100: return 0.25
    return 0.15

async def process_fusion_logic(lat: float, lng: float, triggering_pga: float):
    if triggering_pga > MAX_REALISTIC_PGA:
        logger.warning(f"⚠️ Anormal Veri ({triggering_pga}g) yok sayıldı.")
        return

    stats = await get_recent_signals_stats(lat, lng, FUSION_RADIUS_KM, FUSION_TIME_WINDOW_SEC)

    # Eğer veritabanında yeterli sinyal yoksa çık (Test için 1 yapmıştın)
    if not stats or stats['signal_count'] < MIN_SIGNAL_COUNT:
        return

    signal_count = stats['signal_count']
    avg_pga = stats['avg_pga']
    center_lat = stats['center_lat']
    center_lng = stats['center_lng']

    total_users_in_area = await get_nearby_users_count(center_lat, center_lng, FUSION_RADIUS_KM)
    if total_users_in_area == 0: total_users_in_area = 1

    ratio = signal_count / total_users_in_area
    required_ratio = get_dynamic_threshold(total_users_in_area)

    logger.info(f"📊 ANALİZ: {signal_count}/{total_users_in_area} (%{ratio*100:.1f})")

    # =================================================================
    # DÜZELTME: TEST AMAÇLI İSTİSNA EKLE
    # Eğer toplam 2 cihaz varsa ve en az 1 sinyal geldiyse onayla.
    # Normalde required_ratio 0.60 olduğu için 1/2 kurtarmıyordu.
    # =================================================================
    is_confirmed = False

    if total_users_in_area <= 2 and signal_count >= MIN_SIGNAL_COUNT:
        is_confirmed = True
        logger.info("🧪 TEST MODU: Az sayıda cihazla deprem onaylandı.")
    elif ratio >= required_ratio:
        is_confirmed = True

    if is_confirmed:
        intensity_label = calculate_mmi_intensity(avg_pga)
        logger.warning(f"🚨 DEPREM ONAYLANDI! Şiddet: {intensity_label}")
        await create_app_detected_event(center_lat, center_lng, intensity_label, avg_pga, signal_count)

@app.post("/signal", response_model=StatusResponse)
async def receive_seismic_signal(request: SeismicSignalRequest, background_tasks: BackgroundTasks):
    await insert_seismic_signal(request.user_id, request.pga, request.latitude, request.longitude)
    await update_user_location(request.user_id, request.latitude, request.longitude)
    background_tasks.add_task(process_fusion_logic, request.latitude, request.longitude, request.pga)
    return StatusResponse(message="Sinyal Alındı")

# --- 1. Sekme: Kenet Algılamaları ---
@app.get("/app_detected_events", response_model=List[AppDetectedEventItem])
async def get_app_detected_events_endpoint():
    rows = await get_app_detected_events_db(hours=24)
    return [AppDetectedEventItem(id=row['id'], latitude=row['latitude'], longitude=row['longitude'], intensity_label=row['intensity_label'], max_pga=row['max_pga'], participating_users=row['participating_users'], created_at=row['created_at']) for row in rows]

# --- 2. Sekme: Resmi Veriler ---
@app.get("/confirmed_earthquakes", response_model=EarthquakeListResponse)
async def get_confirmed_earthquakes_endpoint():
    rows = await get_confirmed_earthquakes_db(hours=24)
    return EarthquakeListResponse(earthquakes=[ConfirmedEarthquakeItem(id=row['id'], external_id=row['external_id'], title=row['title'], magnitude=row['magnitude'], depth=row['depth'], latitude=row['latitude'], longitude=row['longitude'], occurred_at=row['occurred_at']) for row in rows])
