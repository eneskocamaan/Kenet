package com.eneskocamaan.kenet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Bu 'tableName' ismi, DAO içindeki sorgudaki isimle AYNI olmalı
@Entity(tableName = "seismic_events")
data class SeismicEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val timestamp: Long,
    val pga: Double,
    val latitude: Double,
    val longitude: Double,
    val isSentToServer: Boolean = false
)