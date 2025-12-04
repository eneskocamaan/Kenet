from fastapi import FastAPI, HTTPException
from models import (
    RequestOtpRequest, VerifyOtpRequest, VerifyOtpResponse,
    CompleteProfileRequest, StatusResponse,
    CheckContactsRequest, CheckContactsResponse,
    SyncContactsRequest, SyncContactsResponse, SyncContactItem,
    DeleteContactRequest
)
from database import (
    connect_db, close_db, get_user_by_phone, upsert_otp,
    activate_new_user, update_user_profile, add_contacts_to_db,
    get_registered_users, get_user_contacts, delete_contact_from_db
)
from utils import generate_short_id, generate_mock_key, generate_public_params, generate_otp

import logging

# Loglama ayarları
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="KENET IBE Kimlik Servisi", version="2.0")

# --- FastAPI Yaşam Döngüsü Olayları ---
@app.on_event("startup")
async def startup_event():
    """Uygulama başlatıldığında veritabanına bağlan."""
    await connect_db()

@app.on_event("shutdown")
async def shutdown_event():
    """Uygulama kapatıldığında veritabanı bağlantısını kapat."""
    await close_db()
# ---------------------------------------

@app.post("/request_otp", response_model=StatusResponse)
async def request_otp_endpoint(request: RequestOtpRequest):
    """
    Kullanıcıdan telefon numarasını alır ve bir OTP kodu oluşturur/günceller.
    """
    phone = request.phone_number.strip()
    otp_code = generate_otp()

    # Veritabanına OTP'yi kaydet (Numara database.py içinde sanitize edilir)
    await upsert_otp(phone, otp_code)

    # MOCK SMS GÖNDERİMİ (Konsola Yazdır)
    print(f"\n--- MOCK SMS ---\nNumara: {phone}\nKOD: {otp_code}\n------------------\n", flush=True)

    return StatusResponse(message="Doğrulama kodu gönderildi.")


@app.post("/verify_otp", response_model=VerifyOtpResponse)
async def verify_otp_endpoint(request: VerifyOtpRequest):
    """
    OTP kodunu doğrular. Kullanıcının yeni veya mevcut olduğunu belirler.
    """
    phone = request.phone_number
    code = request.code

    user = await get_user_by_phone(phone)

    # Kullanıcı yoksa veya kod yanlışsa hata ver
    if not user or user['verification_code'] != code:
        raise HTTPException(status_code=401, detail="Doğrulama kodu hatalı veya geçersiz.")

    # Eğer IBE Private Key henüz yoksa, bu yeni bir kullanıcıdır
    is_new_user = not bool(user['ibe_private_key'])

    if is_new_user:
        # SENARYO A: Yeni Kullanıcı (Aktive Et)
        user_id = user['user_id']
        private_key = generate_mock_key(user_id)
        public_params = generate_public_params()

        await activate_new_user(phone, user_id, private_key, public_params)
        logger.info(f"Yeni Kullanıcı Aktive Edildi: {user_id}")
    else:
        # SENARYO B: Mevcut Kullanıcı
        user_id = user['user_id']
        private_key = user['ibe_private_key']
        public_params = user['public_params']
        logger.info(f"Mevcut Kullanıcı Giriş Yaptı: {user_id}")

    return VerifyOtpResponse(
        is_new_user=is_new_user,
        user_id=user_id,
        phone_number=phone,
        display_name=user.get('display_name'),
        blood_type=user.get('blood_type'),
        ibe_private_key=private_key,
        public_params=public_params
    )


@app.post("/complete_profile", response_model=StatusResponse)
async def complete_profile_endpoint(request: CompleteProfileRequest):
    """
    1. Yeni kullanıcının profil bilgilerini (isim, kan grubu) kaydeder.
    2. Varsa, seçilen güvenilir kişileri rehber veritabanına işler.
    """

    # 1. Profil Bilgilerini Güncelle
    await update_user_profile(
        phone_number=request.phone_number,
        display_name=request.display_name,
        blood_type=request.blood_type
    )

    # 2. Seçilen Kişileri İşle (Rehber Senkronizasyonu)
    if request.contacts:
        # Bu fonksiyon database.py içindedir.
        await add_contacts_to_db(request.phone_number, request.contacts)
        logger.info(f"{request.phone_number} kullanıcısı için {len(request.contacts)} kişi işlendi.")

    logger.info(f"Kullanıcı profili tamamlandı: {request.phone_number}")

    # Android tarafında güncelleme sonrası user_id'ye ihtiyaç duyulabilir.
    user = await get_user_by_phone(request.phone_number)

    return StatusResponse(
        message="Profil ve rehber başarıyla güncellendi.",
        user_id=user['user_id'] if user else None
    )


@app.post("/check_contacts", response_model=CheckContactsResponse)
async def check_contacts_endpoint(request: CheckContactsRequest):
    """
    Rehberdeki numaralardan hangilerinin uygulamaya kayıtlı olduğunu kontrol eder.
    "Kenet Kullanıyor" rozeti için kullanılır.
    """
    # database.py içindeki fonksiyon gelen listeyi sanitize edip sorgular.
    registered = await get_registered_users(request.phone_numbers)
    return CheckContactsResponse(registered_numbers=registered)


@app.post("/sync_contacts", response_model=SyncContactsResponse)
async def sync_contacts_endpoint(request: SyncContactsRequest):
    """
    Kullanıcının sunucudaki rehber yedeğini geri döndürür.
    Splash ekranında veya cihaz değişiminde kullanılır.
    """
    # Veritabanından kullanıcı rehberini çek
    rows = await get_user_contacts(request.user_id)

    # Pydantic modeline dönüştür
    contact_list = [
        SyncContactItem(
            contact_id=row['contact_id'],
            phone_number=row['phone_number'],
            display_name=row['display_name']
        ) for row in rows
    ]

    return SyncContactsResponse(contacts=contact_list)



@app.post("/delete_contact", response_model=StatusResponse)
async def delete_contact_endpoint(request: DeleteContactRequest):
    await delete_contact_from_db(request.owner_phone, request.contact_phone)
    return StatusResponse(message="Kişi silindi.")