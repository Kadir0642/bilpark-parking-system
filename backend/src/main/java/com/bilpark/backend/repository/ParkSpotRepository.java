package com.bilpark.backend.repository;

import com.bilpark.backend.model.ParkSpot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository // Spring'e : ParkSpotRepository-> veritabanı işlerinden sorumlu der. (Dependency Injection) veritabanı hatalarını bizim dilimize çeviren bir Tercüman
public interface ParkSpotRepository extends JpaRepository<ParkSpot, Long>
{
    /*extends JpaRepository dediğimiz için miras ile
    *.save()     -> Kaydet
    * .findAll() -> Hepsini getir.
    * .delete()  -> Sil
    * .findById() -> ID ile bul
    */
}
