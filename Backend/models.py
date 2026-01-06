from pydantic import BaseModel
from typing import Optional, List
from datetime import datetime

# --- Temel Modeller ---
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

class GatewaySmsRequest(BaseModel):
    packet_uid: str
    sender_id: str
    sender_phone: str
    target_phone: str
    encrypted_payload: str
    nonce: str
    ephemeral_key: str
    integrity_tag: str

# --- DEPREM SİNYAL İSTEĞİ ---
class SeismicSignalRequest(BaseModel):
    user_id: str
    pga: float
    latitude: float
    longitude: float

# --- Yanıt (Response) Modelleri ---
class StatusResponse(BaseModel):
    message: str
    user_id: Optional[str] = None

class VerifyOtpResponse(BaseModel):
    is_new_user: bool
    user_id: str
    phone_number: str
    display_name: Optional[str] = None
    blood_type: Optional[str] = None
    ibe_private_key: Optional[str] = None
    public_params: Optional[str] = None

class RegisteredUserItem(BaseModel):
    user_id: str
    phone_number: str
    display_name: Optional[str] = ""
    blood_type: Optional[str] = None
    public_key: Optional[str] = None
    latitude: Optional[float] = 0.0
    longitude: Optional[float] = 0.0

class CheckContactsResponse(BaseModel):
    registered_users: List[RegisteredUserItem]

class SyncContactItem(BaseModel):
    contact_id: Optional[str]
    phone_number: str
    display_name: str
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    public_key: Optional[str] = None

class SyncContactsResponse(BaseModel):
    contacts: List[SyncContactItem]

# --- DEPREM LİSTELEME MODELLERİ ---

# 1. Sekme İçin (Kenet Algılaması)
class AppDetectedEventItem(BaseModel):
    id: int
    latitude: float
    longitude: float
    intensity_label: str
    max_pga: float
    participating_users: int
    created_at: datetime

# 2. Sekme İçin (Resmi Veriler)
class ConfirmedEarthquakeItem(BaseModel):
    id: int
    centroid_latitude: float
    centroid_longitude: float
    intensity_label: str
    radius_km: int
    occurred_at: datetime

class EarthquakeListResponse(BaseModel):
    earthquakes: List[ConfirmedEarthquakeItem]

class ConfirmedEarthquakeItem(BaseModel):
    id: int
    external_id: str
    title: str
    magnitude: float
    depth: float
    latitude: float
    longitude: float
    occurred_at: datetime

class EarthquakeListResponse(BaseModel):
    earthquakes: List[ConfirmedEarthquakeItem]
