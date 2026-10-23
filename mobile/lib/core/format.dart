// Ortak biçimlendirme yardımcıları (para, süre, plaka, gün adı).

String two(int n) => n.toString().padLeft(2, '0');

String formatMoney(num v) => '₺${v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(2)}';

String formatHm(DateTime t) => '${two(t.hour)}:${two(t.minute)}';

/// 03:25:08
String formatClock(Duration d) {
  final s = d.isNegative ? Duration.zero : d;
  return '${two(s.inHours)}:${two(s.inMinutes.remainder(60))}:${two(s.inSeconds.remainder(60))}';
}

/// "13 sa 25 dk", "52 dk", "2 sa"
String formatDurationText(Duration d) {
  final s = d.isNegative ? Duration.zero : d;
  final h = s.inHours;
  final m = s.inMinutes.remainder(60);
  if (h == 0) return '$m dk';
  if (m == 0) return '$h sa';
  return '$h sa ${two(m)} dk';
}

/// 34ABC123 -> 34 ABC 123 (biçime uymayan plaka olduğu gibi döner)
String formatPlate(String plate) {
  final m = RegExp(r'^(\d{2})([A-Z]{1,3})(\d{2,4})$').firstMatch(plate.toUpperCase().replaceAll(RegExp(r'[\s-]'), ''));
  if (m == null) return plate;
  return '${m.group(1)} ${m.group(2)} ${m.group(3)}';
}

/// 2 rakam, ardından MA-MZ veya MAA-MZZ arası harfler, ardından 3-4 rakam.
bool isForeignPlate(String plate) {
  return RegExp(r'^\d{2}M[A-Z]{1,2}\d{3,4}$').hasMatch(plate.toUpperCase().replaceAll(' ', ''));
}

const _months = ['Oca', 'Şub', 'Mar', 'Nis', 'May', 'Haz', 'Tem', 'Ağu', 'Eyl', 'Eki', 'Kas', 'Ara'];
const _weekdays = ['Pzt', 'Sal', 'Çar', 'Per', 'Cum', 'Cmt', 'Paz'];

/// 22 Eki
String formatDayMonth(DateTime d) => '${d.day} ${_months[d.month - 1]}';

/// 22 Eki Çar
String formatDayLabel(DateTime d) => '${formatDayMonth(d)} ${_weekdays[d.weekday - 1]}';

String methodLabel(String? m) {
  switch (m) {
    case 'CASH':
      return 'Nakit';
    case 'POS':
      return 'POS';
    case 'SUBSCRIPTION':
      return 'Abonelik';
    default:
      return m ?? '-';
  }
}

/// Ekran başlığı: "Tevfik Bey — Bölüm 1". Bölge adı zaten cadde adını içeriyorsa tekrar edilmez
/// (sunucudaki bölge adları "Tevfik Bey - Bölüm 1" biçiminde).
String zoneTitle(String street, String? zoneName) {
  final short = street.replaceAll(' Caddesi', '');
  if (zoneName == null || zoneName.isEmpty) return street;
  if (zoneName.startsWith(short)) return zoneName.replaceFirst(' - ', ' — ');
  return '$short — $zoneName';
}
