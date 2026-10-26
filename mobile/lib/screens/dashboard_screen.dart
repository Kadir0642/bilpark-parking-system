import 'package:flutter/material.dart';
import 'settings_screen.dart';
import '../core/api_client.dart';
import '../core/constants.dart';
import '../core/format.dart';
import '../core/tariff.dart';
import '../core/theme.dart';
import '../widgets/bp_header.dart';
import '../widgets/connection_banner.dart';
import '../widgets/license_banner.dart';

class DashboardScreen extends StatefulWidget {
  final String streetCode; // sunucudaki cadde kodu
  final String streetName; // görünen cadde adı
  final int? zoneId;
  final String? zoneName;
  final VoidCallback? onOpenStreet;
  final VoidCallback? onScanPlate;
  final VoidCallback? onManualEntry;

  const DashboardScreen({
    super.key,
    required this.streetCode,
    required this.streetName,
    this.zoneId,
    this.zoneName,
    this.onOpenStreet,
    this.onScanPlate,
    this.onManualEntry,
  });

  @override
  State<DashboardScreen> createState() => DashboardScreenState();
}

class _Summary {
  double collected = 0;
  double cash = 0;
  double pos = 0;
  int paidCount = 0;
  int runawayCount = 0;
  int subscriptionExits = 0;
}

class DashboardScreenState extends State<DashboardScreen> {
  int activeVehicleCount = 0;
  int leftCount = 0;
  int rightCount = 0;
  int subscriberCount = 0;
  _Summary? _summary;

  String get _title => zoneTitle(widget.streetName, widget.zoneName);

  @override
  void initState() {
    super.initState();
    refresh();
  }

  Future<void> refresh() async {
    await Future.wait([_fetchStats(), _fetchSummary()]);
  }

  Future<void> _fetchStats() async {
    try {
      final spots = await ApiClient.get('/parking/filter', query: {'street': widget.streetCode});
      if (!mounted) return;
      var list = spots is List ? spots : [];
      // Görevli yalnızca kendi bölgesini görür (harita ekranıyla aynı süzme)
      if (widget.zoneId != null) {
        list = list.where((s) => s['zone'] != null && s['zone']['id'] == widget.zoneId).toList();
      }
      setState(() {
        activeVehicleCount = list.length;
        leftCount = list.where((s) => s['side'] == 'LEFT' || s['side'] == null).length;
        rightCount = list.where((s) => s['side'] == 'RIGHT').length;
        subscriberCount = list.where((s) => s['hasSubscription'] == true).length;
      });
    } on ApiException {
      // Bağlantı durumunu canlı senkron bandı gösterir; sayılar son değerde kalır
    }
  }

