import 'package:flutter/material.dart';
import '../core/format.dart';
import '../core/theme.dart';

/// Gün gün ücretli / ücretsiz süre dökümü.
///
/// [breakdown] sunucunun ileride döneceği `[{date, paidMinutes, freeMinutes}]` listesidir.
/// Gelmediği sürece yalnızca toplam süre gösterilir; istemci ücretsiz gece saatini KENDİ hesaplamaz
/// (sunucudaki ücretle çelişmemesi için).
class DayBreakdown extends StatelessWidget {
  final Duration total;
  final List<dynamic>? breakdown;
  final String? footnote;

  const DayBreakdown({super.key, required this.total, this.breakdown, this.footnote});

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final rows = _parse(breakdown);
    final hasRows = rows.isNotEmpty;
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
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            child: Text(hasRows ? 'Gün gün süre dökümü' : 'Süre dökümü',
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
          ),
          const Divider(),
          if (hasRows) ...[
            _line(c, const ['Gün', 'Ücretli', 'Ücretsiz'], header: true),
            for (final r in rows)
              _line(
                  c,
                  [
                    formatDayLabel(r.date),
                    formatDurationText(Duration(minutes: r.paid)),
                    formatDurationText(Duration(minutes: r.free)),
                  ],
                  muteLast: true),
            Container(
              color: c.surface2,
              child: _line(
                  c,
                  [
                    'Toplam',
                    formatDurationText(Duration(minutes: rows.fold<int>(0, (a, r) => a + r.paid))),
                    formatDurationText(Duration(minutes: rows.fold<int>(0, (a, r) => a + r.free))),
                  ],
                  bold: true),
            ),
          ] else ...[
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  const Text('Toplam süre', style: TextStyle(fontSize: 15)),
                  Text(formatDurationText(total),
                      style: const TextStyle(
                          fontSize: 15, fontWeight: FontWeight.w800, fontFeatures: [FontFeature.tabularFigures()])),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
              child: Text('Gün gün döküm tarife güncellemesiyle gelecek.', style: TextStyle(fontSize: 13, color: c.text2)),
            ),
          ],
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

  static List<_Row> _parse(List<dynamic>? raw) {
    if (raw == null) return const [];
    final out = <_Row>[];
    for (final e in raw) {
      if (e is! Map) continue;
      final d = DateTime.tryParse('${e['date']}');
      final paid = e['paidMinutes'];
      final free = e['freeMinutes'];
      if (d == null || paid is! num || free is! num) continue;
      out.add(_Row(d.toLocal(), paid.toInt(), free.toInt()));
    }
    return out;
  }
}

class _Row {
  final DateTime date;
  final int paid;
  final int free;
  const _Row(this.date, this.paid, this.free);
}
