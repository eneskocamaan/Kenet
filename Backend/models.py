from pydantic import BaseModel
from typing import Optional, List

# --- Yardımcı Modeller ---

class ContactModel(BaseModel):
    """Android'den gelen kişi listesindeki tekil kişi."""
    phone_number: str
    display_name: str
    # isSelected backend'e lazım değil, zaten seçilenler geliyor.

# --- Gelen İstek (Request) Modelleri ---

class RequestOtpRequest(BaseModel):
    """Sadece telefon numarası içeren OTP istek modeli."""
    phone_number: str

class VerifyOtpRequest(BaseModel):
    """OTP doğrulaması için gelen veriler."""
    phone_number: str
    code: str

class CompleteProfileRequest(BaseModel):
    """Yeni kullanıcının profilini tamamlamak için gönderdiği bilgiler."""
    phone_number: str # Kullanıcıyı doğrulamak için
    display_name: str
    blood_type: Optional[str] = None
    # YENİ: Seçilen kişiler listesi (Varsayılan boş liste)
    contacts: List[ContactModel] = []

# --- Giden Yanıt (Response) Modelleri ---

class VerifyOtpResponse(BaseModel):
    """
    Başarılı OTP doğrulaması sonrası dönen yanıt.
    Kullanıcının yeni mi eski mi olduğunu, anahtarlarını ve mevcut verilerini içerir.
    """
    is_new_user: bool
    user_id: str
    phone_number: str
    display_name: Optional[str] = None
    blood_type: Optional[str] = None
    ibe_private_key: str
    public_params: str

class StatusResponse(BaseModel):
    """Basit durum mesajları için (örn: 'Kod gönderildi', 'Profil güncellendi')."""
    message: str
    # Opsiyonel olarak oluşturulan user_id'yi dönebiliriz (Android tarafında işe yarayabilir)
    user_id: Optional[str] = None

class CheckContactsRequest(BaseModel):
    phone_numbers: List[str]

class CheckContactsResponse(BaseModel):
    registered_numbers: List[str]

class DeleteContactRequest(BaseModel):
    owner_phone: str
    contact_phone: str

# --- SYNC (SENKRONİZASYON) MODELLERİ ---

class SyncContactsRequest(BaseModel):
    user_id: str

class SyncContactItem(BaseModel):
    contact_id: Optional[str]
    phone_number: str
    display_name: str

class SyncContactsResponse(BaseModel):
    contacts: List[SyncContactItem]