  /// Vardiya özeti, mevcut geçmiş uç noktasından bugünün kayıtlarıyla hesaplanır (yeni API çağrısı yok).
  Future<void> _fetchSummary() async {
    try {
      final data = await ApiClient.get('/parking/history', query: {'street': widget.streetCode});
      if (!mounted) return;
      final now = DateTime.now();
      final user = AppConstants.currentUser;
      final s = _Summary();
      for (final r in (data is List ? data : const [])) {
        if (r is! Map) continue;
        final z = r['zone'];
        if (widget.zoneId != null && z is Map && z['id'] != widget.zoneId) continue;
        final who = r['collectedByUsername'];
        if (user != null && who is String && who != user) continue;
        final t = DateTime.tryParse('${r['exitTime'] ?? r['entryTime']}')?.toLocal();
        if (t == null || t.year != now.year || t.month != now.month || t.day != now.day) continue;
        final fee = r['fee'] is num ? (r['fee'] as num).toDouble() : 0.0;
        if (r['status'] == 'RUNAWAY') {
          s.runawayCount++;
        } else if (r['status'] == 'PAID') {
          s.paidCount++;
          s.collected += fee;
          switch (r['paymentMethod']) {
            case 'CASH':
              s.cash += fee;
            case 'POS':
              s.pos += fee;
            case 'SUBSCRIPTION':
              s.subscriptionExits++;
          }
        }
      }
      setState(() => _summary = s);
    } on ApiException {
      // Özet alınamazsa kartlarda "-" görünür; bağlantı durumunu araç sayısı çağrısı belirler
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final user = AppConstants.currentUser;
    return ColoredBox(
      color: c.bg,
      child: Column(
        children: [
          BpHeader(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const LiveConnectionBanner(),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(_title, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
                          if (user != null)
                            Padding(
                              padding: const EdgeInsets.only(top: 2),
                              child: Text('Görevli: $user', style: TextStyle(fontSize: 14, color: c.text2)),
                            ),
                        ],
                      ),
                    ),
                    IconButton.outlined(
                      tooltip: 'Ayarlar',
                      icon: const Icon(Icons.settings_outlined),
                      style: IconButton.styleFrom(
                        minimumSize: const Size(BpSize.tap, BpSize.tap),
                        side: BorderSide(color: c.border),
                        backgroundColor: c.surface,
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(BpSize.rMd)),
                      ),
                      onPressed: () => Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) => SettingsScreen(street: widget.streetName, zoneName: widget.zoneName),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: refresh,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(BpSize.screenPad),
                children: [
                  _vehicleCard(c),
                  const SizedBox(height: 16),
                  _tariffCard(c),
                  const SizedBox(height: 16),
                  _summaryCard(c),
                  const SizedBox(height: 16),
                  ReadOnlyGate(
                    builder: (_, ro) => Row(
                      children: [
                        Expanded(child: _actionButton(Icons.photo_camera_outlined, 'Plaka tara', ro ? null : widget.onScanPlate)),
                        const SizedBox(width: 10),
                        Expanded(child: _actionButton(Icons.add, 'Elle ekle', ro ? null : widget.onManualEntry)),
                      ],
                    ),
                  ),
                  const ReadOnlyNote(),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _vehicleCard(BilParkColors c) {
    return Material(
      color: c.primary,
      borderRadius: BorderRadius.circular(BpSize.rLg),
      child: InkWell(
        borderRadius: BorderRadius.circular(BpSize.rLg),
        onTap: widget.onOpenStreet,
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Sahadaki araç', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: c.onPrimary)),
                    Text('$activeVehicleCount',
                        style: TextStyle(fontSize: 48, fontWeight: FontWeight.w800, height: 1.1, color: c.onPrimary)),
                    Text(
                      'Sol $leftCount · Sağ $rightCount${subscriberCount > 0 ? ' · $subscriberCount abone' : ''}',
                      style: TextStyle(fontSize: 14, color: c.onPrimary),
                    ),
                  ],
                ),
              ),
              Container(
                height: BpSize.tap,
                padding: const EdgeInsets.symmetric(horizontal: 16),
                decoration: BoxDecoration(color: c.surface, borderRadius: BorderRadius.circular(BpSize.rMd)),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text('Caddeyi aç', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: c.primary)),
                    const SizedBox(width: 6),
                    Icon(Icons.arrow_forward, size: 20, color: c.primary),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Güncel tarife özeti; TARIFF_UPDATED olayında anında yenilenir.
  Widget _tariffCard(BilParkColors c) {
    return ValueListenableBuilder<Tariff?>(
      valueListenable: TariffStore.current,
      builder: (_, t, _) {
        if (t == null) return const SizedBox.shrink();
        String line(Tariff x, String label, String type) =>
            '$label: İlk saat ${formatMoney(x.firstHour(type))} · sonraki ${formatMoney(x.extraHour(type))}';
        final end = t.paidEnd == '00:00' ? '24:00' : t.paidEnd;
        final up = t.upcoming;
        final from = up?.effectiveFrom;
        return Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: c.surface,
            border: Border.all(color: c.border),
            borderRadius: BorderRadius.circular(BpSize.rLg),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Güncel tarife', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              const SizedBox(height: 10),
              Text(line(t, 'Otomobil', 'SMALL'), style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
              const SizedBox(height: 4),
              Text(line(t, 'Kamyonet', 'LARGE'), style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
              const SizedBox(height: 6),
              Text('İlk ${t.freeMinutes} dk ücretsiz · ücretli ${t.paidStart}–$end',
                  style: TextStyle(fontSize: 14, color: c.text2)),
              if (up != null && from != null) ...[
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(color: c.warningSoft, borderRadius: BorderRadius.circular(BpSize.rSm)),
                  child: Text(
                    '${formatDayMonth(from)} ${formatHm(from)} itibarıyla yeni tarife: '
                    'Otomobil ${formatMoney(up.smallFirstHour)}/${formatMoney(up.smallExtraHour)} · '
                    'Kamyonet ${formatMoney(up.largeFirstHour)}/${formatMoney(up.largeExtraHour)}',
                    style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: c.onWarningSoft),
                  ),
                ),
              ],
            ],
          ),
        );
      },
    );
  }

  Widget _summaryCard(BilParkColors c) {
    final s = _summary;
    String money(double? v) => s == null ? '-' : formatMoney(v ?? 0);
    String count(int? v) => s == null ? '-' : '$v';
    Widget tile(String label, String value, {Color? bg, Color? fg, Color? valueColor}) {
      return Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(color: bg ?? c.surface2, borderRadius: BorderRadius.circular(BpSize.rMd)),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: fg ?? c.text2)),
            Text(value, style: TextStyle(fontSize: 26, fontWeight: FontWeight.w800, color: valueColor ?? fg ?? c.text)),
          ],
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: c.surface,
        border: Border.all(color: c.border),
        borderRadius: BorderRadius.circular(BpSize.rLg),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              const Text('Vardiya özeti', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              Text('bugün', style: TextStyle(fontSize: 13, color: c.text2)),
            ],
          ),
          const SizedBox(height: 12),
          GridView.count(
            crossAxisCount: 2,
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            mainAxisSpacing: 10,
            crossAxisSpacing: 10,
            childAspectRatio: 2.0,
            children: [
              tile('Tahsil edilen', money(s?.collected)),
              tile('İşlem', count(s?.paidCount)),
              tile('Kaçak', count(s?.runawayCount), valueColor: c.danger),
              tile('Teslim edilecek nakit', money(s?.cash), bg: c.successSoft, fg: c.onSuccessSoft),
            ],
          ),
          if (s != null) ...[
            const SizedBox(height: 12),
            Text('Nakit ${formatMoney(s.cash)} · POS ${formatMoney(s.pos)} · Abonelik ${s.subscriptionExits} çıkış',
                style: TextStyle(fontSize: 14, color: c.text2)),
          ] else ...[
            const SizedBox(height: 12),
            Text('Özet yüklenemedi. Yenilemek için aşağı çekin.', style: TextStyle(fontSize: 14, color: c.text2)),
          ],
        ],
      ),
    );
  }

  Widget _actionButton(IconData icon, String label, VoidCallback? onTap) {
    return SizedBox(
      height: 64,
      child: OutlinedButton.icon(
        onPressed: onTap,
        icon: Icon(icon, size: 24),
        label: Text(label, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
      ),
    );
  }
}
