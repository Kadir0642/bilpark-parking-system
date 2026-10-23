# BilPark API — Aşama 3 sözleşmesi

Bu belge mobil uygulamanın ve web panelinin Aşama 3 backend'ine bağlanması için yazıldı.
Aşama 1-2'deki tüm uç noktalar ve alan adları aynen çalışmaya devam ediyor; buradaki her şey **ekleme**.

Genel kurallar (değişmedi):

- Kimlik: `Authorization: Bearer <jwt>` (`POST /api/auth/login` ile alınır). Cihaz: `X-Device-Id`.
- Hata gövdesi her yerde `{"error": "<Türkçe mesaj>", "code": "<KOD>"}`.
- Tarih-saatler Europe/Istanbul yerel saatidir, ISO biçiminde ve saat dilimi eki olmadan gelir (`2026-10-23T14:05:00`). İstisna: `/api/health` içindeki `time` UTC `Instant`'tır.
- Para alanları TL cinsinden `number` (ör. `25.0`).

Yeni hata kodları: `INVALID_TARIFF` (400), `TARIFF_STATE` (409), `PASSWORD_MISMATCH` (403).

---

## 1. Sağlık kontrolü

### `GET /api/health` (herkese açık, `HEAD` de çalışır)

Veritabanına hiç dokunmaz. UptimeRobot veya Render keep-alive isteği **bu adrese** yönlendirilmeli, `/api/parking/spots` adresine değil.

```json
{ "status": "UP", "time": "2026-10-23T11:05:00.123Z" }
```

Bağlantı havuzu boşta bağlantı tutmuyor (`minimumIdle=0`, 30 sn boşta kalınca kapanır, en fazla 5 bağlantı). Bu sayede Neon, istek olmadığında uykuya geçebiliyor.

---

## 2. Caddeler ve bölümler

### `GET /api/streets` (herkese açık)

Caddeler `sortOrder`'a, bölümler `zoneNumber`'a göre sıralı gelir. `code` eski enum adıdır ve `street` parametresi olarak kullanılmaya devam eder (`TEVFIK_BEY`, `ALI_RIZA_OZKAY`, `CUMHURIYET`).

```json
[
  {
    "id": 1, "code": "TEVFIK_BEY", "displayName": "Tevfik Bey Caddesi", "sortOrder": 1,
    "zones": [
      { "id": 1, "zoneNumber": 1, "zoneName": "Tevfik Bey - Bölüm 1",
        "leftLandmark": "Öncü Döner", "rightLandmark": "Ziraat Bankası" }
    ]
  }
]
```

`/api/zones` ve `/api/zones/by-street` değişmedi.

---

## 3. Tarife

### Ücret kuralları (motor)

- Ücretli saatler tarifeden gelir (varsayılan her gün 08:00-22:00). Bu aralığın dışı ücretsizdir; sayaç ertesi sabah kaldığı yerden devam eder.
- Girişten çıkışa kadar ücretli aralıktaki dakikalar gün gün toplanır, tarife **bir kez** uygulanır:
  - ücretli dakika ≤ `freeMinutes` → 0
  - ≤ 60 → `firstHour`
  - aksi halde `firstHour + ceil((ücretli dakika − 60) / 60) × extraHour`
- `LARGE` araçlar `large`, diğerleri `small` fiyatlarıyla hesaplanır.
- Kaçak borcu da aynı motorla, girişten "Kaçtı" işaretine kadar hesaplanır.
- Hesap anında ACTIVE olan tarife uygulanır. Çıkış ve kaçak kayıtları kullanılan sürümü `tariffId` alanında, ücretli dakikayı `paidMinutes` alanında saklar. Aşama 3 öncesi kayıtlarda bu iki alan `null`'dır.
- `paidEnd = "00:00"` gece yarısı demektir. `00:00-00:00` tüm günün ücretli olduğu anlamına gelir.

### Gün gün döküm (`segments`)

