from fastapi import FastAPI, HTTPException
from models import (
    RequestOtpRequest, VerifyOtpRequest, VerifyOtpResponse,
    CompleteProfileRequest, StatusResponse,
    CheckContactsRequest, CheckContactsResponse,
    SyncContactsRequest, SyncContactsResponse, SyncContactItem,
    DeleteContactRequest, UpdateLocationRequest # [YENİ]
)
from database import (
    connect_db, close_db, get_user_by_phone, upsert_otp,
    activate_new_user, update_user_profile, add_contacts_to_db,
    get_registered_users, get_user_contacts, delete_contact_from_db,
    update_user_location # [YENİ]
)
from utils import generate_short_id, generate_mock_key, generate_public_params, generate_otp
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="KENET IBE Kimlik Servisi", version="2.1")

@app.on_event("startup")
async def startup_event():
    await connect_db()

@app.on_event("shutdown")
async def shutdown_event():
    await close_db()

@app.post("/request_otp", response_model=StatusResponse)
async def request_otp_endpoint(request: RequestOtpRequest):
    phone = request.phone_number.strip()
    otp_code = generate_otp()
    await upsert_otp(phone, otp_code)
    print(f"\n--- MOCK SMS ---\nNumara: {phone}\nKOD: {otp_code}\n------------------\n", flush=True)
    return StatusResponse(message="Doğrulama kodu gönderildi.")

@app.post("/verify_otp", response_model=VerifyOtpResponse)
async def verify_otp_endpoint(request: VerifyOtpRequest):
    phone = request.phone_number
    code = request.code
    user = await get_user_by_phone(phone)

    if not user or user['verification_code'] != code:
        raise HTTPException(status_code=401, detail="Doğrulama kodu hatalı.")

    is_new_user = not bool(user['ibe_private_key'])

    if is_new_user:
        user_id = user['user_id']
        private_key = generate_mock_key(user_id)
        public_params = generate_public_params()
        await activate_new_user(phone, user_id, private_key, public_params)
        logger.info(f"Yeni Kullanıcı Aktive: {user_id}")
    else:
        user_id = user['user_id']
        private_key = user['ibe_private_key']
        public_params = user['public_params']
        logger.info(f"Mevcut Kullanıcı: {user_id}")

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
    await update_user_profile(
        phone_number=request.phone_number,
        display_name=request.display_name,
        blood_type=request.blood_type
    )
    if request.contacts:
        await add_contacts_to_db(request.phone_number, request.contacts)

    user = await get_user_by_phone(request.phone_number)
    return StatusResponse(message="Profil tamamlandı.", user_id=user['user_id'] if user else None)

# [YENİ] Konum Güncelleme Endpoint'i
@app.post("/update_location", response_model=StatusResponse)
async def update_location_endpoint(request: UpdateLocationRequest):
    """
    Kullanıcının anlık konumunu veritabanına kaydeder.
    Splash ekranda çağrılır.
    """
    await update_user_location(request.user_id, request.latitude, request.longitude)
    return StatusResponse(message="Konum güncellendi.")

@app.post("/check_contacts", response_model=CheckContactsResponse)
async def check_contacts_endpoint(request: CheckContactsRequest):
    registered = await get_registered_users(request.phone_numbers)
    return CheckContactsResponse(registered_numbers=registered)

@app.post("/sync_contacts", response_model=SyncContactsResponse)
async def sync_contacts_endpoint(request: SyncContactsRequest):
    """
    Kullanıcının rehberini dönerken artık kişilerin
    LATITUDE ve LONGITUDE bilgilerini de içerir.
    """
    rows = await get_user_contacts(request.user_id)

    # Veritabanından gelen satırları Pydantic modeline eşle
    contact_list = [
        SyncContactItem(
            contact_id=row['contact_id'],
            phone_number=row['phone_number'],
            display_name=row['display_name'],
            latitude=row['latitude'],   # [YENİ]
            longitude=row['longitude']  # [YENİ]
        ) for row in rows
    ]

    return SyncContactsResponse(contacts=contact_list)

@app.post("/delete_contact", response_model=StatusResponse)
async def delete_contact_endpoint(request: DeleteContactRequest):
    await delete_contact_from_db(request.owner_phone, request.contact_phone)
    return StatusResponse(message="Kişi silindi.")