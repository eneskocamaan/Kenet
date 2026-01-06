package com.eneskocamaan.kenet.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "discovered_peers")
data class DiscoveredPeerEntity(
    @PrimaryKey
    val userId: String,
    val displayName: String,
    val status: String,
    val bloodType: String,
    val latitude: Double,
    val longitude: Double,
    val movementScore: Int,
    val distanceMeters: Float,
    val lastSeenTimestamp: Long,
    val source: String
)