Kalış süresi gece yarısında ve ücretli aralığın sınırlarında bölünür. Her parça ya tamamen ücretli ya tamamen ücretsizdir ve zaman sırasıyla gelir.

```json
"segments": [
  { "date": "2026-10-22", "start": "2026-10-22T20:00:00", "end": "2026-10-22T22:00:00", "paid": true,  "minutes": 120 },
  { "date": "2026-10-22", "start": "2026-10-22T22:00:00", "end": "2026-10-23T00:00:00", "paid": false, "minutes": 120 },
  { "date": "2026-10-23", "start": "2026-10-23T00:00:00", "end": "2026-10-23T08:00:00", "paid": false, "minutes": 480 },
  { "date": "2026-10-23", "start": "2026-10-23T08:00:00", "end": "2026-10-23T10:00:00", "paid": true,  "minutes": 120 }
]
```

### `GET /api/tariff/current` (herkese açık)

Bu yanıt, `TARIFF_UPDATED` olayının `payload` alanıyla aynı biçimdedir. `upcoming` zamanlanmış bir tarife varsa aynı biçimde dolu gelir, yoksa `null` olur.

```json
{
  "id": 1, "status": "ACTIVE", "effectiveFrom": "2026-10-23T12:00:00",
  "small": { "firstHour": 25.0, "extraHour": 15.0 },
  "large": { "firstHour": 50.0, "extraHour": 30.0 },
  "freeMinutes": 5, "paidStart": "08:00", "paidEnd": "22:00",
  "subscription": { "small": 2500.0, "large": 3000.0 },
  "upcoming": null
}
```

Yeni abonelikler aylık ücreti o anki tarifeden alır.

### Yönetici uç noktaları (`/api/admin/**`, yalnızca ADMIN)

Akış: **taslak → önizleme → zamanla (şifre tekrar) → yürürlük anında otomatik ACTIVE**.
Durumlar şunlardır: `DRAFT`, `SCHEDULED`, `ACTIVE`, `ARCHIVED`. Aynı anda en fazla bir ACTIVE ve bir SCHEDULED tarife olabilir.

| Uç nokta | Gövde | Yanıt |
|---|---|---|
| `GET /api/admin/tariffs` | — | Yönetici biçiminde liste (yeniden eskiye) |
| `POST /api/admin/tariffs` | TariffRequest | Yeni `DRAFT` |
| `PUT /api/admin/tariffs/{id}` | TariffRequest | Güncellenen taslak (yalnızca `DRAFT`, değilse 409 `TARIFF_STATE`) |
| `POST /api/admin/tariffs/{id}/preview` | — | Önizleme (aşağıda) |
| `POST /api/admin/tariffs/{id}/schedule` | `{"effectiveFrom": "2026-11-01T00:00:00", "password": "..."}` | `SCHEDULED` tarife |
| `POST /api/admin/tariffs/{id}/cancel` | — | `ARCHIVED` (yalnızca `DRAFT` veya `SCHEDULED`) |

TariffRequest:

```json
{
  "smallFirstHour": 30, "smallExtraHour": 20,
  "largeFirstHour": 60, "largeExtraHour": 35,
  "freeMinutes": 5, "paidStart": "08:00", "paidEnd": "22:00",
  "subscriptionSmallMonthly": 2750, "subscriptionLargeMonthly": 3300,
  "note": "2027 tarifesi"
}
```

Doğrulama kuralları ihlal edilirse 400 `INVALID_TARIFF` döner:

- İlk saat ücretleri 0'dan büyük olmalı.
- Ek saat ücretleri 0 veya daha büyük olmalı.
- Abonelik fiyatları 0'dan büyük olmalı.
- `freeMinutes` 0 ile 120 arasında olmalı.
- `paidStart` `paidEnd`'den önce olmalı. Tek istisna: `paidEnd` `"00:00"` ise gece yarısı sayılır.

Yönetici biçimi, herkese açık biçime şu alanları ekler: `note`, `createdByUsername`, `createdAt`, `activatedAt`, `archivedAt`.

