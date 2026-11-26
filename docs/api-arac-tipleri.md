# Araç tipleri veritabanında (27 Ekim 2026)

Araç tipleri artık `VehicleType` enum'u değil, `vehicle_types` tablosundaki satırlardır. Kodlar aynı kaldı
(`SMALL`, `LARGE`); üçüncü tip `MOTORCYCLE` pasif olarak eklendi. Bir tip yalnızca **tarife akışıyla**
etkinleşir/pasifleşir (taslak → önizleme → yürürlük tarihi → yönetici şifresi). Ayrı bir anlık anahtar yoktur.

## Veritabanı (Flyway V5)

`vehicle_types`

| sütun | tip | not |
|---|---|---|
| code | varchar(30) PK | `SMALL`, `LARGE`, `MOTORCYCLE` |
| display_name | varchar(60) | Otomobil, Kamyonet, Motosiklet |
| icon_key | varchar(30) | `car`, `truck`, `motorcycle` |
| sort_order | int | 10, 20, 30 |
| active | boolean | yürürlükteki tarifeden türetilir (SMALL/LARGE true, MOTORCYCLE false) |

`tariff_vehicle_prices` (tarife sürümü × araç tipi)

| sütun | tip | not |
|---|---|---|
| tariff_id | bigint FK → tariffs(id) | PK parçası |
| vehicle_type | varchar(30) FK → vehicle_types(code) | PK parçası |
| enabled | boolean | bu sürümde tip etkin mi |
| first_hour, extra_hour, subscription_monthly | float, null olabilir | etkin tipte zorunlu |

- Var olan her tarifenin `small_*` / `large_*` / `subscription_*` değerleri bu tabloya SMALL ve LARGE satırı
  olarak taşınır, eski altı sütun düşürülür. Satırı olmayan tip o sürümde pasif sayılır.
- `parking_records.vehicle_type`, `active_park_spots.current_type`, `subscriptions.vehicle_type` aynı
  varchar değerleri tutmaya devam eder; üzerlerindeki olası CHECK kısıtları düşürülür, `vehicle_types(code)`
  FK'si eklenir.
- `vehicle_types.active` elle değiştirilmez: bir tarife ACTIVE olduğunda (ve açılışta) o tarifenin `enabled`
  kümesiyle eşitlenir.

## Uç noktalar

### `GET /api/vehicle-types` — herkese açık
Yalnızca etkin tipler, `sort_order` sırasıyla:
```json
[
  {"code":"SMALL","displayName":"Otomobil","iconKey":"car","sortOrder":10},
  {"code":"LARGE","displayName":"Kamyonet","iconKey":"truck","sortOrder":20}
]
```

### `GET /api/admin/vehicle-types` — ADMIN
Tüm tipler, `active` alanıyla (`{"code", "displayName", "iconKey", "sortOrder", "active"}`).

### Tarife haritası (`GET /api/tariff/current`, `/api/admin/tariffs`, TARIFF_UPDATED yükü)
Eski alanlar geriye uyumluluk için kalır (`small`, `large`, `subscription.small/large`; tipin satırı yoksa
`null`). Yeni alan `vehicleTypes`, tablodaki **tüm** tipleri sırasıyla verir:
```json
"vehicleTypes": [
  {"code":"SMALL","displayName":"Otomobil","iconKey":"car","sortOrder":10,"enabled":true,
   "firstHour":25.0,"extraHour":15.0,"subscriptionMonthly":2500.0},
  {"code":"LARGE","displayName":"Kamyonet","iconKey":"truck","sortOrder":20,"enabled":true,
   "firstHour":50.0,"extraHour":30.0,"subscriptionMonthly":3000.0},
  {"code":"MOTORCYCLE","displayName":"Motosiklet","iconKey":"motorcycle","sortOrder":30,"enabled":false,
   "firstHour":null,"extraHour":null,"subscriptionMonthly":null}
]
```
İstemci kuralı: fiyat göstermek için `enabled == true` olanları kullan; `vehicleTypes` yoksa (eski sunucu)
`small`/`large` alanlarına düş.

