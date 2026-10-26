# Sözleşme eki taslağı: lisans, okunur mod, veri güvencesi ve geliştirici erişimi

> **Taslaktır.** Bu metin, BilPark yazılımının bu konulardaki teknik davranışını sözleşmeye eklenebilecek biçimde anlatır. İmzadan önce bir avukat tarafından gözden geçirilmelidir. Köşeli parantez içindeki değerler (`[...]`) taraflarca doldurulur.

**Taraflar:** [Yazılım sağlayıcı] ("Sağlayıcı") ve [Otopark işletmecisi firma] ("Müşteri").
**Konu:** BilPark otopark yönetim sistemi: sunucu yazılımı, yönetim paneli, saha uygulaması ve vatandaş sayfası ("Sistem").

---

## Madde 1: Lisans süresi

1.1. Sistemin kullanım lisansı **[başlangıç tarihi]** ile **[bitiş tarihi]** arasında geçerlidir. Lisans, [yıllık bakım bedelinin ödenmesiyle] her yıl [1 yıl] uzatılır.

1.2. Bitiş tarihi Sistemde **açıkça görünür**. Yönetici paneli ve `GET /api/license/status` uç noktası lisans sahibini, bitiş tarihini, ek süreyi ve kalan gün sayısını gösterir.

1.3. Sistem, bitişe **30, 7 ve 1 gün** kala Müşteriye uyarı gösterir.

1.4. Bitiş tarihinden sonra Müşteriye **[15] günlük ek süre** tanınır. Ek süre boyunca Sistem tam olarak çalışmaya devam eder ve uyarı gösterir.

## Madde 2: Okunur mod

2.1. Lisans süresi ve ek süre dolduğunda Sistem **kapanmaz**, **okunur moda** geçer.

2.2. Okunur modda **açık kalanlar:**
- Tüm kayıtları, raporları, denetim kayıtlarını ve listeleri görüntüleme
- Excel ve CSV indirme (dönem raporları, kaçak araç raporu, personel raporu, kayıt dökümü)
- Oturum açma ve kapatma, kendi şifresini değiştirme
- Sağlık kontrolü

2.3. Okunur modda **kapalı olanlar:**
- Yeni araç girişi, çıkışı ve kaçak işaretleme
- Kayıt iptali
- Tarife, kullanıcı, abonelik ve ayar değişiklikleri

Kapalı bir işlem denendiğinde Sistem bunun nedenini Türkçe bir mesajla bildirir (HTTP 423).

2.4. Lisans yenilendiğinde Sistem hiçbir veri kaybı olmadan normal çalışmaya döner.

## Madde 3: Veri güvencesi

3.1. Lisansın bitmesi, ödemenin gecikmesi veya sözleşmenin sona ermesi dahil **hiçbir durumda Müşteri verisi silinmez** ve erişilemez hale getirilmez.

3.2. Müşteri, verisini istediği zaman dönem bazında **Excel/CSV** olarak indirebilir. Sözleşme sona ererse Sağlayıcı, Müşterinin talebi üzerine [30] gün içinde veritabanının tam bir dökümünü teslim eder.

3.3. Sistemde **gizli bir kapatma düğmesi, uzaktan kilitleme veya veri silme mekanizması bulunmaz**. Lisans güvencesi yalnızca bu ekte yazan, Müşterinin gördüğü mekanizmalarla sağlanır.

3.4. **Saklama süreleri.** Aşağıdaki kurallar yasal yükümlülükler ve kişisel verilerin korunması için her gece otomatik uygulanır. İşlemler tekrar çalıştırılsa da sonuç değişmez.

| Veri | Süre | İşlem |
|---|---|---|
| Mali kayıtlar (park kayıtları, abonelik satışları) | 5 yıl (VUK md. 253) | Kayıt silinmez, yalnızca plaka `ANONIM` yapılır. Denetim kayıtlarının ayrıntıları da temizlenir. |
| Abone adı ve telefonu | Abonelik bitiminden 1 yıl sonra | Ad ve telefon alanları boşaltılır, abonelik kaydı kalır |
| Mesai (vardiya) kayıtları; performans puanı bunlardan hesaplanır | 1 yıl | Silinir. Müşteri bu süre içinde aylık personel raporunu indirebilir. |

Müşteri, saklama sürelerinin dolmasından önce ilgili dönemlerin Excel/CSV dökümlerini almakla sorumludur.

## Madde 4: Geliştirici (bakım) hesabı

