package com.bilpark.backend.repository;

import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.ParkingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    // 1. Plakaya göre aktif aracı bul. Plaka her zaman PlateNormalizer ile normalize edilmiş gelir.
    Optional<ParkSpot> findByCurrentPlate(String currentPlate);

    // 1.1 Aynı satırı kilitleyerek oku (SELECT ... FOR UPDATE). Giriş, çıkış ve kaçak işlemleri bunu
    // @Transactional içinde kullanır; aynı araca gelen ikinci istek ilk işlem bitene kadar bekler.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ParkSpot p WHERE p.currentPlate = :plate")
    Optional<ParkSpot> findByPlateForUpdate(@Param("plate") String plate);

    // 2. Sadece belirli bir caddedeki aktif araçları getir. (Enum kullanıyoruz !)
    //SELECT * FROM park_spots WHERE street = 'Atatürk Caddesi';
    List<ParkSpot> findByStreet(StreetLocation street); // Parametre de gelen sokak adını veritabanında arayıp park yerlerini liste olarak bana ver.

    // 3. Cadde, Mahalle ve İlçe filtreli arama
    List<ParkSpot> findByRegionAndNeighborhoodAndStreet(String region, String neighborhood, StreetLocation street);
    List<ParkSpot> findByRegionAndNeighborhood(String region, String neighborhood);// İlçe,mahalle filtresi (Daha geniş arama)

    // 4. Belirli bir durumda olan araçları getir. (Örn: Sadece ACTIVE olanlar)
    List<ParkSpot> findByStatus(ParkingStatus status);

}
