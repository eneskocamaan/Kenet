package com.eneskocamaan.kenet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserDao {

    /**
     * Cihazda kayıtlı olan tek kullanıcı profilini getirir.
     */
    @Query("SELECT * FROM user_profile LIMIT 1")
    suspend fun getUserProfile(): UserEntity?

    /**
     * [YENİ EKLENEN]
     * Sohbet ekranında mesaj gönderirken "senderId" olarak kullanmak için
     * sadece ID'yi çeker. Tüm profili yüklemekten daha hızlıdır.
     */
    @Query("SELECT userId FROM user_profile LIMIT 1")
    suspend fun getMyUserId(): String?

    /**
     * Yeni bir kullanıcıyı veritabanına ekler veya mevcut bir kullanıcıyı tamamen günceller.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    /**
     * Mevcut bir kullanıcının sadece profil bilgilerini (isim ve kan grubu) günceller.
     * Bu, yeni bir kullanıcının kaydının ikinci adımında kullanılır.
     */
    @Query("UPDATE user_profile SET displayName = :displayName, bloodType = :bloodType WHERE phoneNumber = :phoneNumber")
    suspend fun updateUserProfile(phoneNumber: String, displayName: String, bloodType: String?)
}