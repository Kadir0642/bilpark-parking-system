# Geliştirici izleme (bakım dönemi)

Bu belge, 1 yıllık bakım döneminde sistem aksaklıklarını uzaktan fark etmek için geliştirici (`DEVELOPER`) rolüne eklenen **salt okunur** izleme yeteneklerini tanımlar. `docs/api-asama5.md` §9'a eklemedir.

İlkeler:

- Geliştirici **görür, değiştirmez**. Kayıt düzeltme, iptal, tarife, kullanıcı işlemleri yöneticide (ADMIN) kalır. Bu belgede yazma yapan yeni uç nokta yoktur.
- **KVKK, en az veri:** Geliştiriciye giden yanıtlarda plaka hiçbir zaman tam görünmez (bkz. "Plaka maskeleme"). Ad soyad gönderilmez; kullanıcı adı gönderilir. Excel/CSV dışa aktarma geliştiriciye açılmaz.
- **Görünürlük:** Geliştiricinin denetim kaydını görüntülemesi de denetim kaydına düşer; yönetici bunu kendi denetim ekranında görür.
- **Düşük trafik:** Panel bu uç noktaları yalnızca geliştirici sayfası açıkken ve sekme görünürken 60 saniyede bir çağırır.

## Plaka maskeleme

`maskPlate("34 ABC 123") → "34 *** *23"`. Kural: boşlukları atmadan, ilk 2 karakter ve son 2 karakter açık; aradaki harf ve rakamlar `*`. 4 karakterden kısa değerlerin tamamı `*`. Denetim kaydının `summary`, `before`, `after`, `entityId` alanlarında geçen plakalar da maskelenir (plaka biçimine uyan her parça: TR plaka düzenli ifadesi, `core/plate.dart` ile aynı kural).

## 1. `GET /api/developer/audit?from&to&action&actor&actorRole&entityType&q&page&size`

Yöneticinin `GET /api/admin/audit` uç noktasıyla aynı filtreler ve aynı sayfalı yanıt biçimi (`AuditQueryService` yeniden kullanılır), iki farkla:

- Plakalar maskelenir. `q` ile plaka araması yapılabilir (sunucu tarafında tam plakayla eşleşir, yanıt yine maskelidir).
- `entityId` filtresi yoktur.

`GET /api/developer/audit/actions`: yöneticideki gibi eylem listesi.

Denetim kaydı: yalnızca `page=0` isteklerinde bir kez `DEV_AUDIT_VIEWED` (summary: kullanılan filtreler). Sayfa çevirmek yeni kayıt üretmez.

## 2. `GET /api/developer/activity?hours=24`

`hours`: 1-168, varsayılan 24. Yanıt:

```json
{
  "generatedAt": "2026-11-05T10:15:00",
  "windowHours": 24,
  "workingHours": { "start": "08:00", "end": "22:00", "now": true },
  "zones": [
    {
      "zoneId": 1, "zoneName": "...", "street": "...",
      "activeVehicles": 7,
      "checkIns": 41, "checkOuts": 36, "runaways": 1, "cancellations": 2,
      "lastActivityAt": "2026-11-05T10:02:11",
      "openShift": { "username": "memur1", "startedAt": "2026-11-05T08:01:00", "deviceId": "..." },
      "officers": [
        { "username": "memur1", "lastSeenAt": "2026-11-05T10:14:30", "appVersion": "1.0.0+1", "device": "SM-M127F / Android 13", "liveConnected": true }
      ]
    }
  ],
  "sync": {
    "liveConnections": 5,
    "offlineCheckIns": 3,
    "maxOfflineDelaySeconds": 640
  },
  "logins": { "success": 12, "failed": 2 },
  "errors": {
    "last24h": 4,
    "byDay": [ { "date": "2026-10-30", "count": 0 } ],
    "topCodes": [ { "code": "INTERNAL_ERROR", "path": "/api/parking/check-out", "count": 3 } ]
  },
  "versions": { "latest": "1.0.1+2", "outdatedUsers": ["memur3"] },
  "alerts": [
    { "level": "WARN", "code": "DEVICE_OFFLINE", "message": "memur2 (Saha 3) 22 dakikadır görülmedi, mesai açık.", "zoneId": 3, "username": "memur2" }
  ]
}
```

Alan açıklamaları:

