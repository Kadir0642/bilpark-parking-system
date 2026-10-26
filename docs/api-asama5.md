# BilPark API — Aşama 5 sözleşmesi

Bu belge, yönetim panelinin ve saha uygulamasının Aşama 5 backend'ine bağlanması için yazıldı. Aşama 1-3'teki uç noktalar ve alan adları aynen çalışıyor. Buradaki her şey **ekleme**. Tek istisna, yeni `DEVELOPER` rolünün operasyonel uç noktalara erişememesidir.

Genel kurallar (değişmedi):

- Kimlik: `Authorization: Bearer <jwt>`, cihaz: `X-Device-Id`.
- Hata gövdesi: `{"error": "<Türkçe mesaj>", "code": "<KOD>"}`.
- Tarih-saat: Europe/Istanbul yerel saati, ISO biçiminde ve saat dilimi eki olmadan (`2026-11-01T08:03:00`).
- Para alanları TL cinsinden `number`.

Ek kurallar:

- **Tarih aralığı:** `from` ve `to` parametreleri `yyyy-MM-dd` biçimindedir ve **her iki gün de dahildir**. Ay parametresi `month=yyyy-MM` biçimindedir. Raporlarda aralık en fazla 366 gündür.
- **Sayfalı yanıt:** `{"content":[...], "page":0, "size":50, "totalElements":123, "totalPages":3}`. `page` 0'dan başlar, `size` varsayılan 50, en fazla 200.
- **İndirme:** `format=xlsx` (varsayılan) veya `format=csv`.
  - CSV: UTF-8 BOM, `;` ayırıcı, ondalık virgül (`1234,50`), tarih `dd.MM.yyyy HH:mm`. Türkçe Excel doğrudan açar.
  - Dosya adı `Content-Disposition` başlığında gelir. İstemci `fetch` ile alıp `blob` olarak kaydetmelidir; token başlıkta gittiği için düz link çalışmaz.

Yeni hata kodları:

| Kod | HTTP | Ne zaman |
|---|---|---|
| `READ_ONLY` | 423 | Lisans ve ek süre doldu; yazma işlemi kapalı |
| `WEAK_PASSWORD` | 400 | Şifre kurallara uymuyor |
| `PASSWORD_MISMATCH` | 400 | `/api/me/password`: mevcut şifre hatalı |
| `USERNAME_TAKEN` | 409 | Kullanıcı adı kullanımda |
| `PROTECTED_ACCOUNT` | 400/403 | Bu hesap bu uç noktayla değiştirilemez (ör. geliştirici hesabı) |
| `ALREADY_CANCELLED` | 409 | Kayıt zaten iptal edilmiş |
| `INVALID_SETTINGS` | 400 | Ayar değeri geçersiz (ör. ağırlık toplamı 100 değil) |

---

## 0. Rol yetki tablosu

| Uç nokta grubu | Herkes | OFFICER | ADMIN | DEVELOPER |
|---|---|---|---|---|
| `/api/auth/login`, `/api/health`, `/api/streets`, `/api/zones`, `/api/tariff/current`, `/api/parking/debt` | ✓ | ✓ | ✓ | ✓ |
| `/api/auth/logout`, `GET /api/me`, `POST /api/me/password`, `GET /api/license/status` | | ✓ | ✓ | ✓ |
| Araç işlemleri `/api/parking/check-in`, `check-out`, `runaway`, `spots`, `fee-preview`, `spots/cancel` (yalnız kendi sahası) | | ✓ | ✓ | ✗ |
| SSE ve senkron `/api/events`, `/api/sync`; abonelik sorgu `/api/subscription/check` | | ✓ | ✓ | ✗ |
| `/api/admin/**`: kullanıcılar, mesai, kayıt iptali, iptal listesi, denetim kaydı, raporlar, ayarlar, tarifeler; `/api/parking/bi/**`, `/api/parking/income/**` | | ✗ | ✓ | ✗ |
| `/api/developer/**`: lisans, sağlık, hatalar, yönetici şifresi | | ✗ | ✗ | ✓ |

