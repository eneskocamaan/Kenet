from pydantic import BaseModel
from typing import Optional, List

# --- Yardımcı Modeller ---
class ContactModel(BaseModel):
    phone_number: str
    display_name: str

# --- İstek (Request) Modelleri ---
class RequestOtpRequest(BaseModel):
    phone_number: str

class VerifyOtpRequest(BaseModel):
    phone_number: str
    code: str

class CompleteProfileRequest(BaseModel):
    phone_number: str
    display_name: str
    blood_type: Optional[str] = None
    contacts: List[ContactModel] = []

class UpdateLocationRequest(BaseModel):
    user_id: str
    latitude: float
    longitude: float

class CheckContactsRequest(BaseModel):
    phone_numbers: List[str]

class SyncContactsRequest(BaseModel):
    user_id: str

class DeleteContactRequest(BaseModel):
    owner_phone: str
    contact_phone: str

# --- [GÜVENLİ] GATEWAY SMS MODELİ ---
class GatewaySmsRequest(BaseModel):
    packet_uid: str
    sender_id: str
    sender_phone: str
    target_phone: str
    encrypted_payload: str
    nonce: str
    ephemeral_key: str
    integrity_tag: str

# models.py dosyası

class VerifyOtpResponse(BaseModel):
    is_new_user: bool           # True/False
    user_id: str                # String olması zorunlu
    phone_number: str           # String olması zorunlu
    display_name: Optional[str] = None
    blood_type: Optional[str] = None
    ibe_private_key: Optional[str] = None
    public_params: Optional[str] = None

class StatusResponse(BaseModel):
    message: str
    user_id: Optional[str] = None

# [GÜNCELLENDİ] Tüm detayları içeren model
class RegisteredUserItem(BaseModel):
    user_id: str
    phone_number: str
    display_name: Optional[str] = ""
    blood_type: Optional[str] = None
    public_key: Optional[str] = None  # public_params
    latitude: Optional[float] = 0.0
    longitude: Optional[float] = 0.0

class CheckContactsResponse(BaseModel):
    registered_users: List[RegisteredUserItem]

# [GÜNCELLENDİ] SyncContactsResponse için de aynı yapıyı kullanabiliriz aslında ama
# mevcut yapıyı bozmamak için SyncContactItem'ı tutuyoruz.
class SyncContactItem(BaseModel):
    contact_id: Optional[str]
    phone_number: str
    display_name: str
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    public_key: Optional[str] = None

class SyncContactsResponse(BaseModel):
    contacts: List[SyncContactItem]