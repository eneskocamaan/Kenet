import requests
import xmltodict
from datetime import datetime
import logging
import database  # <-- DÜZELTME BURADA: Modülü import ediyoruz

logger = logging.getLogger("KANDILLI-SERVICE")

# Kandilli XML URL
KANDILLI_URL = "http://udim.koeri.boun.edu.tr/zeqmap/xmlt/son24saat.xml"

async def fetch_and_store_kandilli_data():
    """
    Arka planda çalışacak görev.
    Kandilli'den son depremleri çeker ve veritabanına yazar.
    """
    try:
        # 1. İstek At
        response = requests.get(KANDILLI_URL, timeout=10)

        if response.status_code != 200:
            logger.error(f"Kandilli verisi çekilemedi. Kod: {response.status_code}")
            return

        # 2. XML Parsing
        data = xmltodict.parse(response.content)

        # XML yapısı bazen tek deprem olunca liste değil dict döner
        raw_list = data.get('eqlist', {}).get('earhquake', [])
        if isinstance(raw_list, dict):
            raw_list = [raw_list]

        if not raw_list:
            return

        new_count = 0

        # 3. Veritabanı Bağlantısı Al
        # DÜZELTME BURADA: database.pool kullanıyoruz
        if database.pool is None:
            logger.error("Veritabanı havuzu henüz başlatılmamış!")
            return

        async with database.pool.acquire() as conn:
            # Listeyi tersten (eskiden yeniye) işle
            for eq in reversed(raw_list):
                try:
                    # XML'den verileri al
                    date_str = eq['@name']
                    loc_raw = eq['@lokasyon']
                    lat = float(eq['@lat'])
                    lng = float(eq['@lng'])
                    mag = float(eq['@mag'])
                    depth = float(eq['@Depth'])

                    title = loc_raw.strip()
                    occurred_at = datetime.strptime(date_str, "%Y.%m.%d %H:%M:%S")

                    # 4. Veritabanına Ekle
                    query = """
                        INSERT INTO confirmed_earthquakes
                        (external_id, title, magnitude, depth, latitude, longitude, occurred_at)
                        VALUES ($1, $2, $3, $4, $5, $6, $7)
                        ON CONFLICT (external_id) DO NOTHING
                        RETURNING id
                    """

                    result = await conn.fetchval(query, date_str, title, mag, depth, lat, lng, occurred_at)

                    if result:
                        new_count += 1

                except Exception as e:
                    continue

        if new_count > 0:
            logger.info(f"🌍 {new_count} yeni resmi deprem kaydedildi.")

    except Exception as e:
        logger.error(f"Kandilli Servis Genel Hatası: {e}")