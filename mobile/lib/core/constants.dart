// BilPark App - Global Constants
// All shared configuration values are defined here.
// API adresi derleme zamanında verilir: --dart-define=API_BASE=...

import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

class AppConstants {
  AppConstants._(); // Prevent instantiation

  // --- API ---
  static const String apiBase = String.fromEnvironment('API_BASE', defaultValue: 'https://bilpark-api-rtdl.onrender.com/api');
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
  static Map<String, dynamic>? assignedZone; // ADMIN için null

  // --- Auth Helpers ---
  static Map<String, String> authHeaders() => {
        if (authToken != null) 'Authorization': 'Bearer $authToken',
      };

  static void clearAuth() {
    authToken = null;
    currentUser = null;
    currentRole = null;
    assignedZone = null;
  }

  /// 401/403 yanıtlarını işler. İşlendiyse (yönlendirme/SnackBar) true döner.
  static bool handleAuthError(BuildContext context, http.Response response) {
    if (response.statusCode == 401) {
      clearAuth();
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text("Oturum süresi doldu, lütfen tekrar giriş yapın.")));
      Navigator.of(context).pushNamedAndRemoveUntil('/', (route) => false);
      return true;
    }
    if (response.statusCode == 403) {
      String msg = "Bu bölgede işlem yetkiniz yok.";
      try {
        final body = jsonDecode(response.body);
        if (body is Map && body['error'] is String) msg = body['error'];
      } catch (_) {}
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(msg), backgroundColor: Colors.red));
      return true;
    }
    return false;
  }

  // --- App Info ---
  static const String appName = "BilPark Pro";
  static const String appVersion = "1.0.0";
  static const String companyName = "BilPark Bilişim A.Ş.";
}
