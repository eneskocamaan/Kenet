package com.eneskocamaan.kenet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDetectedEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<AppDetectedEventEntity>)

    @Query("SELECT * FROM app_detected_events ORDER BY createdAt DESC")
    fun getAllEvents(): Flow<List<AppDetectedEventEntity>>
}