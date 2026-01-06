package com.eneskocamaan.kenet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiscoveredPeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePeer(peer: DiscoveredPeerEntity)

    // Sadece belirli bir kişiyi getir (Spam kontrolü için)
    @Query("SELECT * FROM discovered_peers WHERE userId = :id LIMIT 1")
    suspend fun getPeerById(id: String): DiscoveredPeerEntity?

    @Query("SELECT * FROM discovered_peers ORDER BY lastSeenTimestamp DESC")
    fun getAllPeers(): Flow<List<DiscoveredPeerEntity>>

    // Bayat verileri sil (Örn: 10 dakikadan eski)
    @Query("DELETE FROM discovered_peers WHERE lastSeenTimestamp < :thresholdTime")
    suspend fun deleteStalePeers(thresholdTime: Long)
}