### Taslak oluşturma / düzenleme (`POST /api/admin/tariffs`, `PUT /api/admin/tariffs/{id}`)
`TariffRequest` yeni alan alır:
```json
{
  "freeMinutes":5, "paidStart":"08:00", "paidEnd":"22:00", "note":"Motosiklet eklendi",
  "vehicleTypes":[
    {"code":"SMALL","enabled":true,"firstHour":25,"extraHour":15,"subscriptionMonthly":2500},
    {"code":"LARGE","enabled":true,"firstHour":50,"extraHour":30,"subscriptionMonthly":3000},
    {"code":"MOTORCYCLE","enabled":true,"firstHour":10,"extraHour":5,"subscriptionMonthly":800}
  ]
}
```
- `vehicleTypes` verilirse: bilinmeyen kod → 400 `INVALID_TARIFF`; en az bir tip etkin olmalı; etkin tipte
  ilk saat > 0, ek saat ≥ 0, abonelik > 0. Pasif tipin fiyatları isteğe bağlı (verilirse saklanır).
  Listede olmayan tipler yürürlükteki tarifedeki hâlleriyle kopyalanır.
- `vehicleTypes` verilmezse (eski istemci): eski `smallFirstHour…subscriptionLargeMonthly` alanları SMALL ve
  LARGE satırı olur (etkin); diğer tipler yürürlükteki tarifeden kopyalanır.
- Hata metinleri görünen adı kullanır: "Motosiklet ilk saat ücreti sıfırdan büyük olmalıdır."

### Önizleme (`POST /api/admin/tariffs/{id}/preview`)
- `rows`: yürürlükteki **veya** önerilen tarifede etkin olan her tip için 10 örnek satır. Satırda
  `vehicleType` (kod) ve `displayName` var. Tip yürürlükteki tarifede pasifse `current` `null`, önerilende
  pasifse `proposed` `null` olur.
- `typeChanges`: durumu değişen tipler, ör.
  `[{"code":"MOTORCYCLE","displayName":"Motosiklet","currentEnabled":false,"proposedEnabled":true}]`.

### Zamanlama / yürürlük
Değişmedi (şifre + gelecekte yürürlük tarihi). Tarife ACTIVE olunca `vehicle_types.active` eşitlenir, sonra:
1. `TARIFF_UPDATED` (GLOBAL, yük = yeni `currentWithUpcoming`, `vehicleTypes` dahil) — her zaman,
2. `VEHICLE_TYPES_UPDATED` (GLOBAL, yük = `{"vehicleTypes":[…/api/vehicle-types biçimi…]}`) — etkin küme
   değiştiyse.
Denetim kaydında yürürlük açıklamasına "etkinleşen: Motosiklet" / "pasifleşen: …" eklenir.

### Giriş (`POST /api/parking/check-in?type=…`)
- `type` boşsa `SMALL` (eski davranış). Küçük/büyük harf fark etmez.
- Bilinmeyen kod → 400 `UNKNOWN_VEHICLE_TYPE`, "Bilinmeyen araç tipi: X".
- Pasif tip → 400 `VEHICLE_TYPE_INACTIVE`, "Motosiklet şu an tarifede etkin değil. Yönetici tarife
  sayfasından etkinleştirebilir."
- Abonelik oluşturmada (`/api/subscription/create`) aynı kurallar; aylık ücret tipin tarife satırındaki
  `subscriptionMonthly`.

### Giriş tekrarı (`checkInRequestId`)
`POST /api/parking/check-in` isteğe bağlı `checkInRequestId` (8-64 karakter, `[A-Za-z0-9-]`) alır; çevrimdışı kuyruk
tekrarını güvenli kılar. Biçim hatalıysa 400 `INVALID_REQUEST_ID`. Aynı kimlik, oluşturduğu kayıt hâlâ aktifken
tekrar gelirse 200 ile aynı `ParkSpot` döner (yeni kayıt, denetim kaydı veya olay yok; `ALREADY_PARKED` kontrolünden
önce çalışır). Kayıt artık aktif değilse (çıkış/kaçak/iptal) 409 `DUPLICATE_REQUEST` ("Bu giriş daha önce işlendi.").
Parametresiz çağrı eskisi gibidir.

### Çevrimdışı giriş saati (`clientEntryTime`)
`POST /api/parking/check-in` isteğe bağlı `clientEntryTime` alır: memurun girişi cihazda yaptığı an, ofsetli
ISO-8601 (ör. `2026-10-29T08:15:30.000Z`; `+03:00` gibi ofset de olur, sorgu dizesinde `+` kodlanmalı). Mobil
uygulama bunu yalnızca çevrimdışı kuyruktan gönderirken ekler; doğrudan çevrimiçi girişte göndermez.
- Sunucu değeri `Europe/Istanbul` yerel saatine çevirip **giriş saati** (`entryTime`) olarak yazar; ücret çıkışta bu
  saatten itibaren hesaplanır (ücret motoru değişmedi).
