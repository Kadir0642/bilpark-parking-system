# 🚗 BilPark - Akıllı Otopark Yönetim Sistemi

> "Sadece kod yazmıyoruz, şehir yaşamını optimize eden bir mimari kuruyoruz."

* BilPark, geleneksel otopark sorunlarını (kağıt bilet, nakit ödeme, gişe sırası) ortadan kaldıran; **Kağıtsız (Paperless)** ve **Mobil Odaklı** yeni nesil bir otopark yönetim ekosistemidir.

## 🌟 Proje Vizyonu

Amaç sadece çalışan bir uygulama yapmak değil; **Java Spring Boot** ekosisteminde kurumsal standartlarda bir altyapı kurmaktır.

## 🏗️ Mimari Yapı (Monorepo)

Proje, tüm ekosistemi tek bir çatı altında toplayan **Monorepo** yapısındadır:

* **`/backend`**: Sistemin beyni. Java 21 & Spring Boot 3.
* **`/mobile`**: (Yakında) Flutter tabanlı sürücü uygulaması.
* **`/docs`**: Mimari çizimler ve veritabanı şemaları.

## 🛠️ Teknoloji Yığını (Tech Stack)

| Alan | Teknoloji | Neden? |
| :--- | :--- | :--- |
| **Dil** | Java 21 (LTS) | Yüksek performans ve kurumsal standart. |
| **Framework** | Spring Boot 3 | Hızlı geliştirme ve güçlü ekosistem. |
| **Veritabanı** | H2 (Dev) / PostgreSQL (Prod) | Geliştirme kolaylığı ve ilişkisel veri gücü. |
| **ORM** | Spring Data JPA (Hibernate) | Veritabanı bağımsız kodlama. |
| **Araçlar** | Lombok, Maven, Git | Verimlilik ve sürüm kontrolü. |

## 💰 Fiyatlandırma Politikası
![img_2.png](img_2.png)

Sistem, araç tiplerine göre dinamik tarife uygular:
* **İlk 5 Dakika:** ÜCRETSİZ ⚡
* **Küçük Araçlar:** 25.00 TL (İlk saat) + 15.00 TL/Saat
* **Büyük Araçlar:** 50.00 TL (İlk saat) + 30.00 TL/Saat

## 🚀 Kurulum (Nasıl Çalıştırılır?)

1.  Repoyu klonlayın:
    ```bash
    git clone [https://github.com/Kadir0642/bilpark-parking-system.git](https://github.com/Kadir0642/bilpark-parking-system.git)
    ```
2.  Backend klasörüne gidin ve projeyi IntelliJ IDEA ile açın.
3.  `BackendApplication.java` dosyasını çalıştırın.
4.  H2 Veritabanı Konsolu için: `http://localhost:8080/h2-console`
    * **User:** `a`
    * **Pass:** `123`

---
*Geliştirici: [Kadir Kacır] | 2026*