Geliştirici rolünün varlığı ve yetkileri `docs/sozlesme-eki-gelistirici-ve-lisans.md` belgesinde yazılıdır. Her geliştirici işlemi denetim kaydına düşer ve yönetici bu kayıtları görür. Geliştirici hesabı, sunucuda `DEVELOPER_PASSWORD` ortam değişkeni ayarlıysa açılışta bir kez oluşturulur. Varsayılan kullanıcı adı `gelistirici`, `DEVELOPER_USERNAME` ile değiştirilebilir.

---

## 1. Lisans ve okunur mod

### `GET /api/license/status` (giriş yapmış her rol)

```json
{
  "licensee": "BilPark Müşterisi",
  "expiresOn": "2027-11-01",
  "graceDays": 15,
  "graceEndsOn": "2027-11-16",
  "daysLeft": 6,
  "graceDaysLeft": 21,
  "state": "ACTIVE",
  "warningLevel": 7,
  "readOnly": false,
  "message": "Lisansın bitmesine 6 gün kaldı. Süre dolunca 15 günlük ek süre tanınır, sonra sistem okunur moda geçer.",
  "updatedAt": "2026-11-01T10:12:00",
  "updatedBy": "gelistirici",
  "note": "2027 bakım sözleşmesi"
}
```

- `state`: `ACTIVE` (bugün ≤ `expiresOn`), `GRACE` (ek süre içinde, yazma açık), `READ_ONLY`.
- `warningLevel`: `ACTIVE` iken kalan gün ≤30 ise `30`, ≤7 ise `7`, ≤1 ise `1`, aksi halde `null`. `GRACE` ve `READ_ONLY` durumunda `0`.
- Panel: `warningLevel != null` ise üstte `message` bandı gösterilmeli. 30 sarı, 7 ve 1 turuncu, 0 kırmızı olabilir.
- SSE: lisans değişince herkese `LICENSE_UPDATED` olayı gider (`{expiresOn, graceDays, state}`). İstemci bu olayda durumu yeniden çekmeli.

### Okunur mod

`READ_ONLY` durumunda `/api/**` altındaki **her yazma isteği** (POST, PUT, PATCH, DELETE) şu yanıtı alır:

```json
HTTP 423
{ "error": "Lisans süresi ve ek süre doldu; sistem okunur modda. Kayıtları görüntüleyebilir ve Excel alabilirsiniz, ancak giriş, çıkış, kaçak, tarife ve kullanıcı değişikliği yapılamaz. Lisansı yenilemek için sistem sağlayıcınızla görüşün.", "code": "READ_ONLY" }
```

Okunur modda açık kalanlar: tüm GET istekleri (listeler, raporlar, Excel/CSV), `/api/auth/**` (giriş ve çıkış), `/api/health`, `/api/me/password`, `/api/events/**` (SSE bileti) ve `/api/developer/**` (lisans yenileme). 401 ve 403 yanıtları 423'ten önce gelir. Veri hiçbir durumda silinmez.

Saha uygulaması 423 aldığında işlemi yerel kuyrukta tekrar denememeli. Kullanıcıya mesajı göstermeli.

---

## 2. Kendi hesabım

### `GET /api/me`

```json
{ "id": 1, "username": "admin", "fullName": "Firma Yöneticisi", "role": "ADMIN",
  "zoneId": null, "zoneName": null, "passwordChangedAt": "2026-11-01T10:20:11" }
```

### `POST /api/me/password`

```json
{ "currentPassword": "eski", "newPassword": "Yeni-sifre-2026" }
```

→ `200 {"message": "Şifre değiştirildi.", "token": "<yeni JWT>"}`

- Şifre kuralları: en az 8 karakter, en az bir harf ve bir rakam, kullanıcı adını içermez, mevcut şifreyle aynı olmaz, en az 4 farklı karakter içerir, en fazla 72 bayt. Kurala uymazsa `400 WEAK_PASSWORD`, mevcut şifre yanlışsa `400 PASSWORD_MISMATCH`.
- Şifre değişince **bu kullanıcının tüm eski token'ları geçersiz olur**, diğer cihazlar dahil. İstemci yanıttaki `token`'ı saklamalı. Diğer cihazlar sonraki istekte 401 alır ve girişe döner.
- Denetim kaydına `PASSWORD_CHANGED` olarak düşer. Okunur modda da çalışır.

