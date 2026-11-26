# BilPark: proje talimatı

Bu dosya, repoda çalışan her yapay zeka agent'ının tek kaynağıdır (Claude Code okur; diğer agent'lar için bkz. AGENTS.md). Yöntem (orkestrasyon) agent'ın skill'indedir; bu dosya yalnızca BilPark'a özgü bilgiyi taşır. Kısa tutulur.

**Durum (26 Kasım 2026):** canlıda; main `0b017c8`; Flyway canlıda V9, main'de V10.

## 1. Ürün ve parçalar
Açık hava otopark takip sistemi (plaka girişi/çıkışı, tarife, tahsilat, vardiya, abonelik, borç sorgusu).
- `backend/`: Spring Boot 4, Java 21, Flyway, PostgreSQL. API sözleşmeleri `docs/api-*.md`, canlı belge `/swagger-ui.html`.
- `mobile/`: Flutter saha uygulaması (Android; plaka kamera okuması ML Kit). Flutter 3.50.1 (CI'da sabit).
- `web/`: statik yönetim paneli ve vatandaş QR borç sayfası (düz HTML/CSS/JS, GitHub Pages). `web-admin/` erken prototip, kullanılmıyor.
- Canlı: Render Free (Docker, `main`'den elle deploy) + Neon Free (Postgres). `/api/health` izlenir.
- Belge dizini: `docs/README.md`. Genel bakış: `README.md`.

## 2. Komutlar
- Backend test: `cd backend && ./mvnw -B test` (Docker varsa Testcontainers testleri de koşar, yoksa atlanır; atlananı "geçti" sayma).
- Saat dilimi: `TurkeyTimeZoneExtension` her test sınıfından önce JVM'i Europe/Istanbul yapar (üretimle aynı); makine UTC olsa bile testler İstanbul'da koşar. Gerçek UTC koşusu için eklentiyi kapat: `./mvnw -B test -DargLine="-Duser.timezone=UTC -Djunit.jupiter.extensions.autodetection.enabled=false"`. Toplam test sayısını ayrıca raporla ("çıkış 0" yetmez).
- Flutter: `cd mobile && flutter pub get && flutter analyze && flutter test`. CI yalnızca `flutter analyze` koşar.
- İmzalı release APK: `BILPARK_KEY_PROPERTIES` ortam değişkeni anahtar özellik dosyasını gösterir, ardından `flutter build apk --release`. Ayrıntı `docs/android-imzalama.md`. Anahtar ve özellik dosyası ASLA commit'lenmez, çıktıda/raporda yazılmaz. Telefona kurarken eski uygulamanın ÜZERİNE güncelle (silip kurma yeni cihaz kimliği üretir).
- Web paneli yerelde: `python -m http.server 5500 -d web`, sonra `admin-panel.html?api=http://localhost:8080`. Tam yığın: `docs/yerel-demo.md`.
- CI: `maven.yml` (`mvn -B package`, JDK 21) ve `flutter.yml` (analyze). İkisi de yeşil olmadan birleştirme istenmez.

## 3. Çalışma kuralları
- Orkestrasyon skill'i kullanılır: orkestratör planlar, alt agent'lar iş yapar.
- Her agent kendi worktree'sinde ve kendi dalında (`claude/<konu>`) çalışır; başkasının dalına/dosyasına dokunmaz, dosya sahipliği brief'te yazılıdır.
- PR'ı yalnızca kullanıcı birleştirir. Agent'lar asla birleştirmez, `main`'e doğrudan push etmez.
- Her PR'ı yazandan FARKLI bir model inceler; düzeltmeler yazara geri gider.
- Erken birleştirilmiş bir PR'ın düzeltmesi, güncel `main`'den açılan yeni dalda ve yeni PR'da yapılır.
- Push'tan önce `git fetch origin` ve `origin/main`'i dala birleştir. Commit'ler sık atılır.
- Commit mesajı Türkçe; sonuna imza satırı: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (kullanılan modele göre ad değişir). PR gövdesi `🤖 Generated with [Claude Code](https://claude.com/claude-code)` ile biter.
- Şema değişikliği yeni Flyway migration'ıdır; uygulanmış olan asla düzenlenmez. Davranış değişikliğine test eklenir.
- Ücretli hiçbir şey (hesap, plan, satın alma) kullanıcı onayı olmadan kullanılmaz.
- Kabuk komutlarında ters tırnak kullanma. Sahte sırlar `test-secret-…` önekiyle başlar.
- Raporlar Türkçe ve kısa; test sayıları açık yazılır.

## 4. Korunan kaynaklar (agent'lar dokunmaz)
- Canlı Render servisi, Neon veritabanı ve UptimeRobot: bağlanma, deploy etme, ayar değiştirme yok. Deploy'u yalnızca kullanıcı yapar.
- Kullanıcının yerel demo konteynerleri (başlatma, durdurma, silme, veritabanına yazma yok) ve başka projelerin konteynerleri. Test için kendi geçici Testcontainers'ın serbesttir; ayrı proje adı ve boş portlar kullan, bitince `down -v`.
- Kullanıcının kişisel telefonu: `adb shell input` ve `monkey` YASAK. Yalnızca kurulum, `adb reverse`, `logcat` ve `flutter test integration_test`.
- Ana checkout (repo kök klasörü): kullanıcının commit'lenmemiş değişiklikleri olabilir. Orada çalışma; worktree kullan.
- Çalışan başka bir agent'ın ya da demonun kullandığı worktree silinmez.

## 5. Deploy prosedürü (kullanıcı yapar)
1. Neon'da yedek dal aç (ör. `yedek-v<N>-<tarih>`).
2. Render'da Manual Deploy (Auto-Deploy kapalı, `main`'den).
3. Deploy log'unda Flyway satırını gör: "Successfully applied N migration(s) ... vXX".
4. `/api/health` UP olmalı.
5. Geliştirici sayfasında "Flyway sürümü" beklenen numarayı göstermeli.

İlk geçiş ayrıntıları: `docs/1-kasim-canliya-gecis.md`. Mobil değişiklikler APK ile gider, deploy gerektirmez.

## 6. Numara kaydı
- Main'de uygulanmış son Flyway migration: V10 (yabancı plaka). Canlıda uygulanmış: V9.
- Sıradaki boş numara: V11 (yabancı plakada ülke kodu için AYRILDI). Numaralar brief'te önceden dağıtılır; kullanılmazsa sıradaki iş alır.
- Yeni migration eklenince bu bölüm ve Durum satırı aynı PR'da güncellenir.

## 7. Herkese açıklık kuralları
Repo herkese açıktır; lisans "Tüm hakları saklı" (`LICENSE`). Şunlar repoya, PR'a, commit mesajına ve raporlara YAZILMAZ:
- müşteri/firma adı, sözleşme, fiyat ve pazarlık bilgisi;
- gerçek sır, parola, anahtar, kişisel veri, gerçek plaka;
- yerel kullanıcı yolları, makine adları;
- kapanmamış güvenlik zayıflığının ayrıntısı (kullanıcıya özel bildirilir).

Demo verisi kurgusaldır.

## 8. Yerel bilgiler
Yerel dosya yolları, not klasörü, imza anahtarının yeri ve çalışan agent'ların durumu bu repoda YOKTUR. Orkestratörün kalıcı hafızasında ve kullanıcının yerel not klasöründe tutulur; agent bunları orkestratörün brief'inden alır.
