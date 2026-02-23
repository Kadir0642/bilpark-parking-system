# 🅿️ BilPark - Yeni Nesil Otopark Yönetim Sistemi

![Language](https://img.shields.io/badge/Language-Java%2021-orange) ![Framework](https://img.shields.io/badge/Framework-Spring%20Boot%203-brightgreen) ![Database](https://img.shields.io/badge/Database-PostgreSQL%20(Neon)-blue) ![Mobile](https://img.shields.io/badge/Mobile-Flutter-blue) ![Hosting](https://img.shields.io/badge/Hosting-Render-purple) ![Status](https://img.shields.io/badge/status-Active%20Development-green.svg) ![License](https://img.shields.io/badge/license-MIT-blue.svg)

> **"Kağıt Yok, Donanım Yok, Sadece Kod"**: Şehir içi otopark yönetimini dijitalleştiren, pahalı donanım maliyetlerini (kiosk/bariyer) ortadan kaldıran, **Mobil Odaklı** ve **%100 Bulut Tabanlı** akıllı otopark çözümüdür.

---

## 🎯 Proje Vizyonu (Vision)

**Problem:**
Geleneksel sistemlerdeki el terminalleri ve kağıt fişler; yağmurda ıslanır, kaybolur ve maliyetlidir. Ayrıca nakit para akışında kaçaklar oluşur ve denetim zordur. Saha personelinin operasyonel yükü fazladır.

**Çözüm:**
**BilPark**, fiziksel bilet yerine **Plaka Tanıma (OCR) ve QR** teknolojisini kullanır.
* **Backend (Beyin):** Java Spring Boot ile kurulan sağlam mimari, binlerce aracın giriş-çıkışını yönetir ve karmaşık fiyat hesaplamalarını yapar. Şu an aktif olarak Almanya lokasyonlu **Render** bulut sunucularında 7/24 hizmet vermektedir.
* **Veritabanı (Hafıza):** Veriler **Neon.tech (PostgreSQL)** serverless bulut mimarisinde güvenle saklanır.
* **Mobil (Saha):** Saha görevlileri, Flutter ile geliştirilmiş modern, sürükle-bırak destekli ve karanlık mod (Dark Mode) uyumlu mobil uygulama üzerinden operasyonu tek parmakla yönetir. Cihaz bağımsızdır, internetin olduğu her yerden merkeze bağlanır.

---

## 🚀 Öne Çıkan Özellikler

* **7/24 Kesintisiz Bulut Entegrasyonu:** Backend sunucusu Render üzerinde yayındadır ve *UptimeRobot* entegrasyonu ile uyku moduna geçmeden kesintisiz hizmet verir. Zaman dilimi (Timezone) otomatik olarak UTC+3 (Türkiye) standartlarına senkronize edilmiştir.
* **Kamera ile Plaka Okuma (OCR):** Google ML Kit entegrasyonu ile araç plakaları kameradan anında metne çevrilir.
* **Akıllı Saha Krokisi:** Araçlar sürükle-bırak (Drag & Drop) mantığıyla park yerlerine atanır. Boş yerler otomatik olarak üst sıralara taşınır (Dynamic Sorting).
* **Dinamik Araç Tipi Algılama:** Kamyonet/Ticari araçlar ile Standart otomobiller haritada farklı ikon ve renklerle (Turuncu/Kırmızı) görselleştirilir.
* **Vardiya ve Lokasyon Yönetimi:** Personel giriş ekranı (Auth) ve dinamik bölge seçimi ile her görevli sadece kendi bölgesini yönetir.
* **SOLID Mimari:** Hem Backend (Java) hem de Mobil (Flutter) tarafı, bakımı kolay ve ölçeklenebilir bir mimariyle kodlanmıştır.

---



[Image of cloud computing architecture]


## 🛠️ Teknoloji Yığını (Tech Stack)

Proje, "Software Architect" bakış açısıyla; ölçeklenebilir ve modüler bir yapı olarak tasarlanmıştır.

| Alan | Teknoloji | Açıklama |
| :--- | :--- | :--- |
| **Backend** | ☕ **Java 21 & Spring Boot 3** | Kurumsal standartlarda, yüksek performanslı REST API. |
| **Database** | 🐘 **PostgreSQL (Neon.tech)** | Serverless (Sunucusuz) Bulut Veritabanı Altyapısı. |
| **Cloud/DevOps**| ☁️ **Render & UptimeRobot** | CI/CD süreçleri ile otomatik canlıya alma ve kesintisiz sunucu yönetimi. |
| **ORM** | 🍃 **Spring Data JPA** | SQL yazmadan veritabanı yönetimi (Hibernate). |
| **Mobile** | 💙 **Flutter (Dart)** | iOS & Android için State Management ve SOLID prensipleriyle kodlanmış mobil uygulama. |
| **Tools** | 🛠️ **Google ML Kit, Maven, Lombok** | Görüntü işleme, bağımlılık yönetimi ve temiz kod araçları. |

---

## 💰 Fiyatlandırma Algoritması (Business Logic)

Sistem, belediye tarifelerine uygun olarak ücreti **otomatik** hesaplar. Şu anki aktif algoritma:

| Kural | Açıklama |
| :--- | :--- |
| **İlk 5 Dakika** | **ÜCRETSİZ** (Giriş-Çıkış yapanlardan ücret alınmaz) |
| **Küçük Araç** | İlk 1 Saat: **25.00 TL** <br> Sonraki Her Saat: **+15.00 TL** |
| **Büyük Araç** | İlk 1 Saat: **50.00 TL** <br> Sonraki Her Saat: **+30.00 TL** |

> *Not: Süre hesaplamasında 1 saati geçen her dakika, yukarı yuvarlanarak tam saat ücreti alınır.*

---

## ⚙️ Kurulum ve Çalıştırma

Proje canlı bulut sunucularına bağlandığı için mobil uygulamayı doğrudan telefonunuzda derleyip kullanabilirsiniz.

### Seçenek 1: Canlı Ortamı Test Etme (Sadece Mobil)
Sadece mobil arayüzü test etmek ve doğrudan canlı sunucuya bağlanmak için:
1. `mobile` klasörüne gidin.
2. `flutter pub get` komutu ile bağımlılıkları yükleyin.
3. Cihazınızı bağlayın ve `flutter run` komutu ile saha operasyonlarına anında başlayın. (Herhangi bir local sunucu çalıştırmanıza gerek yoktur).

### Seçenek 2: Geliştirici Ortamı (Local Backend & Database)
Sistemin beynine (Java) müdahale etmek ve geliştirmek isterseniz:
1. Repoyu klonlayın ve backend klasörünü IntelliJ IDEA ile açın.
2. `pom.xml` dosyasına sağ tıklayıp "Add as Maven Project" deyin.
3. `src/main/resources/application.properties` dosyasına kendi Neon.tech PostgreSQL bilgilerinizi girin.
4. `BackendApplication.java` dosyasını çalıştırın.
5. Mobil uygulamadaki `globalBaseUrl` değişkenini kendi yerel IP adresinize yönlendirin.

---
## 🗺️ Yol Haritası (Roadmap)

- [x] Faz 1: Monorepo Kurulumu & Spring Boot Başlangıcı
- [x] Faz 2: Veritabanı Tasarımı (PostgreSQL Neon Geçişi)
- [x] Faz 3: Repository Katmanı ve Data Seeding
- [x] Faz 4: Service Katmanı (İş Mantığı & Akıllı Fiyat Hesaplama Motoru)
- [x] Faz 5: Controller Katmanı (REST API'nin Dışa Açılması)
- [x] Faz 6: Mobil Uygulama Geliştirme (Flutter, SOLID Mimarisi, UI/UX, Dark Mode)
- [x] Faz 7: Kamera ile Plaka Okuma (OCR) ve Sürükle-Bırak Entegrasyonu
- [x] Faz 8: Backend Sunucusunun Buluta Taşınması (Render & UptimeRobot Entegrasyonu)
- [ ] Faz 9: Vatandaşlar İçin QR Kod ile Kendi Kendine Ödeme Sistemi 🚀 **(ŞU AN BURADAYIZ)**
- [ ] Faz 10: Canlı Saha Testleri ve Performans Optimizasyonu

---

Developed by Kadir Kacır

Copyright © 2026 Kadir Kacır. All Rights Reserved.