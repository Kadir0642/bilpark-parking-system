package com.bilpark.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity // Veritabaninda bir karsiligi var(kalici hafızada)
@Table(name="parking_records") // veritabanindaki adi
public class ParkingRecord
{
    @Id // Primary Key , veritabaninda ki numarasi
    @GeneratedValue(strategy=GenerationType.IDENTITY) //Veritabani id atamasi yapicak(auto Increment)
    private Long id;

    private String licensePlate; // Arac plakası

    // Konum bilgileri (Snapshotting Pattern) Raporlama için buraya da kaydediyoruz )
    private String region;         // İlçe
    private String neighborhood;   // Mahalle
    private String street;        // Cadde/Sokak
    private String spotName;      // Park yeri | Örn: A-1 (Hangi kutuya park etti?)

    // Zamanlama
    private LocalDateTime entryTime; //Giriş (araç girdiği an sistem saatini basar)
    private LocalDateTime exitTime;  //Cikis (icerideyken "null") cikarken o anki saati yazariz

    //Ücret
    private Double fee; // Ödenen ücret

    // --- Constructers ---
    public ParkingRecord(){}   //Veri tabanından veriyi çekerken boş nesne oluşturulup , sonra içi doldurulur.

    //Bizim kullanacağımız
    public ParkingRecord(String licensePlate,String region, String neigborhood,String street, LocalDateTime entryTime)
    {
            this.licensePlate=licensePlate;
            this.region=region;
            this.neighborhood=neighborhood;
            this.street=street;
            this.entryTime=entryTime;
    }

    //GETTER && SETTER
    public Long getId()
    {
        return id;
    } // set yok (ID degistirilemez)çünkü onu veritabanı zaten yapıyor.

    public String getLicensePlate()
    {
        return licensePlate;
    }
    public void setLicensePlate(String licensePlate)
    {
        this.licensePlate=this.licensePlate;
    }

    public String getRegion()
    {
        return region;
    }
    public void setRegion(String region)
    {
        this.region=region;
    }

    public String getNeighborhood()
    {
        return neighborhood;
    }
    public void setNeighborhood(String neighborhood)
    {
        this.neighborhood=neighborhood;
    }

    public String getStreet()
    {
        return street;
    }
    public void setStreet(String street)
    {
        this.street=street;
    }

    public String getSpotName()
    {
        return spotName;
    }
    public void setSpotName(String spotName)
    {
        this.spotName=spotName;
    }

    public LocalDateTime getEntryTime()
    {
        return entryTime;
    }
    public void setEntryTime(LocalDateTime entryTime)
    {
        this.entryTime=entryTime;
    }

    public LocalDateTime getExitTime()
    {
        return exitTime;
    }
    public void setExitTime(LocalDateTime exitTime){
        this.exitTime=exitTime;
    }

    public Double getFee()
    {
        return fee;
    }
    public void setFee(Double fee)
    {
        this.fee=fee;
    }
}
