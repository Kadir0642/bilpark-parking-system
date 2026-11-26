-- Koşu sonrası veri doğruluğu (yalnızca okur). Her satır 0 olmalı (son satır bilgi amaçlı).
SELECT 'ayni plakada birden cok aktif arac' AS kontrol, count(*) AS sorun
  FROM (SELECT current_plate FROM active_park_spots GROUP BY current_plate HAVING count(*) > 1) x
UNION ALL
SELECT 'ayni checkInRequestId birden cok kez', count(*)
  FROM (SELECT request_id FROM check_in_requests GROUP BY request_id HAVING count(*) > 1) x
UNION ALL
SELECT 'istek kimligi var ama spot/kayit yok', count(*)
  FROM check_in_requests c
 WHERE NOT EXISTS (SELECT 1 FROM active_park_spots s WHERE s.id = c.park_spot_id)
   AND NOT EXISTS (SELECT 1 FROM parking_records r WHERE r.license_plate = c.plate AND r.entry_time >= c.created_at - interval '1 day')
UNION ALL
SELECT 'ayni plaka ayni anda aktif ve ayni giris saatli 2 kayit', count(*)
  FROM (SELECT license_plate, entry_time FROM parking_records WHERE device_id IS DISTINCT FROM 'yuk-seed'
         GROUP BY license_plate, entry_time HAVING count(*) > 1) x
UNION ALL
SELECT 'PAID kayitta ucret null/negatif', count(*) FROM parking_records WHERE status = 'PAID' AND (fee IS NULL OR fee < 0)
UNION ALL
SELECT 'cikis girişten once', count(*) FROM parking_records WHERE exit_time < entry_time
UNION ALL
SELECT 'bilgi: yuk kosusu kayit (PAID/RUNAWAY/CANCELLED)', count(*) FROM parking_records WHERE device_id IS DISTINCT FROM 'yuk-seed'
UNION ALL
SELECT 'bilgi: aktif arac', count(*) FROM active_park_spots
UNION ALL
SELECT 'bilgi: sync_events', count(*) FROM sync_events;