Zamanlama hataları:

- Şifre yanlışsa 403 `PASSWORD_MISMATCH`. Oturumu kapatmayın; bu bir yetki kaybı değil, yalnızca şifre doğrulaması başarısız oldu.
- Geçmiş bir tarih verilirse 400 `INVALID_TARIFF`.
- Tarife taslak değilse veya zaten zamanlanmış bir tarife varsa 409 `TARIFF_STATE`. İkinci durumda önce mevcut zamanlamayı iptal edin.

Önizleme yanıtı ACTIVE tarifeyi önerilen tarifeyle karşılaştırır. Örnekler her iki araç tipi için hazırlanır: yarın 10:00'da başlayan 5, 6, 30, 60, 61, 90, 120, 180 ve 300 dakikalık kalışlar, ayrıca 20:00'den ertesi gün 10:00'a bir gece kalışı. Toplam 20 satır döner.

```json
{
  "current":  { "...": "herkese açık tarife biçimi" },
  "proposed": { "...": "herkese açık tarife biçimi" },
  "rows": [
    { "label": "61 dakika", "vehicleType": "SMALL",
      "entry": "2026-10-24T10:00:00", "exit": "2026-10-24T11:01:00",
      "current":  { "fee": 40.0, "paidMinutes": 61 },
      "proposed": { "fee": 50.0, "paidMinutes": 61, "segments": [ ] } }
  ]
}
```

Zamanlama ve yürürlük olayları `TARIFF_UPDATED` ile tüm istemcilere anında gider. Yürürlük, tek seferlik bir zamanlayıcıyla tam `effectiveFrom` anında gerçekleşir. Sunucu o sırada uyuyorduysa uyanınca ilk istekte gerçekleşir. Veritabanı periyodik olarak yoklanmaz.

---

## 4. Ücret ve borç uç noktaları

### `GET /api/parking/debt?plate=34ABC123` (herkese açık, vatandaş sayfası)

Eski alanlar aynen duruyor: `streetName`, `plate`, `entryTime`, `durationMinutes`, `fee`, `hasSubscription`. Yeni alanlar eklendi:

```json
{
  "streetName": "Tevfik Bey Caddesi", "plate": "34ABC123",
  "entryTime": "2026-10-22T20:00:00", "durationMinutes": 840,
  "fee": 70.0, "hasSubscription": false,
  "paidMinutes": 240, "freeMinutes": 600, "tariffId": 1,
  "segments": [ "...yukarıdaki biçim..." ]
}
```

Abonelerde `fee` 0 olur. Döküm yine dolu gelir.

### `GET /api/parking/fee-preview?plate=34ABC123` (oturum gerekli, memur ve admin)

Memurun ödeme ekranı için kullanılır. Gövde `/debt` ile aynıdır, ayrıca `vehicleType` ve `zoneId` içerir. Memur yalnızca kendi bölümündeki aracı sorgulayabilir, başka bölüm için 403 döner. Araç yoksa 404 `VEHICLE_NOT_FOUND` döner.

### Mevcut uç noktalardaki eklemeler

- `GET /api/parking/spots` ve `GET /api/parking/filter?street=` → her araçta `hasSubscription` (boolean) ve `accumulatedDebt` (ödenmemiş kaçak borç toplamı, TL; borç yoksa `0.0`) artık dolu gelir. Önceden yalnızca check-in yanıtında doluydu, listelerde `null` dönüyordu. Tüm liste için toplam iki sorgu çalışır, araç başına sorgu atılmaz. Abone araçları farklı renkte göstermek ve borçlu aracı işaretlemek için bu alanlar kullanılabilir.
- `POST /api/parking/check-out` → `ParkingRecord` yanıtına `tariffId` ve `paidMinutes` eklendi.
- `POST /api/parking/runaway` → `{message, debt, record}` yanıtına `segments` eklendi. `record` içinde de `tariffId` ve `paidMinutes` var.

---

## 5. Anlık senkron (SSE)

