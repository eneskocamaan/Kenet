package com.eneskocamaan.kenet.utils

import android.util.Base64
import com.eneskocamaan.kenet.proto.EmergencyBeacon

object EmergencyDataMapper {

    fun stringToProtoBloodType(bloodString: String?): EmergencyBeacon.BloodType {
        return when (bloodString) {
            "A Rh+" -> EmergencyBeacon.BloodType.A_POS
            "A Rh-" -> EmergencyBeacon.BloodType.A_NEG
            "B Rh+" -> EmergencyBeacon.BloodType.B_POS
            "B Rh-" -> EmergencyBeacon.BloodType.B_NEG
            "AB Rh+" -> EmergencyBeacon.BloodType.AB_POS
            "AB Rh-" -> EmergencyBeacon.BloodType.AB_NEG
            "0 Rh+" -> EmergencyBeacon.BloodType.ZERO_POS
            "0 Rh-" -> EmergencyBeacon.BloodType.ZERO_NEG
            else -> EmergencyBeacon.BloodType.UNKNOWN
        }
    }

    fun protoBloodTypeToString(protoType: EmergencyBeacon.BloodType): String {
        return when (protoType) {
            EmergencyBeacon.BloodType.A_POS -> "A Rh+"
            EmergencyBeacon.BloodType.A_NEG -> "A Rh-"
            EmergencyBeacon.BloodType.B_POS -> "B Rh+"
            EmergencyBeacon.BloodType.B_NEG -> "B Rh-"
            EmergencyBeacon.BloodType.AB_POS -> "AB Rh+"
            EmergencyBeacon.BloodType.AB_NEG -> "AB Rh-"
            EmergencyBeacon.BloodType.ZERO_POS -> "0 Rh+"
            EmergencyBeacon.BloodType.ZERO_NEG -> "0 Rh-"
            else -> "Bilinmiyor"
        }
    }

    fun mapProtoStatusToText(status: EmergencyBeacon.EmStatus): String {
        return when (status) {
            EmergencyBeacon.EmStatus.CRITICAL -> "Kritik"
            EmergencyBeacon.EmStatus.URGENT -> "Acil"
            EmergencyBeacon.EmStatus.SAFE -> "Güvende"
            else -> "Bilinmiyor"
        }
    }

    fun protoToBase64(beacon: EmergencyBeacon): String {
        return Base64.encodeToString(beacon.toByteArray(), Base64.NO_WRAP)
    }

    fun base64ToProto(base64String: String): EmergencyBeacon? {
        return try {
            val bytes = Base64.decode(base64String, Base64.NO_WRAP)
            EmergencyBeacon.parseFrom(bytes)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}