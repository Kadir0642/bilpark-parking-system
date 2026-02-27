package com.bilpark.backend.repository;

import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.ParkingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

//Park yeri filtreleri/büyüklüğü ile ilgilenir.
@Repository // Spring'e : ParkSpotRepository-> veritabanı işlerinden sorumlu der. (Dependency Injection) veritabanı hatalarını bizim dilimize çeviren bir Tercüman
public interface ParkSpotRepository extends JpaRepository<ParkSpot, Long>
{
    /*extends JpaRepository dediğimiz için miras ile
    *.save()     -> Kaydet
    * .findAll() -> Hepsini getir.
    * .delete()  -> Sil
    * .findById() -> ID ile bul
    */

    // 1. Plakaya göre sokaktaki aracı bul (Çifte kayıt güvenliği ve Çıkış işlemi için)
    //Plakaya göre dolu park yerlerini  bul (büyük/küçük fark etmeksizin) | Optional -> (Null kontrolleri daha güvenli ve okunabilir olsun diye)Bir değerin mevcut olup olmadığını belirtmek için kullanılır.
    Optional<ParkSpot> findByCurrentPlateIgnoreCase(String currentPlate);

    // 2. Sadece belirli bir caddedeki aktif araçları getir. (Enum kullanıyoruz !)
    //SELECT * FROM park_spots WHERE street = 'Atatürk Caddesi';
    List<ParkSpot> findByStreet(StreetLocation street); // Parametre de gelen sokak adını veritabanında arayıp park yerlerini liste olarak bana ver.

    // 3. Cadde, Mahalle ve İlçe filtreli arama
    List<ParkSpot> findByRegionAndNeighborhoodAndStreet(String region, String neighborhood, StreetLocation street);
    List<ParkSpot> findByRegionAndNeighborhood(String region, String neighborhood);// İlçe,mahalle filtresi (Daha geniş arama)

    // 4. Belirli bir durumda olan araçları getir. (Örn: Sadece ACTIVE olanlar)
    List<ParkSpot> findByStatus(ParkingStatus status);

}