Mevcut 6 saniyelik yoklamanın yerini alır. Ağ trafiği yalnızca olay olduğunda ve 25 saniyede bir küçük canlılık sinyaliyle oluşur.

### Önerilen istemci akışı

1. `GET /api/sync` (since olmadan) çağrılır, dönen `version` saklanır.
2. Ekran verisi yüklenir (`/api/parking/spots`, `/api/tariff/current` ...).
3. Akış açılır: `GET /api/events?since=<version>`. Akış, aradaki olayları önce tekrar gönderir.
4. Her olayda `version` saklanır. Bağlantı koparsa üstel bekleme ile (1 sn → 2 → 4 … en fazla 30 sn) yeniden bağlanılır. Bağlanırken `since=<son version>` veya `Last-Event-ID` gönderilir.
5. 60 saniye boyunca hiçbir kare (HEARTBEAT dahil) gelmezse bağlantı ölü sayılır ve yeniden açılır.
6. `RESET` olayı veya `resetRequired: true` gelirse 2. adımdaki veri tamamen yeniden yüklenir.

### Bağlanma

- **Mobil:** `GET /api/events` isteği `Authorization: Bearer <jwt>` başlığıyla gönderilir. `Accept: text/event-stream` kullanılmalı.
- **Tarayıcı:** `EventSource` başlık gönderemez, bu yüzden önce kısa ömürlü bir bilet alınır:
  - `POST /api/events/ticket` (Bearer ile) → `{"ticket": "<43 karakter>", "expiresInSeconds": 60}`
  - `new EventSource(API + "/api/events?ticket=" + ticket + "&since=" + version)`
  - Bilet **tek kullanımlıktır** ve 60 sn geçerlidir. Her yeniden bağlanmada yeni bilet alınır. Bu yüzden `EventSource`'un kendi otomatik yeniden bağlanması yerine `onerror`'da akışı kapatıp yeni biletle açın.
- Kimliksiz, süresi dolmuş ya da kullanılmış bilet ile gelen istek 401 alır. Akış 30 dakika sonra sunucu tarafından kapatılır. İstemci `since` ile yeniden bağlanır, olay kaybı olmaz.

### Kare biçimi

```
event: HELLO
data: {"version":42,"userId":2,"role":"OFFICER","zoneId":1}

id: 43
event: VEHICLE_ENTERED
data: {"version":43,"type":"VEHICLE_ENTERED","scope":"ZONE","zoneId":1,"street":"TEVFIK_BEY","at":"2026-10-23T14:05:00","payload":{...}}

event: HEARTBEAT
data: {"version":43,"at":"2026-10-23T14:05:25"}

event: RESET
data: {"version":43}
```

