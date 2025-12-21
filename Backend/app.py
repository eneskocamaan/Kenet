from fastapi import FastAPI, HTTPException
from models import *
from database import *
from utils import generate_user_keys, generate_otp, send_real_sms_via_provider
from crypto_utils import decrypt_gateway_message
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("KENET-BACKEND")

app = FastAPI(title="KENET Secure Gateway", version="3.0")

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
    print(f"\n--- SMS DOĞRULAMA KODU: {otp_code} ---\n", flush=True)
    return StatusResponse(message="Kod gönderildi.")

@app.post("/verify_otp", response_model=VerifyOtpResponse)
async def verify_otp_endpoint(request: VerifyOtpRequest):
    # 1. Kullanıcıyı bul
    user = await get_user_by_phone(request.phone_number)

    # 2. Kod Doğrulaması
    # Verification code db'de string mi int mi? String'e çevirip karşılaştırıyoruz.
    if not user or str(user.get('verification_code')) != str(request.code):
        raise HTTPException(status_code=401, detail="Hatalı doğrulama kodu.")

    # 3. Anahtar Kontrolü
    current_priv = user.get('ibe_private_key')

    # Veritabanında NULL veya boş string ise 'yeni kullanıcı' say
    is_new = current_priv is None or str(current_priv).strip() == ""

    priv_key = ""
    pub_key = ""

    if is_new:
        # Yeni anahtar üret
        priv_key, pub_key = generate_user_keys()
        await activate_new_user(request.phone_number, user['user_id'], priv_key, pub_key)
    else:
        # Var olanı kullan
        priv_key = str(user['ibe_private_key'])
        pub_key = str(user['public_params'])

    # 4. Yanıtı Oluştur (Tipleri Garantiye Al)
    return VerifyOtpResponse(
        is_new_user=bool(is_new),
        user_id=str(user['user_id']),
        phone_number=str(request.phone_number),
        display_name=str(user.get('display_name') or ""), # None gelirse boş string yap
        blood_type=str(user.get('blood_type') or ""),     # None gelirse boş string yap
        ibe_private_key=str(priv_key),
        public_params=str(pub_key)
    )

@app.post("/complete_profile", response_model=StatusResponse)
async def complete_profile_endpoint(request: CompleteProfileRequest):
    await update_user_profile(request.phone_number, request.display_name, request.blood_type)
    if request.contacts:
        await add_contacts_to_db(request.phone_number, request.contacts)
    return StatusResponse(message="Profil tamamlandı.")

@app.post("/update_location", response_model=StatusResponse)
async def update_location_endpoint(request: UpdateLocationRequest):
    await update_user_location(request.user_id, request.latitude, request.longitude)
    return StatusResponse(message="Konum güncellendi.")

# [GÜNCELLENDİ] Tüm detayları döner
@app.post("/check_contacts", response_model=CheckContactsResponse)
async def check_contacts_endpoint(request: CheckContactsRequest):
    rows = await get_registered_users_details(request.phone_numbers)

    users = [
        RegisteredUserItem(
            user_id=row['user_id'],
            phone_number=row['phone_number'],
            display_name=row['display_name'] or "",
            blood_type=row['blood_type'],
            public_key=row['public_params'],
            latitude=row['latitude'],
            longitude=row['longitude']
        ) for row in rows
    ]

    return CheckContactsResponse(registered_users=users)

@app.post("/sync_contacts", response_model=SyncContactsResponse)
async def sync_contacts_endpoint(request: SyncContactsRequest):
    rows = await get_user_contacts(request.user_id)
    contacts = [
        SyncContactItem(
            contact_id=row['contact_id'],
            phone_number=row['phone_number'],
            display_name=row['display_name'],
            latitude=row['latitude'],
            longitude=row['longitude'],
            public_key=row['public_params']
        ) for row in rows
    ]
    return SyncContactsResponse(contacts=contacts)

@app.post("/delete_contact", response_model=StatusResponse)
async def delete_contact_endpoint(request: DeleteContactRequest):
    await delete_contact_from_db(request.owner_phone, request.contact_phone)
    return StatusResponse(message="Silindi.")

# --- GÜVENLİ SMS GATEWAY ---
@app.post("/send_gateway_sms", response_model=StatusResponse)
async def send_gateway_sms_endpoint(request: GatewaySmsRequest):
    if await is_sms_processed(request.packet_uid):
        logger.info(f"Duplicate SMS ignored: {request.packet_uid}")
        return StatusResponse(message="Duplicate message ignored.")

    sender_user = await get_user_by_id(request.sender_id)
    if not sender_user:
        logger.error(f"Sender ID not found: {request.sender_id}")
        return StatusResponse(message="Processed.")

    sender_phone = sender_user['phone_number']

    plaintext_msg = decrypt_gateway_message(
        encrypted_payload_b64=request.encrypted_payload,
        nonce_b64=request.nonce,
        ephemeral_key_b64=request.ephemeral_key,
        integrity_tag_b64=request.integrity_tag
    )

    if plaintext_msg == "[ŞİFRE ÇÖZÜLEMEDİ]" or plaintext_msg == "[FORMAT HATASI]":
        logger.error("Decryption failed! Possible tampering or wrong key.")
        return StatusResponse(message="Security Error: Decryption Failed")

    logger.info(f"🔓 SMS Decrypted successfully. From: {sender_phone} -> To: {request.target_phone}")

    final_msg = f"{plaintext_msg}\n--\nKimden: {sender_phone} (KENET)"
    provider_response = await send_real_sms_via_provider(request.target_phone, final_msg)

    status = "SENT" if "SUCCESS" in provider_response else "FAILED"

    await log_sms_attempt(
        packet_uid=request.packet_uid,
        sender=sender_phone,
        target=request.target_phone,
        content="ENCRYPTED",
        status=status,
        response=provider_response
    )

    if status == "FAILED":
        raise HTTPException(status_code=500, detail="SMS Provider Error")

    return StatusResponse(message="Secure SMS Sent.")