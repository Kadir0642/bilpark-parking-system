package com.bilpark.backend.model;

import jakarta.persistence.*;

    // @ işaretleri altındaki variable,method,class için bir emir anlamında ne olacağını söylüyor
    //@Data -> Lombok: Getter,Setter,ToString otomatik yazar(Kod kalabalığı azaltır.)
    @Entity // Bu sınıf bir veri tabanı tablosudur. Bu sınıfı al, veritabanı diline (SQL) çevir.
    @Table(name="park_spots") // Veri tabanındaki tablo adı
    public class ParkSpot
    {
        @Id // tablonun "Primary Key", veritabanı sıra numarası
        @GeneratedValue(strategy=GenerationType.IDENTITY) // ID'yi biz değil ,veritabanı her yeni kayıtta sayacı 1 arttırsın. (Auto Increment).
        private Long id; // başlangıç değeri NULL

        /*Spring Boot'ta bir kaydın yeni olup olmadığını ID'sine bakarak anlarız.
        Eğer ID null ise "Bu yeni bir kayıt, kaydedeyim" der.
        (long-0) Eğer 0 olsaydı, "Acaba 0 numaralı kayıt mı, yoksa yeni mi?" diye karışıklık olur.*/

        @Column(unique = true, nullable =false) // Park yeri numarası (A-5 gibi) eşşiz olmalı ve boş olamaz.
        private String spotName;

        @Column(nullable=false)
        private String region; //İlçe(Örn:Merkez)

        @Column(nullable=false)
        private String neighborhood; //Mahalle (Örn: Bahçelievler)

        @Column(nullable=false)
        private String street; // Cadde/Sokak (Örn: Atatürk Cad.)

        private boolean isOccupied =false; // Dolu mu ? (Varsayılan olarak boş başlasın)

        @Enumerated(EnumType.STRING) // Aşağıdaki değişkeni veritabanına sayı olarak değil, YAZI olarak kaydet.
        private VehicleType suitableFor; // Bu park yeri hangi araç tipine uygun (SMALL || LARGE)

        //parametresiz constructer
        public ParkSpot(){}

        //Nesne oluştururken kolaylık için parametreli constructer
        public ParkSpot(String spotName,VehicleType suitableFor,boolean isOccupied,String region,String street,String neighborhood)
        {
            this.spotName=spotName;
            this.region=region;
            this.street=street;
            this.neighborhood=neighborhood;
            this.suitableFor=suitableFor;
            this.isOccupied=isOccupied;
        }
        public boolean isOccupied() // Dolu mu değil mi ona bakmak için
        {
            return isOccupied;
        }
        public void setOccupied(boolean occupied)
        {
            this.isOccupied=occupied;
        }

        // GETTER & SETTER
        public Long getId()
        {
            return id;
        }
        public void setId(Long id)
        {
            this.id=id;
        }

        public String getSpotName()
        {
            return spotName;
        }
        public void setSpotName(String spotName)
        {
            this.spotName=spotName;
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

        public String getStreet()
        {
            return street;
        }
        public void setStreet(String street)
        {
            this.street=street;
        }

        public VehicleType getSuitableFor() {return suitableFor;}
        public void setSuitableFor(VehicleType suitableFor)
        {
            this.suitableFor=suitableFor;
        }
    }

