// Canlı olay (SSE) kaynaklı yenilemeleri birleştirir. Her olayda her telefon sunucudan okuma yaparsa yük
// görevli sayısıyla çarpılır; bu sınıf art arda gelen isteği tek çalıştırmaya indirger:
//  - boştayken ilk istek [lead] sonra çalışır (aynı anda gelen birkaç olay tek yenileme olur),
//  - çalıştıktan sonra [window] boyunca yeni istekler biriktirilir, pencere bitince en çok bir kez daha çalışır,
//  - [canRun] false iken (uygulama arka planda) çalışmaz, istek bekler; [resume] ile yeniden denenir.

import 'dart:async';

class RefreshCoalescer {
  final FutureOr<void> Function() action;
  final bool Function()? canRun;
  final Duration lead;
  final Duration window;

  RefreshCoalescer({
    required this.action,
    this.canRun,
    this.lead = const Duration(milliseconds: 400),
    this.window = const Duration(milliseconds: 2500),
  });

  bool _dirty = false;
  bool _disposed = false;
  Timer? _leadTimer;
  Timer? _cooldown;

  /// Bekleyen (henüz çalışmamış) istek var mı?
  bool get pending => _dirty;

  /// Yenileme gerekti. Çok kez çağrılabilir, sonuç en çok pencere başına bir çalıştırmadır.
  void request() {
    if (_disposed) return;
    _dirty = true;
    if (_leadTimer != null || _cooldown != null) return;
    _leadTimer = Timer(lead, _fire);
  }

  /// Çalışma izni geri geldi (uygulama öne geldi, ekran göründü): bekleyen istek varsa hemen planlanır.
  void resume() {
    if (_disposed || !_dirty) return;
    if (_leadTimer != null || _cooldown != null) return;
    _leadTimer = Timer(lead, _fire);
  }

  void _fire() {
    _leadTimer = null;
    if (_disposed || !_dirty) return;
    if (canRun != null && !canRun!()) return; // istek bekler; resume() çalıştırır
    _dirty = false;
    _cooldown = Timer(window, () {
      _cooldown = null;
      if (_dirty) _fire();
    });
    try {
      final r = action();
      if (r is Future) unawaited(r.catchError((Object _) {}));
    } catch (_) {
      // yenileme hatası zamanlayıcıyı bozmaz
    }
  }

  void dispose() {
    _disposed = true;
    _leadTimer?.cancel();
    _cooldown?.cancel();
    _leadTimer = null;
    _cooldown = null;
  }
}
