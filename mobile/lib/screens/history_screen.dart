import 'package:flutter/material.dart';
import '../core/api_client.dart';
import '../core/format.dart';
import '../core/theme.dart';
import '../widgets/bp_header.dart';
import '../widgets/bp_snack.dart';
import '../widgets/search_field.dart';
import '../widgets/status_badge.dart';

class HistoryScreen extends StatefulWidget {
  final String streetCode; // sunucudaki cadde kodu
  final String streetName; // görünen cadde adı
  final bool showOnlyRunaways; // DRY PRENSİBİ: Bu sayfa Kaçaklar için mi açıldı?

  const HistoryScreen({
    super.key,
    required this.streetCode,
    required this.streetName,
    required this.showOnlyRunaways,
  });

  @override
  State<HistoryScreen> createState() => HistoryScreenState();
}

class _Day {
  final DateTime day;
  final List<Map<dynamic, dynamic>> records = [];
  double total = 0;
  _Day(this.day);
}

class HistoryScreenState extends State<HistoryScreen> {
  List<dynamic> historyRecords = [];
  bool isLoading = true;
  final TextEditingController searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    fetchHistory();
  }

  @override
  void dispose() {
    searchController.dispose();
    super.dispose();
  }

  /// Sekmeye dönüldüğünde listeyi (aramayı koruyarak) tazeler.
  Future<void> refresh() {
    final q = searchController.text.trim();
    return q.isEmpty ? fetchHistory() : searchPlate(q);
  }

  // --- 1. GEÇMİŞİ GETİR ---
  Future<void> fetchHistory() async {
    setState(() => isLoading = true);

    try {
      final data = await ApiClient.get('/parking/history', query: {'street': widget.streetCode});
      if (!mounted) return;
      setState(() {
        historyRecords = _filterByMode(data is List ? data : []);
        isLoading = false;
      });
    } on ApiException catch (e) {
      _showError(e.message);
      if (mounted) {
        setState(() {
          historyRecords = [];
          isLoading = false;
        });
      }
    }
  }

  // Kaçaklar sayfasında yalnızca RUNAWAY, normal geçmişte yalnızca PAID kayıtlar
  List<dynamic> _filterByMode(List<dynamic> records) {
    final wanted = widget.showOnlyRunaways ? 'RUNAWAY' : 'PAID';
    return records.where((r) => r['status'] == wanted).toList();
  }

  void _showError(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(bpSnackBar(context, msg, tone: BadgeTone.danger));
  }

  // --- 2. ARAMA ---
  Future<void> searchPlate(String plate) async {
    if (plate.isEmpty) {
      fetchHistory();
      return;
    }

    setState(() => isLoading = true);
    try {
      // Sunucu parça arama yapar; kayıt yoksa 404 NOT_FOUND döner (boş liste gibi ele alınır)
      final data = await ApiClient.get('/parking/history/search',
          query: {'plate': plate.toUpperCase().replaceAll(RegExp(r'[\s-]'), '')});
      if (!mounted) return;
      setState(() {
        historyRecords = _filterByMode(data is List ? data : []);
        isLoading = false;
      });
    } on ApiException catch (e) {
      if (e.statusCode != 404) _showError(e.message);
      if (mounted) {
        setState(() {
          historyRecords = [];
          isLoading = false;
        });
      }
    }
  }

  DateTime? _parse(dynamic s) => s == null ? null : DateTime.tryParse(s.toString())?.toLocal();

  double _fee(Map r) => r['fee'] is num ? (r['fee'] as num).toDouble() : 0.0;

  /// Kayıtları gün bazında gruplar (kapanış zamanına, yoksa girişe göre; en yeni gün üstte).
  List<_Day> _group() {
    final days = <String, _Day>{};
    for (final r in historyRecords) {
      if (r is! Map) continue;
      final t = _parse(r['exitTime']) ?? _parse(r['entryTime']) ?? DateTime.fromMillisecondsSinceEpoch(0);
      final key = '${t.year}-${t.month}-${t.day}';
      final d = days.putIfAbsent(key, () => _Day(DateTime(t.year, t.month, t.day)));
      d.records.add(r);
      d.total += _fee(r);
    }
    final list = days.values.toList()..sort((a, b) => b.day.compareTo(a.day));
    for (final d in list) {
      d.records.sort((a, b) {
        final ta = _parse(a['exitTime']) ?? _parse(a['entryTime']) ?? DateTime.fromMillisecondsSinceEpoch(0);
        final tb = _parse(b['exitTime']) ?? _parse(b['entryTime']) ?? DateTime.fromMillisecondsSinceEpoch(0);
        return tb.compareTo(ta);
      });
    }
    return list;
  }

  String _dayTitle(DateTime d) {
    final now = DateTime.now();
    if (d.year == now.year && d.month == now.month && d.day == now.day) return 'Bugün · ${formatDayLabel(d)}';
    return formatDayLabel(d);
  }

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final runaways = widget.showOnlyRunaways;
    return ColoredBox(
      color: c.bg,
      child: Column(
        children: [
          BpHeader(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.baseline,
                  textBaseline: TextBaseline.alphabetic,
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(runaways ? 'Kaçaklar' : 'Geçmiş', style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
                    Flexible(
                      child: Text(widget.streetName, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 14, color: c.text2)),
                    ),
                  ],
                ),
                const SizedBox(height: 10),
                BpSearchField(
                  controller: searchController,
                  hint: runaways ? 'Plaka ara' : 'Plaka ara (örn. 11ABC123)',
                  onSubmitted: searchPlate,
                  onCleared: fetchHistory,
                ),
              ],
            ),
          ),
          Expanded(
            child: isLoading
                ? const Center(child: CircularProgressIndicator())
                : RefreshIndicator(onRefresh: refresh, child: _body(c, runaways)),
          ),
        ],
      ),
    );
  }

  Widget _body(BilParkColors c, bool runaways) {
    if (historyRecords.isEmpty) {
      return ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(32),
        children: [
          const SizedBox(height: 48),
          Icon(runaways ? Icons.verified_user_outlined : Icons.history, size: 56, color: c.borderStrong),
          const SizedBox(height: 12),
          Text(
            runaways ? 'Bu caddede kaçak araç kaydı yok.' : 'Kayıt bulunamadı.',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 16, color: c.text2),
          ),
        ],
      );
    }
    final days = _group();
    final totalDebt = historyRecords.whereType<Map>().fold<double>(0, (a, r) => a + _fee(r));
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      children: [
        if (runaways) ...[
          _debtSummary(c, totalDebt, historyRecords.length),
          const SizedBox(height: 4),
        ],
        for (final d in days) ...[
          Padding(
            padding: const EdgeInsets.fromLTRB(2, 14, 2, 8),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(_dayTitle(d.day), style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: c.text2)),
                Text(formatMoney(d.total),
                    style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: runaways ? c.danger : c.text)),
              ],
            ),
          ),
          Container(
            decoration: BoxDecoration(
              color: c.surface,
              border: Border.all(color: c.border),
              borderRadius: BorderRadius.circular(BpSize.rMd),
            ),
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                for (var i = 0; i < d.records.length; i++) ...[
                  if (i > 0) Divider(color: c.border),
                  runaways ? _runawayRow(c, d.records[i]) : _paidRow(c, d.records[i]),
                ],
              ],
            ),
          ),
        ],
        if (runaways) ...[
          const SizedBox(height: 16),
          Text('Borç tahsilatı yönetim panelinden yapılır. Saha personeli kaydı yalnızca görür.',
              style: TextStyle(fontSize: 14, color: c.text2)),
        ],
      ],
    );
  }

  Widget _debtSummary(BilParkColors c, double total, int count) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: c.dangerSoft,
        border: Border.all(color: c.danger),
        borderRadius: BorderRadius.circular(BpSize.rLg),
      ),
      child: Row(
        children: [
          Icon(Icons.warning_amber_rounded, size: 36, color: c.onDangerSoft),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Açık borç toplamı', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: c.onDangerSoft)),
                Text(formatMoney(total), style: TextStyle(fontSize: 30, fontWeight: FontWeight.w800, color: c.onDangerSoft)),
                Text('$count araç · bu araçlar tekrar girişte uyarı verir',
                    style: TextStyle(fontSize: 14, color: c.onDangerSoft)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _paidRow(BilParkColors c, Map r) {
    final entry = _parse(r['entryTime']);
    final exit = _parse(r['exitTime']);
    final range = '${entry != null ? formatHm(entry) : '-'} – ${exit != null ? formatHm(exit) : '-'}';
    final dur = (entry != null && exit != null) ? ' · ${formatDurationText(exit.difference(entry))}' : '';
    final method = r['paymentMethod'] as String?;
    final paidMin = r['paidMinutes'] is num ? (r['paidMinutes'] as num).toInt() : null;
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 68),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(formatPlate((r['licensePlate'] ?? 'Bilinmiyor').toString()),
                      style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800, letterSpacing: 0.5)),
                  const SizedBox(height: 4),
                  Text('$range$dur', style: TextStyle(fontSize: 14, color: c.text2)),
                  if (paidMin != null)
                    Text('Ücretli süre: ${formatDurationText(Duration(minutes: paidMin))}',
                        style: TextStyle(fontSize: 14, color: c.text2)),
                ],
              ),
            ),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(formatMoney(_fee(r)), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
                if (method != null) ...[
                  const SizedBox(height: 4),
                  StatusBadge(methodLabel(method), tone: _methodTone(method)),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }

  BadgeTone _methodTone(String m) {
    switch (m) {
      case 'CASH':
        return BadgeTone.success;
      case 'POS':
        return BadgeTone.primary;
      case 'SUBSCRIPTION':
        return BadgeTone.sub;
      default:
        return BadgeTone.neutral;
    }
  }

  Widget _runawayRow(BilParkColors c, Map r) {
    final entry = _parse(r['entryTime']);
    final exit = _parse(r['exitTime']);
    final when = entry == null
        ? '-'
        : '${formatDayMonth(entry)} ${formatHm(entry)}${exit != null ? ' – ${formatHm(exit)}' : ''}';
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 72),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    crossAxisAlignment: WrapCrossAlignment.center,
                    spacing: 8,
                    runSpacing: 4,
                    children: [
                      Text(formatPlate((r['licensePlate'] ?? 'Bilinmiyor').toString()),
                          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800, letterSpacing: 0.5)),
                      const StatusBadge('Kaçak', tone: BadgeTone.danger),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(when, style: TextStyle(fontSize: 14, color: c.text2)),
                ],
              ),
            ),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text('Borç', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: c.text2)),
                Text(formatMoney(_fee(r)), style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: c.danger)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
