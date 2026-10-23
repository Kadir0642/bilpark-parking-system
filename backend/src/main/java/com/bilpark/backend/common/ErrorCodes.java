package com.bilpark.backend.common;

/** Error codes sent to clients in the "code" field of every error body. */
public final class ErrorCodes {
    private ErrorCodes() {}

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String INVALID_PLATE = "INVALID_PLATE";
    public static final String INVALID_PAYMENT_METHOD = "INVALID_PAYMENT_METHOD";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String VEHICLE_NOT_FOUND = "VEHICLE_NOT_FOUND";
    public static final String ALREADY_PARKED = "ALREADY_PARKED";         // aynı plaka sistemde aktif
    public static final String ALREADY_PROCESSED = "ALREADY_PROCESSED";   // araç az önce başka istekle çıkarıldı/kaçak işaretlendi
    public static final String CONFLICT = "CONFLICT";                     // genel eşzamanlılık / benzersizlik çakışması
    public static final String SUBSCRIPTION_EXISTS = "SUBSCRIPTION_EXISTS";
    public static final String NO_SUBSCRIPTION = "NO_SUBSCRIPTION";
    public static final String ALREADY_ARCHIVED = "ALREADY_ARCHIVED";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String INVALID_TARIFF = "INVALID_TARIFF";         // tarife alanları geçersiz
    public static final String TARIFF_STATE = "TARIFF_STATE";             // tarife bu durumda bu işleme izin vermiyor
    public static final String PASSWORD_MISMATCH = "PASSWORD_MISMATCH";   // yönetici şifresi tekrar doğrulanamadı
    public static final String SYNC_RESET_REQUIRED = "SYNC_RESET_REQUIRED";
}
