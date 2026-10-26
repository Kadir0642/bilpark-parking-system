// BilPark App - Lisans durumu
// GET /license/status yanıtı ve tüm ekranların dinlediği global depo. readOnly iken yazma düğmeleri kapanır.

import 'package:flutter/foundation.dart';
import 'api_client.dart';

enum LicenseState { active, grace, readOnly }

class LicenseStatus {
  final String? licensee;
  final String? expiresOn; // yyyy-MM-dd
  final String? graceEndsOn;
  final int? daysLeft;
  final int? graceDaysLeft;
  final LicenseState state;
  final int? warningLevel; // 30 | 7 | 1 | 0 | null
  final bool readOnly;
  final String message;

  const LicenseStatus({
    this.licensee,
    this.expiresOn,
    this.graceEndsOn,
    this.daysLeft,
    this.graceDaysLeft,
    required this.state,
    this.warningLevel,
    required this.readOnly,
    required this.message,
  });

  factory LicenseStatus.fromJson(Map<String, dynamic> j) {
    final state = switch (j['state']) {
      'GRACE' => LicenseState.grace,
      'READ_ONLY' => LicenseState.readOnly,
      _ => LicenseState.active,
    };
    return LicenseStatus(
      licensee: j['licensee'] as String?,
      expiresOn: j['expiresOn'] as String?,
      graceEndsOn: j['graceEndsOn'] as String?,
      daysLeft: (j['daysLeft'] as num?)?.toInt(),
      graceDaysLeft: (j['graceDaysLeft'] as num?)?.toInt(),
      state: state,
      warningLevel: (j['warningLevel'] as num?)?.toInt(),
      readOnly: j['readOnly'] == true || state == LicenseState.readOnly,
      message: (j['message'] as String?) ?? '',
    );
  }
}

class LicenseStore {
  LicenseStore._();

  static const String defaultReadOnlyMessage =
      'Lisans süresi doldu; sistem okunur modda. Giriş, çıkış ve kaçak işlemleri yapılamaz.';

  static final ValueNotifier<LicenseStatus?> status = ValueNotifier(null);
  static final ValueNotifier<bool> readOnly = ValueNotifier(false);

  /// Sunucunun son okunur mod mesajı (423 yanıtından ya da lisans durumundan).
  static String readOnlyMessage = defaultReadOnlyMessage;

  /// 423 alan [ApiClient] bu kancayla depoya haber verir (döngüsel import olmasın diye).
  static void attach() => ApiClient.onReadOnly = markReadOnly;

  static void applyStatus(LicenseStatus s) {
    status.value = s;
    if (s.readOnly && s.message.isNotEmpty) readOnlyMessage = s.message;
    readOnly.value = s.readOnly;
  }

  /// Ağ hatası mevcut durumu bozmaz.
  static Future<void> load() async {
    try {
      final data = await ApiClient.get('/license/status');
      if (data is Map) applyStatus(LicenseStatus.fromJson(Map<String, dynamic>.from(data)));
    } on ApiException {
      // eski durum korunur
    }
  }

  static void markReadOnly(String message) {
    if (message.isNotEmpty) readOnlyMessage = message;
    readOnly.value = true;
  }

  static void reset() {
    status.value = null;
    readOnly.value = false;
    readOnlyMessage = defaultReadOnlyMessage;
  }
}
