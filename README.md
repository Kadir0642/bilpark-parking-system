# 🅿️ BilPark - Yeni Nesil Otopark Yönetim Sistemi

![Language](https://img.shields.io/badge/Language-Java%2021-orange) ![Framework](https://img.shields.io/badge/Framework-Spring%20Boot%203-brightgreen) ![Database](https://img.shields.io/badge/Database-PostgreSQL%20(Neon)-blue) ![License](https://img.shields.io/badge/license-MIT-blue.svg) ![Status](https://img.shields.io/badge/status-Active%20Development-green.svg)

> **"Kağıt Yok, Donanım Yok, Sadece Kod"**: Şehir içi otopark yönetimini dijitalleştiren, pahalı donanım maliyetlerini (kiosk/bariyer) ortadan kaldıran, **Mobil Odaklı** ve **Bulut Tabanlı** belediye çözümüdür.

---

## 🎯 Proje Vizyonu (Vision)

**Problem:**
Geleneksel sistemlerdeki el terminalleri ve kağıt fişler; yağmurda ıslanır, kaybolur ve maliyetlidir. Ayrıca nakit para akışında kaçaklar oluşur ve denetim zordur.

**Çözüm:**
**BilPark**, fiziksel bilet yerine **Plaka ve QR** teknolojisini kullanır.
* **Backend (Beyin):** Java Spring Boot ile kurulan sağlam mimari, binlerce aracın giriş-çıkışını yönetir ve karmaşık fiyat hesaplamalarını yapar.
* **Veritabanı (Hafıza):** Veriler yerel bilgisayarda değil, **Neon.tech (PostgreSQL)** bulut sunucularında güvenle saklanır.
* **Mobil (Saha):** Sürücüler ve görevliler, telefonlarındaki uygulama üzerinden plaka okutarak ödeme ve kontrol yapar.

---

## 🛠️ Teknoloji Yığını (Tech Stack)

Proje, "Software Architect" bakış açısıyla; ölçeklenebilir ve modüler bir **Monorepo** olarak tasarlanmıştır.

| Alan | Teknoloji | Açıklama |
| :--- | :--- | :--- |
| **Backend** | ☕ **Java 21 & Spring Boot 3** | Kurumsal standartlarda, yüksek performanslı REST API. |
| **Database** | 🐘 **PostgreSQL (Neon.tech)** | Serverless (Sunucusuz) Bulut Veritabanı Altyapısı. |
| **ORM** | 🍃 **Spring Data JPA** | SQL yazmadan veritabanı yönetimi (Hibernate). |
| **Mobile** | 💙 **Flutter (Dart)** | iOS & Android için tek kod tabanlı mobil uygulama. (Geliştirme Aşamasında) |
| **Tools** | 🛠️ **Maven & Lombok** | Bağımlılık yönetimi ve temiz kod araçları. |

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
│   ├── src/main/java/      # Kaynak Kodlar
│   │   ├── model/          # Veritabanı Tabloları (ParkSpot, ParkingRecord)
│   │   ├── repository/     # Veri Erişim Katmanı
│   │   ├── service/        # İş Mantığı (Fiyat Hesaplama Motoru buradadır)
│   │   ├── controller/     # API Uç Noktaları (Rest Controller)
│   │   └── config/         # Başlangıç Ayarları
│   └── pom.xml             # Maven Ayar Dosyası
├── mobile/                 # Flutter Mobil Uygulama (Yakında)
└── README.md               # Proje Dokümantasyonu
```
## ⚙️ Kurulum ve Çalıştırma
Projeyi yerel ortamınızda test etmek için aşağıdaki adımları izleyin:

1. Repoyu Klonlayın

```bash
git clone [https://github.com/Kadir0642/bilpark-parking-system.git](https://github.com/Kadir0642/bilpark-parking-system.git)
cd bilpark-parking-system
```

2. Veritabanı Ayarları

- Proje Neon.tech (PostgreSQL) kullanmaktadır.

```src/main/resources/application.properties```
dosyasına kendi veritabanı bilgilerinizi girmelisiniz.
  Backend'i Başlatın

3. Projeyi IntelliJ IDEA ile açın.

- ```pom.xml``` dosyasına sağ tıklayıp "Add as Maven Project" deyin.

- ```BackendApplication.java``` dosyasını çalıştırın (Run).
---
## 🗺️ Yol Haritası (Roadmap)
```bash
[x] Faz 1: Monorepo Kurulumu & Spring Boot Başlangıcı ✅
[x] Faz 2: Veritabanı Tasarımı (PostgreSQL Gecisi) ✅
[x] Faz 3: Repository Katmanı ve Data Seeding ✅
[x] Faz 4: Service Katmanı (Is Mantigi & Fiyat Hesaplama) ✅
[ ] Faz 5: Controller Katmanı (API'yi Dışa Açmak) 🚧 ŞU AN BURADAYIZ
[ ] Faz 6: Mobil Uygulama Kurulumu (Flutter)
[ ] Faz 7: Entegrasyon ve Demo
```

Developed by Kadir Kacır

Copyright (c) 2026 Kadir Kacır. All Rights Reserved.
