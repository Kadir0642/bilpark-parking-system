# 🅿️ BilPark - Yeni Nesil Otopark Yönetim Sistemi

![Language](https://img.shields.io/badge/Language-Java%2021-orange) ![Framework](https://img.shields.io/badge/Framework-Spring%20Boot%203-brightgreen) ![Database](https://img.shields.io/badge/Database-PostgreSQL%20(Neon)-blue) ![Mobile](https://img.shields.io/badge/Mobile-Flutter-blue) ![Status](https://img.shields.io/badge/status-Active%20Development-green.svg) ![License](https://img.shields.io/badge/license-MIT-blue.svg)

> **"Kağıt Yok, Donanım Yok, Sadece Kod"**: Şehir içi otopark yönetimini dijitalleştiren, pahalı donanım maliyetlerini (kiosk/bariyer) ortadan kaldıran, **Mobil Odaklı** ve **Bulut Tabanlı** akıllı otopark çözümüdür.

---

## 🎯 Proje Vizyonu (Vision)

**Problem:**
Geleneksel sistemlerdeki el terminalleri ve kağıt fişler; yağmurda ıslanır, kaybolur ve maliyetlidir. Ayrıca nakit para akışında kaçaklar oluşur ve denetim zordur. Saha personelinin operasyonel yükü fazladır.

**Çözüm:**
**BilPark**, fiziksel bilet yerine **Plaka Tanıma (OCR) ve QR** teknolojisini kullanır.
* **Backend (Beyin):** Java Spring Boot ile kurulan sağlam mimari, binlerce aracın giriş-çıkışını yönetir ve karmaşık fiyat hesaplamalarını yapar.
* **Veritabanı (Hafıza):** Veriler **Neon.tech (PostgreSQL)** bulut sunucularında güvenle saklanır.
* **Mobil (Saha):** Saha görevlileri, Flutter ile geliştirilmiş modern, sürükle-bırak destekli ve karanlık mod (Dark Mode) uyumlu mobil uygulama üzerinden operasyonu tek parmakla yönetir.

---

## 🚀 Öne Çıkan Özellikler

* **Kamera ile Plaka Okuma (OCR):** Google ML Kit entegrasyonu ile araç plakaları kameradan anında metne çevrilir.
* **Akıllı Saha Krokisi:** Araçlar sürükle-bırak (Drag & Drop) mantığıyla park yerlerine atanır. Boş yerler otomatik olarak üst sıralara taşınır (Dynamic Sorting).
* **Dinamik Araç Tipi Algılama:** Kamyonet/Ticari araçlar ile Standart otomobiller haritada farklı ikon ve renklerle (Turuncu/Kırmızı) görselleştirilir.
* **Vardiya ve Lokasyon Yönetimi:** Personel giriş ekranı (Auth) ve dinamik bölge seçimi ile her görevli sadece kendi bölgesini yönetir.
* **SOLID Mimari:** Hem Backend (Java) hem de Mobil (Flutter) tarafı, bakımı kolay ve ölçeklenebilir bir mimariyle kodlanmıştır.

---

## 🛠️ Teknoloji Yığını (Tech Stack)

Proje, "Software Architect" bakış açısıyla; ölçeklenebilir ve modüler bir yapı olarak tasarlanmıştır.

| Alan | Teknoloji | Açıklama |
| :--- | :--- | :--- |
| **Backend** | ☕ **Java 21 & Spring Boot 3** | Kurumsal standartlarda, yüksek performanslı REST API. |
| **Database** | 🐘 **PostgreSQL (Neon.tech)** | Serverless (Sunucusuz) Bulut Veritabanı Altyapısı. |
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

## 📂 Proje Yapısı (Monorepo)

```bash
bilpark-parking-system/
├── backend/                # Java Spring Boot API Sunucusu
│   ├── src/main/java/      
│   │   ├── model/          # Veritabanı Tabloları (ParkSpot, ParkingRecord vb.)
│   │   ├── repository/     # Veri Erişim Katmanı (Data Access Layer)
│   │   ├── service/        # İş Mantığı & Fiyat Hesaplama Motoru
│   │   └── controller/     # API Uç Noktaları (REST Endpoints)
├── mobile/                 # Flutter Mobil Uygulama
│   ├── lib/
│   │   ├── screens/        # SOLID'e uygun ayrılmış ekranlar (Auth, Dashboard, Map vb.)
│   │   └── main.dart       # Uygulama motoru ve Theme Management
└── README.md               # Proje Dokümantasyonu
```

---

## ⚙️ Kurulum ve Çalıştırma

Projeyi yerel ortamınızda test etmek için aşağıdaki adımları izleyin:
1. Repoyu Klonlayın

```bash
git clone [https://github.com/Kadir0642/bilpark-parking-system.git](https://github.com/Kadir0642/bilpark-parking-system.git)
cd bilpark-parking-system
```

2. Backend'i Başlatın (Java)
- Projeyi IntelliJ IDEA ile açın.

- pom.xml dosyasına sağ tıklayıp "Add as Maven Project" deyin.

- src/main/resources/application.properties dosyasına kendi Neon.tech PostgreSQL bilgilerinizi girin.

- BackendApplication.java dosyasını çalıştırın.

3. Mobil Uygulamayı Başlatın (Flutter)

- ```mobile``` klasörüne gidin.

- ```flutter pub get``` komutu ile bağımlılıkları yükleyin.

- Kendi yerel IP adresinizi veya Ngrok tünel adresinizi ```globalBaseUrl``` olarak ayarlayın.

- ```flutter run``` komutu ile uygulamayı başlatın.

---
## 🗺️ Yol Haritası (Roadmap)
[x] Faz 1: Monorepo Kurulumu & Spring Boot Başlangıcı

[x] Faz 2: Veritabanı Tasarımı (PostgreSQL Neon Geçişi)

[x] Faz 3: Repository Katmanı ve Data Seeding

[x] Faz 4: Service Katmanı (İş Mantığı & Akıllı Fiyat Hesaplama Motoru)

[x] Faz 5: Controller Katmanı (REST API'nin Dışa Açılması)

[x] Faz 6: Mobil Uygulama Geliştirme (Flutter, SOLID Mimarisi, UI/UX, Dark Mode)

[x] Faz 7: Kamera ile Plaka Okuma (OCR) ve Sürükle-Bırak Entegrasyonu

[ ] Faz 8: Backend Sunucusunun Buluta Taşınması (Cloud Deployment) 🚀 (ŞU AN BURADAYIZ)

[ ] Faz 9: Vatandaşlar İçin QR Kod ile Kendi Kendine Ödeme Sistemi

[ ] Faz 10: Canlı Saha Testler

---

Developed by Kadir Kacır

Copyright © 2026 Kadir Kacır. All Rights Reserved.