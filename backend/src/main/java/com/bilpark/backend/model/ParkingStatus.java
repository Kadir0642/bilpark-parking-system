package com.bilpark.backend.model;

public enum ParkingStatus {
    ACTIVE,     //Şu an caddede park halinde
    PAID,       // Ödemesini yaptı , normal çıkış yaptı.
    RUNAWAY     // ÖDEMEDEN KAÇTI ! (Kara Liste)
}
