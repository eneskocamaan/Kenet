package com.eneskocamaan.kenet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_detected_events")
data class AppDetectedEventEntity(
    @PrimaryKey
    val id: Int, // Backend ID'si
    val latitude: Double,
    val longitude: Double,
    val intensityLabel: String, // "HAFİF", "YIKICI"
    val maxPga: Double,
    val participatingUsers: Int, // Kaç kişi hissetti?
    val createdAt: String
)