4.1. Sistemde, Müşterinin yönetici hesabından ayrı, Sağlayıcının bakım için kullandığı **bir geliştirici hesabı** bulunur. Bu hesabın varlığı bu ekle Müşteriye bildirilmiştir. Hesap, Müşterinin yönetici panelindeki kullanıcı listesinde görünür.

4.2. Geliştirici hesabının yetkileri **yalnızca** şunlardır:

| Yetki | Uç nokta | Açıklama |
|---|---|---|
| Lisans uzatma | `PUT /api/developer/license` | Bitiş tarihini ve ek süreyi değiştirir |
| Sistem sağlık özeti | `GET /api/developer/health` | Çalışma süresi, bellek, veritabanı erişimi, şema sürümü, kayıt sayıları, son saklama işi |
| Son hata kayıtları | `GET /api/developer/errors` | Beklenmeyen sunucu hataları: adres, hata türü, kısa mesaj |
| Yönetici şifresi sıfırlama | `POST /api/developer/admin-password-reset` | Müşteri yönetici şifresini unuttuğunda, Müşterinin talebiyle |

4.3. Geliştirici hesabı **operasyonel verilere erişemez**: araç kayıtları, ciro, raporlar, abonelikler ve kullanıcı yönetimi kapalıdır. Kayıt ekleyemez, değiştiremez veya silemez. Sistemi kapatamaz.

4.4. Geliştirici hesabının **her işlemi**, görüntüleme işlemleri dahil, denetim kaydına düşer. Kayıtta kim, ne zaman, hangi cihaz ve IP'den, ne yaptığı ve önce/sonra özeti bulunur. Müşteri yöneticisi bu kayıtları panelde (`GET /api/admin/audit`, "Rol: DEVELOPER" filtresi) görebilir ve indirebilir. Denetim kayıtları uygulama içinden değiştirilemez.

4.5. Yönetici şifresi sıfırlama yalnızca Müşterinin [yazılı / e-posta] talebiyle yapılır. Sıfırlamadan sonra eski oturumlar geçersiz olur ve işlem denetim kaydında görünür. Müşteri yeni şifreyi ilk girişte `POST /api/me/password` ile değiştirmelidir.

4.6. Müşteri yönetici paneli üzerinden geliştirici hesabını değiştiremez. Hesabın kapatılması veya şifresinin yenilenmesi Müşterinin yazılı talebiyle Sağlayıcı tarafından yapılır ve denetim kaydına düşer.

## Madde 5: Denetim kaydı

5.1. Sistem şu işlemleri kayda alır: oturum açma ve kapatma (başarısız denemeler dahil), araç girişi, çıkışı, kaçak işaretleme, kayıt iptali (gerekçesiyle), tarife değişiklikleri, abonelik işlemleri, kullanıcı ekleme, düzenleme, saha atama, pasife alma ve şifre sıfırlama, ayar ve kira değişiklikleri, lisans değişiklikleri, rapor indirmeleri ve geliştirici işlemleri.

5.2. Kayıt iptali silme değildir. İptal edilen kayıt gerekçesi, iptal eden kişi ve zamanıyla saklanır, cirodan düşer ve ayrıca listelenebilir.

## Madde 6: Ödeme ve devir

6.1. Ödemeler [aşamalı] yapılır: [ödeme planı].

6.2. Kaynak kod ve mülkiyet, son ödemeyle Müşteriye devredilir. Devirden sonra lisans süresi ve geliştirici hesabıyla ilgili hükümler [Müşterinin tercihine göre] kaldırılabilir.

6.3. Sistemi Sağlayıcının barındırdığı modelde ödeme yapılmazsa hizmet **önceden [15 gün] yazılı bildirimle** askıya alınabilir. Askıya alma, Madde 2'deki okunur mod ile yapılır ve Madde 3'teki veri güvencesi geçerliliğini korur.

## Madde 7: Kişisel veriler (KVKK)

7.1. Sistem plaka, abone adı ve telefonu ile personel mesai verisini işler. Müşteri veri sorumlusu, Sağlayıcı veri işleyendir.

7.2. Performans puanı yalnızca Müşteri yöneticisine gösterilir, saha uygulamasında gösterilmez. Personele, mesai ve performans verisinin işlendiğine dair yazılı bilgilendirme yapılması Müşterinin sorumluluğundadır.

---

*Teknik ayrıntılar: `docs/api-asama5.md`. Bu ek, Sistemin [sürüm / commit] sürümündeki davranışını anlatır.*
