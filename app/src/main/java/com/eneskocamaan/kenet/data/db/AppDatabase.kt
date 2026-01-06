package com.eneskocamaan.kenet.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserEntity::class,
        MessageEntity::class,
        ContactEntity::class,
        DiscoveredPeerEntity::class,
        SeismicEventEntity::class,
        AppDetectedEventEntity::class
    ],
    version = 1, // <-- VERSİYON 1'DEN 2'YE GÜNCELLENDİ
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    // Mevcut DAO'lar
    abstract fun userDao(): UserDao
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun seismicDao(): SeismicDao
    abstract fun appDetectedEventDao(): AppDetectedEventDao

    // Yeni eklediğimiz DAO
    abstract fun discoveredPeerDao(): DiscoveredPeerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kenet_database"
                )
                    // Versiyon değiştiğinde (1->2) eski verileri silip temiz kurulum yapar.
                    // Geliştirme aşaması için en kolayı budur.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}