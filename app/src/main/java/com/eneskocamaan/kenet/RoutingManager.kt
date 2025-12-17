package com.eneskocamaan.kenet

import android.location.Location
import java.util.concurrent.ConcurrentHashMap

// Komşu Verisi
data class Neighbor(
    val id: String,
    val lat: Double,
    val lng: Double,
    val rssi: Int = 0,
    val lastSeen: Long = System.currentTimeMillis()
)

object RoutingManager {

    // Komşu Tablosu (Neighbor Table)
    private val neighbors = ConcurrentHashMap<String, Neighbor>()

    // Komşu Bilgisini Güncelle
    fun updateNeighbor(id: String, lat: Double, lng: Double) {
        val isNew = !neighbors.containsKey(id)
        neighbors[id] = Neighbor(id, lat, lng)

        // Sadece yeni eklendiyse veya konumu ciddi değiştiyse log basılabilir
        // Ama şimdilik her güncellemede bilgi verelim
        if (isNew) {
            DebugLogger.log("ROUTING", "➕ Yeni Komşu Eklendi: ${id.take(8)}... [$lat, $lng]")
        } else {
            // DebugLogger.log("ROUTING", "📍 Komşu Güncellendi: ${id.take(8)}...") // Çok spam yaparsa kapatabilirsin
        }
    }

    // GREEDY FORWARDING (Açgözlü İletim)
    fun getNextHop(targetLat: Double, targetLng: Double, myLat: Double, myLng: Double): String? {
        DebugLogger.log("ROUTING", "🧭 Rota Hesaplanıyor... Hedef: ($targetLat, $targetLng)")

        var bestNeighborId: String? = null
        var minDistance = calculateDistance(myLat, myLng, targetLat, targetLng) // Referans: Benim mesafem

        DebugLogger.log("ROUTING", "   📏 Benim Hedefe Uzaklığım: ${minDistance.toInt()}m")

        // Komşuları tara
        for ((id, neighbor) in neighbors) {
            val dist = calculateDistance(neighbor.lat, neighbor.lng, targetLat, targetLng)

            // Eğer komşu hedefe benden daha yakınsa
            if (dist < minDistance) {
                DebugLogger.log("ROUTING", "   ✅ Daha İyi Aday: ${id.take(8)}... (Mesafe: ${dist.toInt()}m)")
                minDistance = dist
                bestNeighborId = id
            } else {
                // DebugLogger.log("ROUTING", "   ❌ Aday Elendi: ${id.take(8)}... (Mesafe: ${dist.toInt()}m - Uzak)")
            }
        }

        // Sonuç Değerlendirmesi
        return if (bestNeighborId != null) {
            DebugLogger.log("ROUTING", "🚀 SEÇİLEN ROTA (Next Hop): $bestNeighborId")
            bestNeighborId
        } else {
            DebugLogger.log("ROUTING", "🛑 Yerel Maksimum (Local Maximum). Hedefe benden daha yakın kimse yok.")
            DebugLogger.log("ROUTING", "📥 Mesaj DTN havuzuna (Pending) atılmalı.")
            null
        }
    }

    // İki koordinat arası mesafe
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    fun getAllNeighbors(): List<String> = neighbors.keys.toList()
}