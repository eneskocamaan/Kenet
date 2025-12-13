package com.eneskocamaan.kenet.data.db

import androidx.room.Embedded

/**
 * Bu sınıf, Veritabanından hem Kişi Bilgisini hem de
 * o kişiye ait Okunmamış Mesaj Sayısını tek seferde çekmek için kullanılır.
 */
data class ContactWithUnreadCount(
    // Kişinin tüm verileri (ContactEntity) buraya gömülür
    @Embedded val contact: ContactEntity,

    // Hesaplanan okunmamış mesaj sayısı
    val unreadCount: Int
)