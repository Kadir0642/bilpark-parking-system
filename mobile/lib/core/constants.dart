/// BilPark App - Global Constants
/// All shared configuration values are defined here.
/// To switch between environments, only this file needs to be updated.

class AppConstants {
  AppConstants._(); // Prevent instantiation

  // --- API ---
  static const String apiBase = "http://10.0.2.2:8080/api";
  static const String baseUrl = "$apiBase/parking";

  // --- Street Enum Mapping ---
  static const Map<String, String> streetToEnum = {
    'Tevfik Bey Caddesi': 'TEVFIK_BEY',
    'Ali Rıza Özkay Caddesi': 'ALI_RIZA_OZKAY',
    'Cumhuriyet Caddesi': 'CUMHURIYET',
  };

  // --- Helper: Convert display name to backend enum ---
  static String toBackendEnum(String street) {
    if (street.contains("Tevfik")) return "TEVFIK_BEY";
    if (street.contains("Ali Rıza")) return "ALI_RIZA_OZKAY";
    if (street.contains("Cumhuriyet")) return "CUMHURIYET";
    return "TEVFIK_BEY"; // fallback
  }

  // --- Pricing (mirrors backend logic for live fee display) ---
  static const double smallBaseFee = 25.0;
  static const double smallExtraFee = 15.0;
  static const double largeBaseFee = 50.0;
  static const double largeExtraFee = 30.0;
  static const int gracePeriodSeconds = 300; // 5 minutes free

  // --- Auth State (In-Memory for now) ---
  static String? authToken;
  static String? currentUser;
  static String? currentRole;

  // --- App Info ---
  static const String appName = "BilPark Pro";
  static const String appVersion = "1.0.0";
  static const String companyName = "BilPark Bilişim A.Ş.";
}
