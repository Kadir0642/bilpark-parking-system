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

    @Enumerated(EnumType.STRING)
    private StreetLocation street; // Hangi caddede işlem yapıldı ?

    // Konum bilgileri (Snapshotting Pattern) Raporlama için buraya da kaydediyoruz )
    private String region;         // İlçe
    private String neighborhood;   // Mahalle

    // Zamanlama
    private LocalDateTime entryTime; //Giriş (araç girdiği an sistem saatini basar)
    private LocalDateTime exitTime;  //Cikis (icerideyken "null") cikarken o anki saati yazariz

    //Araç tipi
    @Enumerated(EnumType.STRING)
    private VehicleType vehicleType;

    //Ücret
    private Double fee; // Ödenen ücret

    @Enumerated(EnumType.STRING)
    private ParkingStatus status; // PAID (Ödedi) veya RUNAWAY (Kaçtı) olarak fişlenecek!

    // --- Constructers ---
    public ParkingRecord(){}   //Veri tabanından veriyi çekerken boş nesne oluşturulup , sonra içi doldurulur.

    // Bizim kullanacağımız
    public ParkingRecord(String licensePlate,StreetLocation street,String region, String neighborhood,LocalDateTime entryTime)
    {
            this.licensePlate=licensePlate;
            this.street=street;
            this.region=region;
            this.neighborhood = neighborhood;
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
        this.licensePlate=licensePlate;
    } // set methodu düzeltildi.

    public StreetLocation getStreet(){
        return street;
    } // Web panelinin caddeyi görebilmesi için
    public void setStreet(StreetLocation street){this.street=street;}

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

    public VehicleType getVehicleType(){return vehicleType;}
    public void setVehicleType(VehicleType vehicleType){this.vehicleType=vehicleType;}

    public Double getFee()
    {
        return fee;
    }
    public void setFee(Double fee)
    {
        this.fee=fee;
    }

    public ParkingStatus getStatus(){return status;}
    public void setStatus(ParkingStatus status){this.status=status;}

}
