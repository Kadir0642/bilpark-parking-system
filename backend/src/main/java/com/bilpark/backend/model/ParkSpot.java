package com.bilpark.backend.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

    // @ işaretleri altındaki variable,method,class için bir emir anlamında ne olacağını söylüyor
    //@Data -> Lombok: Getter,Setter,ToString otomatik yazar(Kod kalabalığı azaltır.)
    @Entity // Bu sınıf bir veri tabanı tablosudur. Bu sınıfı al, veritabanı diline (SQL) çevir.
    @Table(name="active_park_spots") // Veri tabanındaki tablo adı
    public class ParkSpot
    {
        @Id // tablonun "Primary Key", veritabanı sıra numarası
        @GeneratedValue(strategy=GenerationType.IDENTITY) // ID'yi biz değil ,veritabanı her yeni kayıtta sayacı 1 arttırsın. (Auto Increment).
        private Long id; // başlangıç değeri NULL

        /*Spring Boot'ta bir kaydın yeni olup olmadığını ID'sine bakarak anlarız.
        Eğer ID null ise "Bu yeni bir kayıt, kaydedeyim" der.
        (long-0) Eğer 0 olsaydı, "Acaba 0 numaralı kayıt mı, yoksa yeni mi?" diye karışıklık olur.*/

        @Column(unique=true, nullable=false)
        private String currentPlate; // Mobil uygulamada kayıt alınan plakaları tuttuğumuz alan bu sayede kayıtlı park kartında plaka kalıcı şekilde yazılabiliyor.

        @Enumerated(EnumType.STRING)// Aşağıdaki değişkeni veritabanına sayı olarak değil, YAZI olarak kaydet.
        @Column(nullable=false)
        private StreetLocation street; // Artık sadece bizim belirlediğimiz 3 cadde seçilebilir. (Tevfik| Ali Rıza | Cumhuriyet)

        private String region= "Merkez"; // İlçe
        private String neighborhood;   // Mahalle

        private String side; // ARAÇ sağ-sol kaldırım konum bilgisi

        private LocalDateTime entryTime; // Giriş Saati --> Mobil uygulamada kayıtlı park yerinde ne kadar süre durduğunu göstermek için

        @Enumerated(EnumType.STRING)
        private VehicleType currentType; // App üzerinde giren aracın tipinin tutulması için

        @Enumerated(EnumType.STRING)
        private ParkingStatus status= ParkingStatus.ACTIVE; // Sisteme giren araç varsayılan olarak AKTİF'tir

        //parametresiz constructer
        public ParkSpot(){}

        //Nesne oluştururken kolaylık için parametreli constructer
        public ParkSpot(String currentPlate,StreetLocation street ,VehicleType currentType,String region,String neighborhood,String side)
        {
            this.currentPlate=currentPlate;
            this.street=street;
            this.currentType=currentType;
            this.region=region;
            this.neighborhood=neighborhood;
            this.side=side;
            this.entryTime=LocalDateTime.now();
            this.status=ParkingStatus.ACTIVE;
        }


        //  --- GETTER & SETTER ---

        public Long getId()
        {
            return id;
        }
        public void setId(Long id)
        {
            this.id=id;
        }

        public String getCurrentPlate(){return currentPlate;}
        public void setCurrentPlate(String currentPlate){this.currentPlate=currentPlate;}

        public StreetLocation getStreet()
        {
            return street;
        }
        public void setStreet(StreetLocation street)
        {
            this.street=street;
        }

        public String getRegion() {return region;}
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
            this.neighborhood =neighborhood;
        }

        public String getSide(){return side;}
        public void setSide(String side){this.side=side;}

        public java.time.LocalDateTime getEntryTime(){
          return entryTime;
        }
        public void setEntryTime(java.time.LocalDateTime entryTime){
            this.entryTime=entryTime;
        }

        public VehicleType getCurrentType(){return currentType;}
        public void setCurrentType(VehicleType currentType){this.currentType=currentType;}

        public ParkingStatus getStatus(){return status;}
        public void setStatus(ParkingStatus status){this.status=status;}
    }

