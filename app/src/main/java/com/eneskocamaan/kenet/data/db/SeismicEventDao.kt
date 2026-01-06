package com.eneskocamaan.kenet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SeismicDao {
    @Insert
    suspend fun insertSignal(event: SeismicEventEntity)

    // Son 24 saatteki algılamalarımız
    @Query("SELECT * FROM seismic_events ORDER BY timestamp DESC")
    fun getAllSeismicEvents(): Flow<List<SeismicEventEntity>>
}