- `HELLO`: bağlanınca ilk kare.
- `HEARTBEAT`: yaklaşık 25 sn'de bir gelir, veritabanına dokunmaz. İçindeki `version` mevcut sürümden büyükse ve olay gelmemişse `/api/sync?since=` ile eksikler alınabilir.
- `RESET`: istenen sürümden itibaren tekrar oynatılamıyor (olaylar 7 günden eski veya 500'den fazla). Veri tamamen yeniden yüklenmeli.

### Olay şeması

Ortak zarf (`/api/sync` yanıtında da aynısı):

| Alan | Açıklama |
|---|---|
| `version` | Artan sürüm numarası (`since` için) |
| `type` | `VEHICLE_ENTERED`, `VEHICLE_EXITED`, `VEHICLE_RUNAWAY`, `TARIFF_UPDATED`, `USER_DEACTIVATED` |
| `scope` | `ZONE`, `GLOBAL`, `USER` |
| `zoneId`, `street` | `ZONE` olaylarında dolu |
| `targetUserId` | Yalnızca `USER` olaylarında bulunur |
| `at` | Olay zamanı |
| `payload` | Türe göre içerik (aşağıda) |

| `type` | `payload` |
|---|---|
| `VEHICLE_ENTERED` | `{plate, vehicleType, zoneId, street, side, entryTime, hasSubscription, spotId}` |
| `VEHICLE_EXITED` | `{plate, recordId, zoneId, street, fee, paymentMethod, exitTime}` |
| `VEHICLE_RUNAWAY` | `{plate, recordId, zoneId, street, debt, exitTime}` |
| `TARIFF_UPDATED` | `GET /api/tariff/current` yanıtının aynısı (`upcoming` dahil) |
| `USER_DEACTIVATED` | `{userId, username}`. Kullanıcının kendisine gönderilir, ardından akışı kapatılır ve token'ı artık 401 alır. İstemci oturumu kapatıp giriş ekranına dönmeli. |

Kim neyi görür:

- **ADMIN** tüm olayları görür.
- **Memur** şunları görür: `GLOBAL` olaylar, kendisine ait `USER` olayları ve kendi bölümünün `ZONE` olayları. Bölümsüz eski kayıtlarda cadde eşleşmesi kullanılır.
- Bölümü atanmamış memur yalnızca `GLOBAL` ve `USER` olaylarını görür.

### `GET /api/sync?since=<version>` (oturum gerekli)

Bağlantı koptuğunda yalnızca değişenleri döner. Olaylar eskiden yeniye sıralıdır ve bir yanıtta en fazla 500 olay gelir. `hasMore: true` ise son olayın `version` değeriyle tekrar çağrılır.

```json
{ "version": 57, "events": [ { "version": 50, "type": "VEHICLE_EXITED", "...": "..." } ],
  "hasMore": false, "resetRequired": false }
```

- `since` verilmezse `{"version": <son>, "events": [], "hasMore": false, "resetRequired": false}` döner.
- `resetRequired: true` iki durumda gelir: `since` saklanan en eski olaydan daha eskiyse (olaylar 7 gün saklanır) veya sunucudaki son sürümden büyükse (veritabanı sıfırlanmış). Bu durumda `events` boştur ve veri tamamen yeniden yüklenmelidir.
- Dönen `version` görünür olaylardan bağımsız olarak genel son sürümdür. İstemci bir sonraki çağrıda bu değeri kullanır.

---

## 6. Kullanıcı yönetimi (pasife alma)

| Uç nokta | Gövde | Yanıt |
|---|---|---|
| `GET /api/admin/users` | — | `[{id, username, role, active, zoneId, zoneName, lastLoginTime}]` |
| `PUT /api/admin/users/{id}/active` | `{"active": false}` | Güncellenen kullanıcı (aynı biçim) |

Pasife alınan kullanıcı için şunlar olur:

- Açık vardiyaları kapanır.
- Kendisine `USER_DEACTIVATED` olayı gider ve SSE akışları kapanır.
- Mevcut token'ı 401 alır, yeniden giriş denemesi de 401 döner.

Yönetici kendini pasife alamaz (400). Tekrar aktifleştirme için `{"active": true}` gönderilir.

---

## 7. İstemci yapılacaklar listesi (sonraki oturum)

- **Mobil:**
  - 6 sn'lik yoklamayı kaldırın, SSE'ye geçin (Bearer).
  - Ödeme ekranında `fee-preview` dökümünü (`segments`) gösterin.
  - `USER_DEACTIVATED` gelince oturumu kapatın.
  - Tarifeyi `/api/tariff/current` ve `TARIFF_UPDATED` ile güncel tutun.
- **Panel:**
  - SSE'ye ticket ile bağlanın.
  - Tarife ekranını ekleyin: taslak, önizleme tablosu, şifreli zamanlama ve iptal.
  - Kullanıcı aktif/pasif anahtarını ekleyin.
  - Cadde listesini `/api/streets`'ten alın.
- **Vatandaş sayfası:** `/debt` yanıtındaki `segments` ile gün gün dökümü gösterin, tarifeyi `/api/tariff/current`'ten okuyun.
- **Operasyon:** UptimeRobot monitörü `HEAD /api/health` olarak değiştirilmeli.
