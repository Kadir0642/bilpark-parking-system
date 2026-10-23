import 'package:flutter/material.dart';
import '../core/format.dart';
import '../core/tariff.dart';
import '../core/theme.dart';

/// Sunucunun gün gün ücretli / ücretsiz süre dökümü. İstemci kendisi hesaplamaz;
/// [segments] sunucudan (veya estimateFee'den) gelir.
class DayBreakdown extends StatelessWidget {
  final List<DaySegmentInfo>? segments;
  final double? fee;
  final String? footnote;
  final bool loading;
  final String? error;
  final VoidCallback? onRetry;

  const DayBreakdown({
    super.key,
    required this.segments,
    this.fee,
    this.footnote,
    this.loading = false,
    this.error,
    this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final segs = segments ?? const <DaySegmentInfo>[];
    return Container(
      decoration: BoxDecoration(
        color: c.surface,
        border: Border.all(color: c.border),
        borderRadius: BorderRadius.circular(BpSize.rMd),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            child: Text('Gün gün süre dökümü', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
          ),
          const Divider(),
          ..._body(c, segs),
          if (footnote != null) ...[
            const Divider(),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              child: Text(footnote!, style: TextStyle(fontSize: 13, color: c.text2)),
            ),
          ],
        ],
      ),
    );
  }

  List<Widget> _body(BilParkColors c, List<DaySegmentInfo> segs) {
    if (loading) {
      return [
        Padding(
          padding: const EdgeInsets.all(12),
          child: Row(children: [
            const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
            const SizedBox(width: 10),
            Text('Döküm hesaplanıyor…', style: TextStyle(fontSize: 14, color: c.text2)),
          ]),
        ),
      ];
    }
    if (error != null) {
      return [
        Padding(
          padding: const EdgeInsets.all(12),
          child: Row(children: [
            Expanded(child: Text(error!, style: TextStyle(fontSize: 14, color: c.danger))),
            if (onRetry != null) TextButton(onPressed: onRetry, child: const Text('Tekrar dene')),
          ]),
        ),
      ];
    }
    if (segs.isEmpty) {
      return [
        Padding(
          padding: const EdgeInsets.all(12),
          child: Text('Henüz süre oluşmadı', style: TextStyle(fontSize: 14, color: c.text2)),
        ),
      ];
    }

    // Güne göre grupla (sıra korunur)
    final days = <DateTime, List<DaySegmentInfo>>{};
    for (final s in segs) {
      days.putIfAbsent(DateTime(s.date.year, s.date.month, s.date.day), () => []).add(s);
    }
    var paidTotal = 0;
    var freeTotal = 0;
    final rows = <Widget>[_line(c, const ['Gün', 'Ücretli', 'Ücretsiz'], header: true)];
    days.forEach((day, list) {
      final paid = list.where((s) => s.paid).fold<int>(0, (a, s) => a + s.minutes);
      final free = list.where((s) => !s.paid).fold<int>(0, (a, s) => a + s.minutes);
      paidTotal += paid;
      freeTotal += free;
      rows.add(_line(
        c,
        [formatDayLabel(day), formatDurationText(Duration(minutes: paid)), formatDurationText(Duration(minutes: free))],
        muteLast: true,
      ));
      rows.add(Padding(
        padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
        child: Text(
          list.map((s) => '${_hm(s.start, day)}–${_hm(s.end, day)} ${s.paid ? 'ücretli' : 'ücretsiz'}').join(' · '),
          style: TextStyle(fontSize: 12, color: c.text2),
        ),
      ));
    });
    rows.add(Container(
      color: c.surface2,
      child: _line(
        c,
        ['Toplam', formatDurationText(Duration(minutes: paidTotal)), formatDurationText(Duration(minutes: freeTotal))],
        bold: true,
      ),
    ));
    if (fee != null) {
      rows.add(Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            const Text('Tutar', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800)),
            Text(formatMoney(fee!),
                style: const TextStyle(
                    fontSize: 16, fontWeight: FontWeight.w800, fontFeatures: [FontFeature.tabularFigures()])),
          ],
        ),
      ));
    }
    return rows;
  }

  /// Gün sonuna denk gelen (ertesi gün 00:00) saat 24:00 yazılır.
  static String _hm(DateTime t, DateTime day) {
    final nextDay = DateTime(day.year, day.month, day.day + 1);
    if (t.isAtSameMomentAs(nextDay) || (t.isAfter(day) && t.hour == 0 && t.minute == 0 && t.day != day.day)) {
      return '24:00';
    }
    return formatHm(t);
  }

  Widget _line(BilParkColors c, List<String> cells, {bool header = false, bool bold = false, bool muteLast = false}) {
    TextStyle style(int i) => TextStyle(
          fontSize: header ? 13 : 15,
          fontWeight: header ? FontWeight.w600 : (bold ? FontWeight.w800 : FontWeight.w400),
          color: header || (muteLast && i == 2) ? c.text2 : c.text,
          fontFeatures: const [FontFeature.tabularFigures()],
        );
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Row(
        children: [
          Expanded(flex: 12, child: Text(cells[0], style: style(0))),
          Expanded(flex: 10, child: Text(cells[1], style: style(1))),
          Expanded(flex: 10, child: Text(cells[2], style: style(2))),
        ],
      ),
    );
  }
}
