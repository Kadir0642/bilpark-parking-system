# 🅿️ BilPark - Yeni Nesil Otopark Yönetim Sistemi

![Java](https://img.shields.io/badge/Language-Java%2021-orange) ![Framework](https://img.shields.io/badge/Framework-Spring%20Boot%203-brightgreen) ![License](https://img.shields.io/badge/license-MIT-blue.svg) ![Status](https://img.shields.io/badge/status-Active%20Development-green.svg)

> **"Kağıt Yok, Donanım Yok, Sadece Kod"**: Şehir içi otopark yönetimini dijitalleştiren, pahalı donanım maliyetlerini (kiosk/bariyer) ortadan kaldıran, **Mobil Odaklı** ve **Bulut Tabanlı** belediye çözümüdür.

---

## 🎯 Proje Vizyonu (Vision)

**Problem:**
Geleneksel sistemlerdeki el terminalleri ve kağıt fişler; yağmurda ıslanır, kaybolur ve maliyetlidir. Ayrıca nakit para akışında kaçaklar oluşur ve denetim zordur.

**Çözüm:**
**BilPark**, fiziksel bilet yerine **Plaka ve QR** teknolojisini kullanır.
* **Backend (Beyin):** Java Spring Boot ile kurulan sağlam mimari, binlerce aracın giriş-çıkışını milisaniyeler içinde işler.
* **Mobil (Saha):** Sürücüler ve görevliler, telefonlarındaki uygulama üzerinden plaka okutarak ödeme ve kontrol yapar.
* **Maliyet Avantajı:** Pahalı ödeme otomatları yerine, her direkte bulunan basit bir QR kod ile ödeme alınır.

---

## 🛠️ Teknoloji Yığını (Tech Stack)

Proje, "Software Architect" bakış açısıyla; ölçeklenebilir ve modüler bir **Monorepo** olarak tasarlanmıştır.

| Alan | Teknoloji | Açıklama |
| :--- | :--- | :--- |
| **Backend** | ☕ **Java 21 & Spring Boot 3** | Kurumsal standartlarda, yüksek performanslı REST API. |
| **Database** | 🗄️ **H2 (Dev) / PostgreSQL** | Geliştirme için bellek içi, üretim için ilişkisel veritabanı. |
| **ORM** | 🍃 **Spring Data JPA** | SQL yazmadan veritabanı yönetimi (Hibernate). |
| **Mobile** | 💙 **Flutter (Dart)** | iOS & Android için tek kod tabanlı mobil uygulama. (Planlanan) |
| **Tools** | 🛠️ **Maven & Lombok** | Bağımlılık yönetimi ve temiz kod araçları. |

---

## 📂 Proje Yapısı (Monorepo)

```bash
bilpark-parking-system/
├── backend/                # Java Spring Boot API Sunucusu
│   ├── src/main/java/      # Kaynak Kodlar
│   │   ├── model/          # Veritabanı Tabloları (Entities)
│   │   ├── repository/     # Veri Erişim Katmanı
│   │   ├── service/        # İş Mantığı (Fiyat Hesaplama vb.)
│   │   └── config/         # Başlangıç Ayarları
│   └── pom.xml             # Maven Ayar Dosyası
├── mobile/                 # Flutter Mobil Uygulama (Yakında)
├── docs/                   # Mimari Çizimler ve Dokümanlar
└── README.md               # Proje Dokümantasyonu
```

## ⚡ Temel Özellikler (Key Features)

---
### ⚙️ Backend (Java API)

* Dinamik Tarife: Küçük ve Büyük araçlar için farklı fiyatlandırma (Örn: İlk 5 dk ücretsiz).

* Data Seeding: Uygulama her başladığında test verilerini (Örn: A-1 Park Yeri) otomatik yükler.

* Kağıtsız İşlem: Giriş ve Çıkışlar tamamen dijital loglanır.

### 📱 Mobil & Ödeme (Planlanan)
* QR ile Ödeme: Sürücü aracının başındaki QR kodu okutur, borcunu görür ve öder.

* Plaka Tanıma: Görevli plaka fotoğrafını çeker, sistem aracı otomatik tanır.

## ⚙️ Kurulum ve Çalıştırma

---
* Projeyi yerel ortamınızda test etmek için aşağıdaki adımları izleyin:

1. Repoyu Klonlayın

```bash
git clone [https://github.com/Kadir0642/bilpark-parking-system.git](https://github.com/Kadir0642/bilpark-parking-system.git)
cd bilpark-parking-system
```
2. Backend'i Başlatın 
* Projeyi IntelliJ IDEA ile açın (Sadece backend klasörünü açtığınızdan emin olun).
* pom.xml dosyasına sağ tıklayıp "Add as Maven Project" deyin.
* BackendApplication.java dosyasını çalıştırın (Run)

3. Veritabanını Kontrol Edin
* Tarayıcınızdan H2 Konsoluna gidin:
* URL: ``` http://localhost:8080/h2-console ```
* JDBC URL: ``` jdbc:h2:mem:bilparkdb ```
* User: ```admin```
* Password: ```123```
---
## 🗺️ Yol Haritası (Roadmap)
```bash
[x] Faz 1: Monorepo Kurulumu & Spring Boot Başlangıcı ✅

[x] Faz 2: Veritabanı Tasarımı (Entity & Enum Yapıları) ✅

[x] Faz 3: Repository Katmanı ve Test Verisi (Data Seeding) ✅

[ ] Faz 4: Service & Controller Katmanları (API yi Dışa Açmak) 🚧 ŞU AN BURADAYIZ

[ ] Faz 5: İş Mantığı (Fiyat Hesaplama Algoritması)

[ ] Faz 6: Mobil Uygulama Kurulumu (Flutter)

[ ] Faz 7: Entegrasyon ve Demo
```

