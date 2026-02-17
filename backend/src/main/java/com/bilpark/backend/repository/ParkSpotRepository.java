package com.bilpark.backend.repository;

import com.bilpark.backend.model.ParkSpot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository // Spring'e : ParkSpotRepository-> veritabanı işlerinden sorumlu der. (Dependency Injection) veritabanı hatalarını bizim dilimize çeviren bir Tercüman
public interface ParkSpotRepository extends JpaRepository<ParkSpot, Long>
{
    /*extends JpaRepository dediğimiz için miras ile
    *.save()     -> Kaydet
    * .findAll() -> Hepsini getir.
    * .delete()  -> Sil
    * .findById() -> ID ile bul
    */

    //SELECT * FROM park_spots WHERE street = 'Atatürk Caddesi';
    List<ParkSpot> findByStreet(String street); // Parametre de gelen sokak adını veritabanında arayıp park yerlerini liste olarak bana ver.
    List<ParkSpot> findByNeighborhood(String neighborhood); // Mahalle ye göre filtre yaparak park alanlarını verir
    List<ParkSpot> findByRegion(String region,String neighborhood); // Hem mahalle hem ilçe filtresi
}