- Kabul aralığı: `[şimdi − 24 saat, şimdi + 5 dakika]`. 5 dakikaya kadar ileri saat kayması kabul edilir ama giriş
  saati sunucu saatine kırpılır (giriş gelecekte olamaz).
- Aralık dışı ya da okunamayan değer girişi **reddetmez**: sunucu saati (isteğin ulaştığı an) kullanılır ve uyarı
  log'lanır. Böylece kuyruktaki giriş, saat yüzünden kaybolmaz.
- Alan yoksa davranış eskisi gibidir (sunucu saati). Eski istemciler etkilenmez.
- `checkInRequestId` tekrarında ilk kayıt döner; ikinci istekteki `clientEntryTime` giriş saatini değiştirmez.
- Denetim kaydının (`VEHICLE_CHECK_IN`) `after` verisine `entryTime`, `entryTimeSource` (`CLIENT` | `SERVER`) ve
  gönderildiyse ham `clientEntryTime` eklenir; geriye dönük saatli girişler bu alanla izlenebilir.
- Yeni migration yok; mevcut `park_spots.entry_time` sütunu kullanılır.

### Yabancı / özel plaka (`foreignPlate`, Flyway V10)
Türk biçimine uymayan plakalar (yabancı ülke plakası, diplomatik CD vb.) için. Varsayılan davranış değişmedi: bayrak
yoksa yalnızca Türk plakası kabul edilir.
- `POST /api/parking/check-in` isteğe bağlı `foreignPlate=true` alır. Plaka o zaman TR kuralı yerine yabancı / özel
  kuralla denetlenir: boşluk ve tire atıldıktan sonra yalnızca `A-Z` ve `0-9`, 2-12 karakter (ör. `M AB 1234` →
  `MAB1234`, `12-ABC-3` → `12ABC3`). Uymazsa 400 `INVALID_PLATE` ("Geçersiz yabancı / özel plaka: …").
- Bayraksız gelen yabancı plaka eskisi gibi 400 `INVALID_PLATE` alır (görevli anahtarı açmayı unuttuysa).
- Plaka aynı temiz biçimde saklanır; bu yüzden tekillik (aynı aktif plaka → 409 `ALREADY_PARKED`),
  `checkInRequestId` tekrarı, çıkış, kaçak, iptal, `fee-preview` ve kaçak borcu uyarısı TR plakalarıyla aynı çalışır.
- Bayrak saklanır: `active_park_spots.foreign_plate` ve `parking_records.foreign_plate` (`boolean NOT NULL DEFAULT
  false`). Çıkış, kaçak ve iptalde kayda kopyalanır. JSON alanı: `ParkSpot.foreignPlate`, `ParkingRecord.foreignPlate`;
  `GET /api/parking/debt` yanıtında da `foreignPlate` var. Mevcut satırlar (V9 ve öncesi) `false` kalır.
- `GET /api/parking/debt` (vatandaş sayfası / QR) hem TR hem yabancı / özel plakayı sorgular (yabancı kural TR
  plakalarını da kapsar).
- Rapor: Excel/CSV "Kayıtlar" ve "Kaçak Araçlar" sayfalarında "Yabancı plaka" sütunu (`Evet` ya da boş). Panel kayıt
  ve kaçak listelerinde plakanın yanında "Yabancı" işareti.
- Ülke kodu isteğe bağlıdır, ayrıntısı aşağıda ("Yabancı plaka ülke kodu", V11).
- Mobil: giriş formunun başlık satırında "Yabancı / özel plaka" anahtarı (varsayılan kapalı). Açıkken TR denetimi ve
  OCR düzeltmesi (O→0 gibi) yapılmaz; kamera ekranındaki aynı adlı seçenekle okunan ham metin forma gelir, görevli
  onaylar ya da düzeltir. Çevrimdışı kuyruk bayrağı saklar ve gönderir.
- Geliştirici görünümü: yabancı plakalar da maskelenir, arama yalnızca tam plakayla (bkz. `api-gelistirici-izleme.md`).

### Yabancı plaka ülke kodu (`foreignCountryCode`, Flyway V11)
Yabancı plakanın ülkesi: aracın uluslararası tanıtma işareti (ör. `D` Almanya, `NL`, `GB`, `BG`, `RO`, `CH`).
- `POST /api/parking/check-in` isteğe bağlı `foreignCountryCode` alır (ör. `&foreignPlate=true&foreignCountryCode=D`).
- Kural: baştaki ve sondaki boşluk atılır, büyük harfe çevrilir (Türkçe `i/ı/İ` → `I`), sonra 1-3 harf `A-Z` olmalı
  (`d` → `D`, ` nl ` → `NL`). Rakam, iç boşluk, tire, Türkçe harf (`Ü`, `Ç`) ya da 4+ harf → 400
  `INVALID_COUNTRY_CODE` ("Geçersiz ülke kodu: …").