- `checkIns` … `cancellations`: pencere içindeki denetim kaydı eylemlerinden (`VEHICLE_CHECK_IN`, `VEHICLE_CHECK_OUT`, `VEHICLE_RUNAWAY`, `SPOT_CANCELLED` + `RECORD_CANCELLED`) saha bazında sayılır.
- `lastActivityAt`: o sahadaki son giriş/çıkış/kaçak/iptal anı (pencere dışı olabilir; hiç yoksa `null`).
- `openShift`: o sahada `endTime` boş en son mesai; yoksa `null`.
- `officers`: o sahaya atanmış aktif OFFICER'lar. `lastSeenAt`, `appVersion`, `device` bölüm 3'teki izden gelir; hiç görülmediyse `null`. `liveConnected`: o anda açık SSE bağlantısı var mı.
- `sync.liveConnections`: açık SSE bağlantısı sayısı. `offlineCheckIns`: pencere içinde `clientEntryTime` ile sunucuya ulaşma anı arasında 60 saniyeden fazla fark olan girişler. `maxOfflineDelaySeconds`: bunların en büyüğü (yoksa 0).
- `logins`: pencere içindeki `LOGIN` ve `LOGIN_FAILED` sayıları.
- `errors.byDay`: son 7 gün, bugün dahil, eksik günler 0 ile. `topCodes`: pencere içinde en sık 5 (errorCode + path).
- `versions.latest`: aktif kullanıcıların bildirdiği en yüksek `appVersion` (semver + build karşılaştırması). `outdatedUsers`: daha eski sürüm bildiren aktif OFFICER'lar.
- Yanıtta plaka ve ad soyad yoktur.

Denetim kaydı: bu uç nokta kayıt **üretmez** (60 sn'de bir çağrılır; kaydı şişirmemek için).

### Uyarılar (`alerts`)

Seviye `CRIT` > `WARN` > `INFO`, liste bu sırayla döner. Kurallar (eşikler sabit, sınıfta tek yerde tanımlı):

| code | seviye | koşul |
|---|---|---|
| `DB_SLOW` | WARN | veritabanı gecikmesi > 500 ms |
| `DB_DOWN` | CRIT | veritabanına ulaşılamıyor |
| `ERROR_SPIKE` | CRIT | son 1 saatte ≥ 10 hata |
| `ERRORS_PRESENT` | WARN | son 24 saatte ≥ 1 hata (spike yoksa) |
| `SHIFT_NOT_STARTED` | WARN | ücretli saatlerde, başlangıçtan 30 dk sonra, sahada açık mesai yok |
| `DEVICE_OFFLINE` | WARN | mesai açık ama memur 15 dk'dır görülmedi |
| `ZONE_IDLE` | INFO | ücretli saatlerde mesai açık, sahada 90 dk'dır hareket yok |
| `OFFLINE_DELAY` | WARN | pencere içinde 10 dk'dan uzun gecikmeli çevrimdışı giriş var |
| `FAILED_LOGINS` | WARN | son 1 saatte aynı kullanıcı adına ≥ 5 başarısız giriş |
| `OUTDATED_APP` | INFO | eski sürüm kullanan memur var |
| `LICENSE_SOON` | WARN | lisansın bitmesine ≤ 30 gün (okunur modda CRIT) |
| `RETENTION_STALE` | WARN | saklama işi 48 saattir çalışmadı |

Ücretli saatler ayarlardan (`paidStart`/`paidEnd` ya da projedeki eşdeğeri) okunur.

## 3. Cihaz izi (son görülme, sürüm, cihaz)

- Mobil uygulama her isteğe iki başlık ekler: `X-App-Version: 1.0.0+1` (pubspec sürümü) ve `X-Device: <model> / <işletim sistemi sürümü>` (en fazla 80 karakter).
- Backend, kimliği doğrulanmış her istekte kullanıcının `last_seen_at`, `last_app_version`, `last_device` alanlarını günceller; **aynı kullanıcı için en fazla 2 dakikada bir** veritabanına yazar (bellekte son yazma zamanı tutulur). Başlık yoksa (web paneli) yalnızca `last_seen_at` güncellenir.
- Flyway `V8__developer_monitoring.sql`: `users` tablosuna bu üç kolon (boş olabilir) eklenir.
- Bu alanlar `GET /api/admin/users` yanıtına da eklenir (`lastSeenAt`, `appVersion`, `device`); yönetici de personelin uygulamasının güncel olup olmadığını görebilir.

## 4. `GET /api/developer/health` eki

Mevcut yanıta `appVersion` (backend derleme sürümü/commit, bilinmiyorsa `null`) ve `startedAt` eklenir.

## 5. Panel (geliştirici sayfası)

Sayfa sırası: Uyarılar → Sahalar ve cihazlar → Senkron ve girişler → Hata eğilimi (7 gün, basit çubuk) → Sistem sağlığı → Son hatalar → Denetim kaydı (salt okunur, filtre + sayfalama, plakalar maskeli) → Lisans → Yönetici şifresi sıfırla.