---

## 3. Kullanıcı yönetimi (ADMIN)

Kullanıcı nesnesi (eski alanlar korunuyor, yenileri: `fullName`, `createdAt`, `streetCode`):

```json
{ "id": 7, "username": "ayse.k", "role": "OFFICER", "active": true, "zoneId": 3,
  "zoneName": "Tevfik Bey - Bölüm 3", "lastLoginTime": null,
  "fullName": "Ayşe Kaya", "createdAt": "2026-11-02T09:00:00", "streetCode": "TEVFIK_BEY" }
```

| İstek | Gövde | Not |
|---|---|---|
| `GET /api/admin/users` | | Geliştirici hesabı da listelenir (`role: DEVELOPER`), ama değiştirilemez |
| `POST /api/admin/users` | `{"username","fullName","password","role":"OFFICER"\|"ADMIN","zoneId"}` | `201` döner. Kullanıcı adı küçük harfe çevrilir, 3-50 karakter, `[a-z0-9._-]`. OFFICER için `zoneId` zorunlu (yoksa 400, bilinmiyorsa 404). ADMIN için gönderilen `zoneId` yok sayılır. Kullanıcı adı alınmışsa `409 USERNAME_TAKEN`. `DEVELOPER` rolü verilemez. |
| `PUT /api/admin/users/{id}` | `{"fullName"?, "role"?, "zoneId"?}` | Kısmi güncelleme: yalnızca gövdedeki alanlar değişir; `"zoneId": null` sahayı boşaltır. Yönetici kendi rolünü değiştiremez. ADMIN'e çevrilen kullanıcının sahası boşaltılır. OFFICER'lıktan çıkarılan kullanıcının açık vardiyaları kapanır. |
| `PUT /api/admin/users/{id}/zone` | `{"zoneId": 3}` | Saha atama, yalnızca OFFICER |
| `PUT /api/admin/users/{id}/active` | `{"active": false}` | Pasife alma (Aşama 3'ten beri var). Açık vardiyaları kapatır, SSE'de `USER_DEACTIVATED` gönderir ve kullanıcının akışlarını kapatır. |
| `POST /api/admin/users/{id}/reset-password` | `{"newPassword"}` | Yalnızca OFFICER hesapları; diğerlerinde `400 PROTECTED_ACCOUNT`. Yanıt: kullanıcı nesnesi + `message`. Hedefin eski token'ları geçersiz olur ve SSE akışları kapanır. |

- Geliştirici hesabını değiştiren her istek `403 PROTECTED_ACCOUNT` alır.
- Rol veya saha değişince hedef kullanıcıya SSE `USER_UPDATED` olayı gider (`{userId, username, zoneId, role}`). Saha uygulaması bu olayda kullanıcı bilgisini (`GET /api/me`) ve sahasını yeniden yüklemeli. Açık vardiya eski sahasında kalır; bir sonraki girişte yeni sahada vardiya açılır.
- `USER_DEACTIVATED` olayını alan saha uygulaması oturumu kapatıp giriş ekranına dönmeli.
- Her işlem denetim kaydına düşer: `USER_CREATED`, `USER_UPDATED`, `USER_ZONE_ASSIGNED`, `USER_DEACTIVATED`/`USER_ACTIVATED`, `USER_PASSWORD_RESET`. Şifreler kayda yazılmaz.

---

## 4. Mesai listesi (ADMIN)

### `GET /api/admin/shifts?from=2026-10-01&to=2026-10-31&zoneId=3&userId=7&page=0&size=50`

`from` ve `to` varsayılan olarak son 7 gündür. Filtre vardiyanın başlangıç zamanına uygulanır. Sıralama en yeni başlangıç önce.

```json
{ "content": [
    { "id": 120, "userId": 7, "username": "ayse.k", "fullName": "Ayşe Kaya", "zoneId": 3,
      "zoneName": "Tevfik Bey - Bölüm 3", "street": "TEVFIK_BEY", "deviceId": "a1b2c3",
      "startTime": "2026-10-30T07:58:12", "endTime": "2026-10-30T17:02:40",
      "durationMinutes": 544, "open": false, "autoClosed": false } ],
  "page": 0, "size": 50, "totalElements": 1, "totalPages": 1 }
```

---

## 5. Kayıt iptali

İptal silme değildir. Kaydın durumu `CANCELLED` olur; önceki durum, gerekçe, iptal eden ve zaman saklanır. İptal edilen kayıt cirodan düşer, çünkü ciro yalnızca `PAID` kayıtları toplar. İptal edilen kaçak kaydı borçtan da düşer. Gerekçe zorunludur ve 5-500 karakter olmalıdır.

### `POST /api/admin/records/{id}/cancel` (ADMIN): kapanmış kayıt (PAID veya RUNAWAY)

```json
{ "reason": "Aynı araç iki kez kapatıldı, mükerrer fiş" }
```

→ `200` iptal edilmiş kayıt nesnesi (aşağıdaki liste öğesiyle aynı biçim). Kayıt zaten iptal edilmişse `409 ALREADY_CANCELLED`, durumu PAID veya RUNAWAY değilse `400 BAD_REQUEST`, kayıt yoksa `404`. Gerekçe eksikse `400 VALIDATION_ERROR`. SSE: o sahaya `RECORD_CANCELLED` (`{recordId, plate, previousStatus, fee}`).

### `POST /api/parking/spots/cancel?plate=34ABC123` (OFFICER kendi sahasında, ADMIN): yanlış girilen aktif araç

```json
{ "reason": "Plaka yanlış okundu" }
```

Aktif araç kaldırılır ve `CANCELLED` durumlu bir kayıt oluşur. Ücret 0, önceki durum `ACTIVE`, `collectedBy` iptal eden kişidir. Araç aktif değilse check-out ile aynı 404 veya 409 yanıtı döner. SSE: o sahaya `VEHICLE_CANCELLED` (`{plate, recordId, zoneId, street}`). İstemci bu olayda aracı listeden çıkarmalı; bu olay yeni olduğu için eski istemciler ilk tam yenilemeye kadar aracı göstermeye devam eder.

### `GET /api/admin/records/cancelled?from&to&page&size` (ADMIN)

Filtre iptal zamanına uygulanır. `from` ve `to` isteğe bağlıdır; verilmezse tarih sınırı yoktur. Sıralama en yeni önce.

```json
{ "content": [
    { "id": 812, "plate": "34ABC123", "street": "TEVFIK_BEY", "zoneId": 1, "zoneName": "Tevfik Bey - Bölüm 1",
      "entryTime": "2026-10-30T09:10:00", "exitTime": "2026-10-30T10:40:00", "fee": 40.0,
      "vehicleType": "SMALL", "paymentMethod": "CASH", "status": "CANCELLED", "previousStatus": "PAID",
      "cancelledAt": "2026-10-30T11:02:00", "cancelledBy": "admin",
      "cancelReason": "Aynı araç iki kez kapatıldı, mükerrer fiş", "collectedBy": "tevfik" } ],
  "page": 0, "size": 50, "totalElements": 1, "totalPages": 1 }
```

Eski listelerde (`/api/parking/history`, `/api/parking/bi/last-records`) iptal edilen kayıt `status: "CANCELLED"` ile görünür. Yeni alanlar: `cancelledFromStatus`, `cancelledAt`, `cancelledByUsername`, `cancelReason`, `plateAnonymized`.

---

## 6. Denetim kaydı (ADMIN)

### `GET /api/admin/audit?from&to&action&actor&actorRole&entityType&entityId&q&page&size`

Tüm filtreler isteğe bağlıdır. `from` ve `to` varsayılan olarak son 30 gündür. `action` birden fazla değeri virgülle alabilir. `q`, özet metninde büyük/küçük harf duyarsız arama yapar. Sıralama en yeni önce.

```json
{ "content": [
    { "id": 5012, "createdAt": "2026-11-01T10:20:11", "actorUsername": "admin", "actorRole": "ADMIN",
      "deviceId": "panel-1", "ipAddress": "88.1.2.3", "action": "PASSWORD_CHANGED",
      "entityType": "User", "entityId": "1", "summary": "admin kendi şifresini değiştirdi",
      "before": null, "after": null } ],
  "page": 0, "size": 50, "totalElements": 1, "totalPages": 1 }
```

- `before` ve `after`, işlemden önceki ve sonraki özetin JSON nesnesidir (ör. tarife alanları, `{plate, fee, paymentMethod}`). Şifre ve kişisel iletişim bilgisi yazılmaz.
- Geliştirici işlemlerini görmek için `actorRole=DEVELOPER` filtresi kullanılır.
- `GET /api/admin/audit/actions`: filtre listesi için `[{ "action": "VEHICLE_CHECK_OUT", "label": "Araç çıkışı" }, ...]`.
- `GET /api/admin/audit/export?<aynı filtreler>&format=csv|xlsx`: en fazla 50 000 satır. Dosya adı `denetim-kaydi.csv` veya `denetim-kaydi.xlsx`.
- Bilinmeyen `action` adı veya hatalı tarih `400 VALIDATION_ERROR` döner. `actor` kullanıcı adında, `q` özette içerir araması yapar.
- Başarısız girişte (`LOGIN_FAILED`) `actorUsername` denenen kullanıcı adıdır, `actorRole` boştur. Giriş kaydının `after` alanında `{deviceId, shiftId}` bulunur.

Kayda alınan işlemler (`action`):

| Grup | Değerler |
|---|---|
| Oturum | `LOGIN`, `LOGIN_FAILED`, `LOGOUT`, `PASSWORD_CHANGED` |
| Araç ve kayıt | `VEHICLE_CHECK_IN`, `VEHICLE_CHECK_OUT`, `VEHICLE_RUNAWAY`, `SPOT_CANCELLED`, `RECORD_CANCELLED` |
| Tarife | `TARIFF_DRAFT_CREATED`, `TARIFF_DRAFT_UPDATED`, `TARIFF_SCHEDULED`, `TARIFF_CANCELLED`, `TARIFF_ACTIVATED` (sistem) |
| Abonelik | `SUBSCRIPTION_CREATED`, `SUBSCRIPTION_CANCELLED` |
| Kullanıcı | `USER_CREATED`, `USER_UPDATED`, `USER_ZONE_ASSIGNED`, `USER_ACTIVATED`, `USER_DEACTIVATED`, `USER_PASSWORD_RESET` |
| Mali dönem | `FISCAL_YEAR_CREATED`, `FISCAL_YEAR_ARCHIVED`, `FISCAL_YEAR_DELETED` |
| Ayar, lisans, rapor, saklama | `SETTINGS_UPDATED`, `LICENSE_UPDATED`, `REPORT_EXPORTED`, `RETENTION_RUN` (sistem) |
| Geliştirici | `DEVELOPER_ACCOUNT_CREATED` (sistem), `DEV_HEALTH_VIEWED`, `DEV_ERRORS_VIEWED`, `DEV_ADMIN_PASSWORD_RESET` |

`deviceId` her istekteki `X-Device-Id` başlığından alınır. Panel de sabit bir değer göndermeli (ör. `panel-<tarayıcı-uuid>`).

---

## 7. Ayarlar (ADMIN)

### Performans ağırlıkları

`GET /api/admin/settings/performance-weights` / `PUT` aynı gövdeyle:

```json
{ "hours": 20, "punctuality": 15, "throughput": 20, "collection": 20, "runaway": 15, "cancellation": 10,
  "startTime": "08:00", "graceMinutes": 5 }
```

Her ağırlık 0-100 arasında olmalı ve toplamları tam 100 olmalı. Kurala uymazsa `400 INVALID_SETTINGS`. `startTime` + `graceMinutes`, "zamanında başlama" sınırıdır (varsayılan 08:05).

### Yıllık kira bedeli

- `GET /api/admin/settings/rent` → `{"years":[{"year":2026,"annualRent":1200000.0,"monthlyShare":100000.0}]}`
- `PUT /api/admin/settings/rent` `{"year":2026,"annualRent":1200000}` → `{"year":2026,"annualRent":1200000.0,"monthlyShare":100000.0}`

Kira, 3 caddenin tek ihalesi olduğu için toplam tutar olarak girilir ve yıl bazında saklanır. Böylece geçmiş raporlar, o yılın kirasıyla hesaplanmaya devam eder. Değişiklikler `SETTINGS_UPDATED` olarak denetim kaydına düşer.

---

## 8. Raporlar (ADMIN, Excel/CSV)

Tüm raporlar okunur modda da çalışır. Her indirme `REPORT_EXPORTED` olarak denetim kaydına düşer (`entityType: "Report"`). `format` yalnızca `xlsx` veya `csv` olabilir; geçersiz `format` veya `sheet` değeri `400 VALIDATION_ERROR` döner.

Dosya adları: `personel-raporu-2026-10.xlsx`, `kacak-raporu-<from>_<to>`, `donem-raporu-<from>_<to>`, `kayitlar-<from>_<to>`.

Excel biçimi:

- Kalın başlık satırı, dondurulmuş başlık ve otomatik filtre.
- Tutarlar `#,##0.00 "TL"` biçimli sayı hücresidir; tarihler gerçek tarih hücresidir.
- Toplam satırları kalındır.

### 8.1 Aylık personel raporu

`GET /api/admin/reports/personnel?month=2026-10&format=xlsx`
CSV: `&format=csv&sheet=summary` (varsayılan), `&sheet=shifts` veya `&sheet=method`
Panel için JSON: `GET /api/admin/reports/personnel/summary?month=2026-10`

JSON dizisi şu anahtarları içerir: `username, fullName, zoneId, zoneName, workDays, shiftCount, hours, onTimeRate, transactions, throughput, collectionTotal, collectionPerHour, cash, pos, subscriptionCount, otherMethod, runawayCount, runawayRate, cancelCount, cancelRate, score, rating`. `onTimeRate`, `runawayRate` ve `cancelRate` yüzde değeridir (0-100).

Hesaplama ayrıntıları:

- Ay sınırını aşan vardiyanın yalnızca ay içindeki kısmı saate sayılır.
- Mesai günü, vardiya sayısı ve zamanında başlama yalnızca ay içinde başlayan vardiyalardan hesaplanır.
- Kayıtlar personele `collectedByUsername` ile, vardiyalar kullanıcı adıyla bağlanır.
- Sonradan iptal edilen PAID veya RUNAWAY kaydı işlem sayısına girer. İptali o aya düşüyorsa iptal sayısına da girer.

Sayfalar:

1. **Özet:** her personel için bir satır. Sütunlar: mesai günü, vardiya sayısı, çalışılan saat, zamanında başlama %, işlem sayısı, saat başına işlem, tahsilat (toplam, nakit, POS), abonelikle çıkış, kaçak sayısı ve oranı, iptal sayısı, **performans puanı (0-100)** ve değerlendirme (Çok iyi ≥85, İyi ≥65, Geliştirilmeli).
2. **Mesai:** ayın tüm vardiyaları (personel, saha, cihaz, başlangıç, bitiş, süre, zamanında mı, otomatik kapandı mı).
3. **Puan Yöntemi:** ağırlıklar ve hesaplama.

Puan hesabı. Puanı yalnızca yönetici görür; saha uygulamasında gösterilmez.

- Ölçütler: çalışılan saat, zamanında başlama oranı, saat başına işlem, saat başına tahsilat, kaçak oranı (düşük iyi), iptal oranı (düşük iyi).
- Caddelerin yoğunluğu farklı olduğu için personel, **aynı sahanın ortalamasıyla** kıyaslanır. Saha ortalaması, rapor ayı ve önceki 2 ayın saha toplamlarından hesaplanır. Her sahada genelde tek personel olduğundan bu, kişiyi sahanın son 3 aylık düzeyiyle kıyaslamak anlamına gelir.
- Alt puanlar:
  - Yüksek olan iyi: `75 × değer / saha ortalaması`, 0-100 arası. Ortalamadaki personel 75 alır, ortalamanın 1,33 katı 100 alır.
  - Düşük olan iyi: `75 × (2 − değer / ortalama)`, 0-100 arası. Saha ortalaması 0 ise 100.
  - Zamanında başlama mutlak hesaplanır: oran × 100.
- Toplam puan = Σ ağırlık × alt puan / 100.

### 8.2 Belediye için kaçak araç raporu

`GET /api/admin/reports/runaways?from=2026-10-01&to=2026-10-31&format=xlsx[&street=TEVFIK_BEY][&unpaidOnly=true]`

Excel sayfası "Kaçak Araçlar"dır; üstte bir başlık satırı bulunur.

Sütunlar: Plaka, Cadde, Saha, Giriş tarih-saat, Çıkış (kaçak işaretleme) tarih-saat, Tutar, Memur, Borç durumu. Son satır "Toplam (N kayıt)"tır. İptal edilen kaçak kayıtları rapora girmez. `street` cadde kodudur; geçersiz kod `400` döner.

### 8.3 Dönem raporu (ciro, kira, net)

`GET /api/admin/reports/period?from=2026-10-01&to=2026-10-31&format=xlsx`
CSV: `sheet=summary|zones|daily`
Panel için JSON: `GET /api/admin/reports/period/summary?from&to`

- **Özet:**
  - Nakit ciro, POS ciro, abonelik satışı (başlangıcı dönemde olan aboneliklerin aylık bedeli), diğer/eski kayıt (ödeme yöntemi boş), toplam ciro.
  - Kira payı: o yılın kirası / 12, dönemin ay içindeki gün oranıyla. Ayrıca aylık kira payı.
  - **Net kalan** = toplam ciro − kira payı.
  - Bilgi amaçlı: iptal edilen kayıtlar, ödenmemiş kaçak borç.
- Kira girilmemiş yıllarda o yılın payı 0 sayılır. Excel'e "Not: <yıl> için kira bedeli girilmemiş" satırı eklenir.
- JSON anahtarları: `from, to, cashRevenue, posRevenue, subscriptionSales, otherRevenue, totalRevenue, subscriptionExitCount, rentShare, rentMonths[{month, overlapDays, monthlyShare, share}], rentMissingYears[], netRemaining, cancelledCount, cancelledFees, unpaidRunawayCount, unpaidRunawayDebt`.
- **Saha bazında:** saha başına nakit, POS, diğer, toplam, işlem ve kaçak sayısı. Abonelikle çıkış işlem sayısına girer, ciroya girmez.
- **Günlük:** gün başına ciro. Kayıt olmayan günler 0 olarak listelenir.

### 8.4 Kayıt dökümü (dönem bazında, silmeden)

`GET /api/admin/reports/records?from&to&format`: giriş zamanı dönemde olan tüm kayıtlar, iptaller dahil. Excel sayfası "Kayıtlar"dır. 100 000 satırı aşarsa `400` döner; dönemi bölerek indirin.

---

## 9. Geliştirici (DEVELOPER)

| İstek | Gövde / yanıt |
|---|---|
| `PUT /api/developer/license` | `{"expiresOn":"2027-11-01","graceDays":15,"licensee"?,"note"?}` → lisans durumu (bkz. §1). Denetim kaydı: `LICENSE_UPDATED`, önce/sonra. |
| `GET /api/developer/health` | `{status: UP\|DEGRADED, time, uptimeSeconds, javaVersion, memory{usedMb,maxMb}, database{ok,latencyMs}, flywayVersion, counts{activeSpots,parkingRecords,users,openShifts}, license, errorsLast24h, lastRetentionRun}`. Veritabanına ulaşılamazsa hata vermez: `database.ok=false` döner. Denetim kaydı: `DEV_HEALTH_VIEWED`. |
| `GET /api/developer/errors?limit=50` | `[{id, createdAt, method, path, errorCode, exceptionClass, message, stackExcerpt, username}]`. Denetim kaydı: `DEV_ERRORS_VIEWED`. |
| `POST /api/developer/admin-password-reset` | `{"username":"admin","newPassword":"..."}` → `{message, username}`. Yalnızca ADMIN hesapları (`400 PROTECTED_ACCOUNT`; bilinmeyen kullanıcıda `404`). Denetim kaydı: `DEV_ADMIN_PASSWORD_RESET`. |

Geliştiricinin başka yetkisi yoktur. Gizli bir kapatma, kilitleme veya silme uç noktası bulunmaz.

---

## 10. Saklama gece işi

Her gece 02:30'da (Europe/Istanbul) çalışır ve idempotenttir: aynı gün tekrar çalışırsa hiçbir şeyi değiştirmez. Aktif abonelikler ve aktif araçlar hiç değiştirilmez.

| Kural | İşlem |
|---|---|
| Çıkışı 5 yıldan eski park kayıtları | Plaka `ANONIM`, `plateAnonymized=true`. Kayıt ve tutar kalır. |
| Bitişi 5 yıldan eski abonelikler | Plaka `ANONIM` |
| 5 yıldan eski denetim kayıtları | Önce/sonra ayrıntısı silinir, özet "Saklama süresi doldu; ayrıntılar anonimleştirildi." olur |
| Bitişinden 1 yıl geçmiş abonelikler | Abone adı ve telefonu boşaltılır |
| 1 yıldan eski, kapanmış vardiyalar | Silinir. Performans puanı vardiyalardan hesaplandığı için ayrıca saklanan puan verisi yoktur. |

Bir şey değiştiğinde `RETENTION_RUN` denetim kaydı yazılır. Son çalışmanın özeti geliştirici sağlık ekranında görünür.

---

## 11. Canlı olaylar (SSE), eklemeler

| Olay | Kapsam | Gövde |
|---|---|---|
| `USER_UPDATED` | hedef kullanıcı ve yöneticiler | `{userId, username, zoneId, role}` |
| `VEHICLE_CANCELLED` | saha | `{plate, recordId, zoneId, street}` |
| `RECORD_CANCELLED` | saha | `{recordId, plate, previousStatus, fee}` |
| `LICENSE_UPDATED` | herkes | `{expiresOn, graceDays, state}` |

Kare biçimi ve `since` ile kaldığı yerden devam etme Aşama 3'teki gibidir (`docs/api-asama3.md`).

---

## 12. İstemci entegrasyonu için yapılacaklar

Yönetim paneli:

- [ ] Lisans bandı (`/api/license/status`)
- [ ] 423 mesajının gösterilmesi
- [ ] "Şifremi değiştir" ekranı
- [ ] Personel ekranı: liste, ekle, düzenle, saha ata, pasife al, şifre sıfırla
- [ ] Mesai listesi (filtreli, sayfalı)
- [ ] Kayıt tablosunda "İptal et" (gerekçe penceresi) ve "İptal edilenler" sekmesi
- [ ] Denetim kaydı ekranı (filtreler, CSV)
- [ ] Raporlar sayfası: personel, kaçak, dönem, kayıt dökümü; Excel/CSV düğmeleri
- [ ] Ayarlar: performans ağırlıkları, yıllık kira
- [ ] SSE'de `LICENSE_UPDATED` ve `RECORD_CANCELLED` olaylarında ilgili verilerin yenilenmesi

Saha uygulaması:

- [ ] 423 mesajı
- [ ] "Yanlış giriş, iptal et" (gerekçeli)
- [ ] `VEHICLE_CANCELLED` ve `USER_UPDATED` olaylarının işlenmesi
- [ ] Şifre değiştirme ekranı
- [ ] Lisans uyarısı: yalnızca 7 ve 1 gün kala, isteğe bağlı
