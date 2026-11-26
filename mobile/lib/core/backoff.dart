// Sunucuyu yormadan yeniden deneme yardımcıları: üstel bekleme + jitter ve Retry-After ayrıştırma.
// Hepsi saf işlevdir (rastgelelik ve saat dışarıdan verilebilir), bu yüzden testi kolaydır.

import 'dart:math';

/// [attempt]: 1'den başlayan deneme sayısı. Bekleme = min(base * 2^(attempt-1), cap);
/// "eşit jitter": yarısı sabit, yarısı rastgele (cap'i hiç aşmaz). Böylece yüzlerce telefon
/// aynı anda koparsa bile geri bağlanmaları aynı saniyelere yığılmaz.
Duration backoffDelay(
  int attempt, {
  Duration base = const Duration(seconds: 1),
  Duration cap = const Duration(seconds: 45),
  Random? rng,
}) {
  final n = attempt < 1 ? 1 : attempt;
  // 2^n taşmasın: üst sınır zaten cap
  final factor = n > 20 ? 1 << 20 : 1 << (n - 1);
  final raw = base.inMilliseconds * factor;
  final capped = raw > cap.inMilliseconds ? cap.inMilliseconds : raw;
  final r = (rng ?? Random()).nextDouble();
  return Duration(milliseconds: (capped * (0.5 + 0.5 * r)).round());
}

/// 0 ile [max] arasında rastgele süre (başlangıç gecikmesi için).
Duration randomJitter(Duration max, [Random? rng]) {
  if (max <= Duration.zero) return Duration.zero;
  return Duration(milliseconds: ((rng ?? Random()).nextDouble() * max.inMilliseconds).round());
}

/// `Retry-After` başlığı: saniye sayısı ya da HTTP tarihi. Geçersiz/negatifse null.
/// [cap] verilirse üst sınır uygulanır (sunucu saatlerce bekletemesin).
Duration? parseRetryAfter(String? value, {DateTime? now, Duration? cap}) {
  if (value == null) return null;
  final v = value.trim();
  if (v.isEmpty) return null;
  Duration? d;
  final secs = int.tryParse(v);
  if (secs != null) {
    if (secs < 0) return null;
    d = Duration(seconds: secs);
  } else {
    final date = _parseHttpDate(v);
    if (date == null) return null;
    final diff = date.difference((now ?? DateTime.now()).toUtc());
    d = diff.isNegative ? Duration.zero : diff;
  }
  if (cap != null && d > cap) return cap;
  return d;
}

const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/// IMF-fixdate: "Wed, 21 Oct 2026 07:28:00 GMT" (HttpDate'e bağımlı olmamak için elle).
DateTime? _parseHttpDate(String s) {
  final m = RegExp(r'^[A-Za-z]{3},\s+(\d{1,2})\s+([A-Za-z]{3})\s+(\d{4})\s+(\d{2}):(\d{2}):(\d{2})\s+GMT$').firstMatch(s);
  if (m == null) return null;
  final month = _months.indexOf(m.group(2)!) + 1;
  if (month == 0) return null;
  return DateTime.utc(int.parse(m.group(3)!), month, int.parse(m.group(1)!), int.parse(m.group(4)!),
      int.parse(m.group(5)!), int.parse(m.group(6)!));
}
