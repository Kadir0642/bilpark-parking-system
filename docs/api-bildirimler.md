# Yönetici bildirimleri ve bakım işlemlerinin ayrılması

Amaç: Yönetici (firma sahibi) operasyonel işlerle uğraşır. Geliştiricinin teknik bakım hareketleri onun denetim ekranını doldurmaz. Yönetici, kendisini ilgilendiren sistem olaylarını sade bir **bildirim kutusunda** görür. Bunlar güncellemeler, şifre sıfırlama, lisans değişikliği ve planlı bakım duyurularıdır.

Şeffaflık korunur:
- Geliştirici işlemleri yine denetim kaydına yazılır.
- Kayıtlar silinmez, gizli kanal yoktur.
- Yönetici bu kayıtları istediği an "Bakım işlemlerini göster" seçeneğiyle görebilir. Sözleşme eki 4.4 buna göre güncellenir.

## 1. Denetim kaydında varsayılan filtre

`GET /api/admin/audit` ve `GET /api/admin/audit/export` uç noktalarına `includeMaintenance` parametresi eklenir (`true`/`false`, varsayılan `false`).

- `false` iken şu satırlar listeden ve sayımlardan çıkar:
  - aktörünün rolü `DEVELOPER` olan satırlar,
  - eylemi `DEV_` ile başlayan satırlar,
  - `DEVELOPER_ACCOUNT_CREATED` satırları.
- `actorRole=DEVELOPER` ya da bir bakım eylemi (`action=DEV_...`) açıkça istenirse `includeMaintenance` kendiliğinden `true` sayılır.
- Yanıta `maintenanceHidden: <sayı>` alanı eklenir. Bu alan, gizlenen satır sayısını verir; filtreler aynı, bakım dahil sayımdan hesaplanır. Panel bu sayıyı "N bakım işlemi gizlendi · Göster" olarak gösterir.

## 2. Bildirimler

### Veri

Flyway `V9__admin_notifications.sql`:

- `admin_notifications`
  - `id`
  - `type` (varchar 40)
  - `title` (varchar 120)
  - `body` (varchar 2000, boş olabilir)
  - `created_at`
  - `created_by` (kullanıcı adı ya da `system`)
  - `valid_until` (boş olabilir; planlı bakım duyurusu bu tarihten sonra listede "geçmiş" olur)
- `admin_notification_reads`
  - `notification_id`
  - `user_id`
  - `read_at`
  - Birincil anahtar: (`notification_id`, `user_id`)

Türler:

| type | ne zaman oluşur | örnek başlık |
|---|---|---|
| `SYSTEM_UPDATED` | Uygulama açılışında backend sürümü (`AppVersion`), `app_settings` içinde saklanan son sürümden farklıysa otomatik oluşur. İlk kurulumda oluşmaz, yalnızca sürüm saklanır. | "Sistem güncellendi" |
| `ANNOUNCEMENT` | Geliştirici duyuru yazdığında. | Geliştiricinin başlığı |
| `MAINTENANCE` | Geliştirici planlı bakım duyurusu yazdığında. `valid_until` zorunludur. | "Planlı bakım: 3 Kasım 23:00-23:30" |
| `ADMIN_PASSWORD_RESET` | Geliştirici bir yönetici şifresini sıfırladığında. | "admin hesabının şifresi sıfırlandı" |
| `LICENSE_UPDATED` | Lisans tarihleri değiştiğinde. | "Lisans güncellendi: bitiş 1 Kasım 2027" |

Bildirim metinleri sade Türkçe olur. İçlerinde teknik ayrıntı, plaka ve kişisel veri bulunmaz.

### Yönetici uç noktaları (ADMIN)

- `GET /api/admin/notifications?limit=30`, şu biçimde döner: `{ unread: 2, items: [{id, type, title, body, createdAt, validUntil, read}] }`. Sıralama yeniden eskiye.
- `POST /api/admin/notifications/{id}/read` ile tek bir bildirim okundu işaretlenir. `POST /api/admin/notifications/read-all` ile hepsi işaretlenir. İkisi de `204` döner, ikisi de okunur modda çalışır.
- Okuma işlemi denetim kaydına yazılmaz.

### Geliştirici uç noktaları (DEVELOPER)

- `POST /api/developer/announcements`, gövdesi `{type: "ANNOUNCEMENT"|"MAINTENANCE", title, body?, validUntil?}`, `201` döner.
  - Doğrulama kuralları: başlık 3-120 karakter, gövde en fazla 2000 karakter. `MAINTENANCE` için `validUntil` zorunludur ve gelecekte olmalıdır.
  - Denetim kaydına `DEV_ANNOUNCEMENT_CREATED` olarak düşer.
- `GET /api/developer/announcements?limit=30` gönderilen bildirimleri listeler. Her öğede okunma sayısı yer alır: `readCount`.
- Silme ve düzenleme uç noktası yoktur. Yanlış bir duyuru, yeni bir duyuruyla düzeltilir.

### Canlı bildirim

Bildirim oluşunca yalnızca ADMIN kullanıcılarına `NOTIFICATION_CREATED` SSE olayı gider. Yükü şudur: `{id, type, title}`. Mevcut SSE altyapısında rol kapsamı yoksa global olay gönderilebilir; bu durumda mobil uygulama olayı yok sayar. Panel olayı alınca rozeti artırır.

### Saklama

Bildirimler 1 yıl sonra mevcut saklama işinde silinir.

## 3. Panel

- **Yönetici:** Üst çubukta bir zil simgesi ve okunmamış sayısını gösteren bir rozet bulunur. Tıklanınca bir açılır liste görünür; listede başlık, gövde, göreli zaman ve "Tümünü okundu say" düğmesi yer alır. Bir öğe tıklanınca okundu işaretlenir. Süresi dolmuş `MAINTENANCE` duyuruları soluk gösterilir.
- **Yönetici denetim ekranı:** "Bakım işlemlerini göster" onay kutusu varsayılan olarak kapalıdır. Kutu kapalıyken `maintenanceHidden > 0` ise kısa bir not gösterilir.
- **Geliştirici sayfası:** "Duyuru gönder" kartı ve gönderilen duyurular listesi eklenir. Sayfanın üstündeki açıklama şöyle olur: "Bu sayfadaki her işlem denetim kaydına yazılır. Yönetici bu kayıtları 'Bakım işlemlerini göster' ile görebilir; şifre sıfırlama ve lisans değişikliği ona ayrıca bildirilir. Gizli düğme yoktur."
