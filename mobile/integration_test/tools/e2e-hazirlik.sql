-- Uçtan uca test hazırlığı: demo/ornek-veri.sql'den SONRA, yalnızca test veritabanında çalıştırılır.
-- Abone aracı 11CC1923'ü tevfik'in sahasına (Tevfik Bey - Bölüm 1) taşır; böylece saha testi abone
-- rengini ve "Aboneleri gizle"yi tevfik ile görebilir. Borçlu plaka 11KCK47 (ornek-veri) park hâlinde değildir.

DO $$
BEGIN
    IF current_database() <> 'bilpark_demo' THEN
        RAISE EXCEPTION 'Yalnizca test veritabaninda calisir. Su anki: %', current_database();
    END IF;
END $$;

UPDATE active_park_spots
   SET street = 'TEVFIK_BEY',
       zone_id = (SELECT id FROM zones WHERE street = 'TEVFIK_BEY' AND zone_number = 1),
       side = 'RIGHT'
 WHERE current_plate = '11CC1923';
