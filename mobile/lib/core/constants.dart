// BilPark App - Global Constants
// All shared configuration values are defined here.
// API adresi derleme zamanında verilir: --dart-define=API_BASE=...

class AppConstants {
  AppConstants._(); // Prevent instantiation

  // --- API ---
  static const String apiBase = String.fromEnvironment('API_BASE', defaultValue: 'https://bilpark-api-rtdl.onrender.com/api');
  static const String baseUrl = "$apiBase/parking";

  // --- Auth State (token ApiClient'ta, SharedPreferences'ta saklanır) ---
  static String? currentUser;
  static String? currentRole;
  static Map<String, dynamic>? assignedZone; // ADMIN için null

  // --- Auth Helpers ---
  static void clearAuth() {
    currentUser = null;
    currentRole = null;
    assignedZone = null;
  }

  // --- App Info ---
  static const String appName = "BilPark Pro";
  static const String appVersion = "1.0.0";
  static const String companyName = "BilPark Bilişim A.Ş.";
}
