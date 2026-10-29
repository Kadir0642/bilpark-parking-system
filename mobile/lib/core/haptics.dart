import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Test için: null değilse gerçek titreşim yerine bu çağrılır.
@visibleForTesting
VoidCallback? errorHapticOverride;

/// Yalnızca hatalarda titreşim (başarıda titreşim yok): iki güçlü darbe, arada ~120 ms.
Future<void> errorHaptic() async {
  final o = errorHapticOverride;
  if (o != null) {
    o();
    return;
  }
  try {
    await HapticFeedback.heavyImpact();
    await Future<void>.delayed(const Duration(milliseconds: 120));
    await HapticFeedback.heavyImpact();
  } catch (_) {
    // titreşim desteklenmiyorsa sessizce geçilir
  }
}
