package com.eneskocamaan.kenet.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// HATA ÇÖZÜMÜ: entities listesine 'ContactEntity::class' eklendi.
// Versiyon numarası 2'ye çıkarıldı (Şema değiştiği için).
@Database(
    entities = [UserEntity::class, MessageEntity::class, ContactEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun messageDao(): MessageDao

    // HATA ÇÖZÜMÜ: ContactDao buraya eklendi.
    abstract fun contactDao(): ContactDao

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
                    // Şema değişikliğinde (versiyon 1 -> 2) verileri kaybetmemek için migration gerekir.
                    // Geliştirme aşamasında olduğumuz için 'fallbackToDestructiveMigration' diyerek
                    // eski verileri silip tabloyu yeniden kurmasını sağlıyoruz.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}