- Boş ya da hiç gönderilmeyen kod `null` saklanır; giriş yine çalışır (görevli ülkeyi bilmeyebilir).
- Yalnızca `foreignPlate=true` ile kabul edilir. TR plakasında (bayrak yok ya da `false`) dolu bir kod gelirse 400
  `INVALID_COUNTRY_CODE` ("Ülke kodu yalnızca yabancı / özel plakada girilebilir …"); sessizce atılmaz. Boş kod TR
  plakasında sorun değildir.
- Doğrulama plaka denetiminden hemen sonra, `checkInRequestId` tekrarından önce yapılır. Tekrar (aynı istek kimliği)
  ilk girişte saklanan aracı ve kodunu döndürür.
- Saklama: `active_park_spots.foreign_country_code` ve `parking_records.foreign_country_code` (`varchar(3)`, boş
  bırakılabilir, varsayılan yok). Çıkış, kaçak ve iptalde kayda kopyalanır. Mevcut satırlar (V10 ve öncesi) `NULL`
  kalır.
- JSON alanı: `ParkSpot.foreignCountryCode`, `ParkingRecord.foreignCountryCode`; `GET /api/parking/debt` yanıtında,
  iptal listesinde (`GET /api/admin/records/cancelled`), `VEHICLE_ENTERED` eşitleme olayında ve giriş/çıkış/kaçak
  denetim kayıtlarının `after` verisinde de var (kod varsa).
- Rapor: Excel/CSV "Kayıtlar" ve "Kaçak Araçlar" sayfalarında "Yabancı plaka" sütunundan sonra "Ülke kodu" sütunu.
  Panel kayıt, kaçak ve iptal listelerinde işaret "Yabancı · D" olarak görünür; panelin kendi Excel dışa aktarımında da
  "Ülke kodu" sütunu var.
- Vatandaş sayfası / QR: plaka zaten gösterildiği için kod plakanın yanında görünür ("MAB1234 · D"). Yeni kişisel veri
  yok.
- Geliştirici görünümü: ülke kodu kişisel veri değildir, maskelenmez; plaka eskisi gibi maskelenir.
- Abonelikte yabancı plaka bayrağı ve ülke kodu yok (V10 da aboneliğe bayrak eklemedi).
- Mobil: ayrı PR'da gelecek; bu sürümde mobil kodu göndermez, sunucu kodu `null` saklar.

### Ücret
`FeeEngine` tipin tarife satırındaki fiyatı kullanır. Park hâlindeyken tipi pasifleşen araç: satırda fiyat
varsa o, yoksa SMALL fiyatı (çıkış engellenmez). Kodu `null` olan eski kayıt SMALL sayılır.

### Raporlar ve BI
- `GET /api/parking/bi/income-by-type`: biçim aynı (görünen ad → ciro), ama anahtarlar tablodan gelir
  (etkin tipler + cirosu olan pasif tipler, sırayla).
- Yeni `GET /api/parking/bi/income-by-type/details` (ADMIN):
  `[{"code","displayName","iconKey","active","income","records"}]` — panel grafikleri bunu kullanır.
- Dönem raporu (`/api/admin/reports/period/summary` JSON'u ve xlsx/csv): yeni `byVehicleType`
  (`[{"code","displayName","transactions","income"}]`) ve "Araç tipine göre" sayfası.
- Kayıt raporu: "Araç tipi" sütunu görünen adı yazar.

## İstemci yapılacaklar
- **Panel:** tarife formu ve kartları `vehicleTypes`'tan dinamik; tip başına "Etkin" anahtarı + üç fiyat;
  önizlemede `typeChanges` uyarısı; geçmiş tablosunda tip sütunları dinamik; grafikler `/details` ile.
- **Mobil:** giriş formundaki tip seçimi ve ikonlar `/api/vehicle-types`'tan; `TARIFF_UPDATED` ve
  `VEHICLE_TYPES_UPDATED` olaylarında ve ekran yenilenince tekrar yüklenir; ikon eşlemesi
  `car` → otomobil, `truck` → kamyonet, `motorcycle` → iki tekerlekli.
- **Vatandaş sayfası:** tarife özeti `vehicleTypes`'ın etkin satırlarıyla.
