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

    // Aşama 5
    public static final String READ_ONLY = "READ_ONLY";                   // lisans ve ek süre doldu: okunur mod (423)
    public static final String WEAK_PASSWORD = "WEAK_PASSWORD";           // şifre kurallara uymuyor
    public static final String USERNAME_TAKEN = "USERNAME_TAKEN";
    public static final String PROTECTED_ACCOUNT = "PROTECTED_ACCOUNT";   // bu hesap bu uç noktayla değiştirilemez
    public static final String ALREADY_CANCELLED = "ALREADY_CANCELLED";
    public static final String INVALID_SETTINGS = "INVALID_SETTINGS";

    // Araç tipleri
    public static final String UNKNOWN_VEHICLE_TYPE = "UNKNOWN_VEHICLE_TYPE";
    public static final String VEHICLE_TYPE_INACTIVE = "VEHICLE_TYPE_INACTIVE"; // tip tarifede etkin değil

    // Idempotent giriş
    public static final String INVALID_REQUEST_ID = "INVALID_REQUEST_ID";   // checkInRequestId biçimi geçersiz
    public static final String DUPLICATE_REQUEST = "DUPLICATE_REQUEST";     // aynı istek daha önce işlendi, kayıt artık aktif değil
}
