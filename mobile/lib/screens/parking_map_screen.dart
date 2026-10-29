import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import '../core/api_client.dart';
import '../core/constants.dart';
import '../core/entry_queue.dart';
import '../core/format.dart';
import '../core/license.dart';
import '../core/live_sync.dart';
import '../core/tariff.dart';
import '../core/theme.dart';
import '../core/vehicle_types.dart';
import '../widgets/bp_header.dart';
import '../widgets/bp_snack.dart';
import '../widgets/connection_banner.dart';
import '../widgets/debt_alert.dart';
import '../widgets/entry_sheet.dart';
import '../widgets/license_banner.dart';
import '../widgets/plate_badge.dart';
import '../widgets/search_field.dart';
import '../widgets/status_badge.dart';
import 'plate_scanner_screen.dart';
import 'vehicle_detail_screen.dart';

enum _ActionKind {
  checkOut('Ödeme'),
  runaway('Kaçak'),
  checkIn('Giriş'); // yalnızca kuyruktan düşen girişin hatası için; yeniden denenmez

  final String label;
  const _ActionKind(this.label);
}

class _PendingAction {
  final String plate;
  final _ActionKind kind;
  final String? method; // CASH | POS | SUBSCRIPTION (yalnızca ödeme)
  final double amount;
  final DateTime createdAt = DateTime.now();
  Timer? timer;
  bool sent = false; // true: istek yola çıktı, artık geri alınamaz

  _PendingAction(this.plate, this.kind, this.method, this.amount);
}

class _FailedAction {
  final String plate;
  final _ActionKind kind;
  final String? method;
  final double amount;
  final DateTime time;
  final String message;
  final String? code;
  final int? statusCode;

  _FailedAction(this.plate, this.kind, this.method, this.amount, this.time, this.message, this.code, this.statusCode);

  // Ağ/sunucu hatası ve kilit çakışması tekrar denenebilir; "zaten işlendi", "abonelik yok" gibi iş kuralı hataları denenemez
  // 423 READ_ONLY dahil diğer 4xx hataları tekrar denenemez
  bool get retryable => kind != _ActionKind.checkIn && (statusCode == null || statusCode! >= 500 || code == 'CONFLICT');
}

class ParkingMapScreen extends StatefulWidget {
  /// Sunucudaki cadde kodu (TEVFIK_BEY ...)
  final String streetCode;

  /// Görünen ad ("Tevfik Bey Caddesi")
  final String streetName;
  final int? zoneId;
  final String? zoneName;

  const ParkingMapScreen({super.key, required this.streetCode, required this.streetName, this.zoneId, this.zoneName});

  @override
  State<ParkingMapScreen> createState() => ParkingMapScreenState();
}

class ParkingMapScreenState extends State<ParkingMapScreen> with AutomaticKeepAliveClientMixin {
  static const Duration _longStay = Duration(hours: 3);

  List<dynamic> activeVehicles = [];
  Timer? _clock; // kart sürelerini yerelde yeniler (ağ yok)
  Timer? _pendingTick; // geri alma geri sayımı
  Timer? _debounce; // art arda gelen yenileme istekleri tek istek olur
  StreamSubscription<SyncEvent>? _evSub;
  StreamSubscription<void>? _reloadSub;
  StreamSubscription<EntrySendResult>? _queueSub;
  final GlobalKey _actionsKey = GlobalKey(); // alttaki düğme sütunu: bildirimler bunun üstünde kalır
  bool _failedExpanded = true;
  final Map<String, _PendingAction> _pending = {};
  final List<_FailedAction> _failed = [];

  final TextEditingController _search = TextEditingController();
  bool _sortLongest = false;
  bool _hideSubscribers = false;

  String get _backendStreetEnum => widget.streetCode;

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    fetchActiveVehicles();
    // Veri yalnızca olay gelince yenilenir; bu zamanlayıcı sadece süreleri yeniden çizer
    _clock = Timer.periodic(const Duration(seconds: 30), (t) {
      if (mounted) setState(() {});
    });
    _evSub = liveSync.events.listen(_onEvent);
    _reloadSub = liveSync.reloads.listen((_) => scheduleRefresh());
    TariffStore.current.addListener(_onTariff);
    _queueSub = entryQueue.results.listen(_onQueueResult);
    entryQueue.items.addListener(_onQueueItems);
  }

  void _onQueueItems() {
    if (mounted) setState(() {});
  }

  void _onTariff() {
    if (mounted) setState(() {});
  }

  void _onEvent(SyncEvent e) {
    if (e.type != 'VEHICLE_ENTERED' && e.type != 'VEHICLE_EXITED' && e.type != 'VEHICLE_RUNAWAY') return;
    final mine = widget.zoneId != null ? e.zoneId == widget.zoneId : e.street == widget.streetCode;
    if (mine) scheduleRefresh();
  }

  /// Yenilemeyi ~300 ms geciktirir; art arda gelen tetikler tek GET /parking/filter olur.
  void scheduleRefresh() {
    if (!mounted) return;
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () {
      if (mounted) fetchActiveVehicles();
    });
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _evSub?.cancel();
    _reloadSub?.cancel();
    _queueSub?.cancel();
    entryQueue.items.removeListener(_onQueueItems);
    TariffStore.current.removeListener(_onTariff);
    _clock?.cancel();
    _pendingTick?.cancel();
    _search.dispose();
    // Ekran kapanırken bekleyen işlemler SESSİZCE DÜŞÜRÜLMEZ, hemen gönderilir: görevli onay vermişti ve
    // tahsilat/kaçak kaydı kaybolursa kasa ile saha uyuşmaz. (UI yok; sonuç sunucu kaydında görünür.)
    for (final p in _pending.values.toList()) {
      if (!p.sent) {
        p.timer?.cancel();
        _execute(p);
      }
    }
    super.dispose();
  }

  // --- API İŞLEMLERİ ---
  Future<void> fetchActiveVehicles({bool userTriggered = false}) async {
    try {
      final data = await ApiClient.get('/parking/filter', query: {'street': _backendStreetEnum});
      if (!mounted) return;
      var list = data is List ? data : <dynamic>[];
      if (widget.zoneId != null) {
        list = list.where((spot) => spot['zone'] != null && spot['zone']['id'] == widget.zoneId).toList();
      }
      setState(() => activeVehicles = list);
    } on ApiException catch (e) {
      // Bağlantı durumunu canlı senkron bandı gösterir; yalnızca kullanıcı işleminden sonraki hata bildirilir
      if (userTriggered && mounted && !e.isUnauthorized) {
        _showSnack('Liste yenilenemedi: ${e.message}', tone: BadgeTone.danger);
      }
    }
  }

  void _showSnack(String msg, {BadgeTone? tone, Duration duration = const Duration(seconds: 4), SnackBarAction? action}) {
    if (!mounted) return;
    final m = ScaffoldMessenger.of(context);
    m.hideCurrentSnackBar();
    // Floating + alt boşluk: bildirim "+" / "Plaka tara" düğmelerinin üstünde durur, dokunuşları yutmaz
    final box = _actionsKey.currentContext?.findRenderObject();
    final h = box is RenderBox && box.hasSize ? box.size.height : 60.0;
    m.showSnackBar(bpSnackBar(context, msg,
        tone: tone,
        duration: duration,
        action: action,
        behavior: SnackBarBehavior.floating,
        margin: EdgeInsets.fromLTRB(12, 0, 12, h + 12 + 8)));
  }

  // CHECK-IN "SIDE" (YÖN) İSTER
  Future<void> checkInVehicle(String plate, String type, String side) async {
    // Her giriş istemci UUID'si taşır: istek sunucuya ulaşıp yanıt kaybolsa da tekrar gönderim çift kayıt açmaz
    final entry = PendingEntry(
      requestId: newRequestId(),
      plate: plate,
      street: _backendStreetEnum,
      type: type,
      side: side, // 'LEFT' veya 'RIGHT' olarak Backend'e gidiyor!
      zoneId: widget.zoneId,
      username: AppConstants.currentUser,
    );
    if (entryQueue.contains(entry.plate)) {
      _showSnack('${entry.plate} zaten beklemede.', tone: BadgeTone.danger);
      return;
    }
    // Çevrimdışıysa ya da önde bekleyen kayıt varsa sıra bozulmasın diye doğrudan kuyruğa
    if (liveSync.status.value == LiveStatus.offline || entryQueue.items.value.isNotEmpty) {
      await _queueEntry(entry);
      return;
    }
    try {
      final data = await ApiClient.post('/parking/check-in', query: entry.toQuery());
      _showSnack('${entry.plate} sokağa eklendi.', tone: BadgeTone.success);
      scheduleRefresh();
      _warnDebt(entry.plate, data);
    } on ApiException catch (e) {
      if (e.isUnauthorized) return;
      // Ağ/zaman aşımı: AYNI requestId ile kuyruğa; istek ulaşmışsa sunucu tekrarı çift kayıt yapmaz
      if (e.statusCode == null) {
        await _queueEntry(entry);
        return;
      }
      // 409 ALREADY_PARKED / 400 INVALID_PLATE: sunucunun mesajı olduğu gibi gösterilir
      _showSnack(e.message, tone: BadgeTone.danger, duration: const Duration(seconds: 6));
      if (e.code == 'ALREADY_PARKED') scheduleRefresh();
      // Tip tarifede pasifleşmiş/bilinmiyor: liste tazelenir, görevli güncel seçeneklerle tekrar dener
      if (e.code == 'VEHICLE_TYPE_INACTIVE' || e.code == 'UNKNOWN_VEHICLE_TYPE') VehicleTypeStore.load();
    }
  }

  Future<void> _queueEntry(PendingEntry entry) async {
    final ok = await entryQueue.enqueue(entry);
    if (!ok) {
      _showSnack('${entry.plate} zaten beklemede.', tone: BadgeTone.danger);
      return;
    }
    _showSnack('${entry.plate} bağlantı gelince gönderilecek (beklemede).');
    unawaited(entryQueue.flush());
  }

  /// Giriş yapıldıktan sonra: ödenmemiş kaçak borcu varsa engellemeyen uyarı.
  void _warnDebt(String plate, dynamic body) {
    final debt = body is Map ? body['accumulatedDebt'] : null;
    if (debt is num && debt > 0 && mounted) showDebtAlert(context, plate, debt);
  }

  /// Kuyruktan giden kayıtların sonucu (bağlantı gelince arka planda gönderilenler).
  void _onQueueResult(EntrySendResult r) {
    if (!mounted || r.entry.street != _backendStreetEnum || r.entry.zoneId != widget.zoneId) return;
    final plate = r.entry.plate;
    switch (r.kind) {
      case EntryResultKind.sent:
        _showSnack('$plate sokağa eklendi.', tone: BadgeTone.success);
        scheduleRefresh();
        _warnDebt(plate, r.body);
      case EntryResultKind.conflict || EntryResultKind.rejected:
        final why = (r.message ?? 'Sunucu reddetti').replaceFirst(RegExp(r'[.\s]+$'), '');
        final text = '$plate kaydedilemedi: $why. Kayıt kuyruktan çıkarıldı.';
        setState(() => _failed.insert(
            0, _FailedAction(plate, _ActionKind.checkIn, null, 0, DateTime.now(), text, null, r.kind == EntryResultKind.conflict ? 409 : 400)));
        _showSnack(text, tone: BadgeTone.danger, duration: const Duration(seconds: 12));
        scheduleRefresh();
    }
  }

  // --- GERİ ALINABİLİR İŞLEMLER (Ödeme Al / Kaçtı) ---
  static const Duration _undoWindow = Duration(seconds: 5);

  /// Tarifeden canlı tahmin; tarife yoksa null.
  double? _estimate(String type, DateTime entry) {
    final t = TariffStore.current.value;
    return t == null ? null : estimateFee(t, type, entry, DateTime.now()).fee;
  }

  /// Detay sayfasında yöntem seçilip "Ödemeyi al" basıldığında: sayfa onay özetidir, ayrı diyalog yoktur.
  /// [amount] detay sayfasının gösterdiği sunucu tutarıdır (abonelikte 0).
  void _payFromDetail(dynamic vehicle, String method, double amount) {
    final String plate = vehicle['currentPlate'];
    if (!mounted || _pending.containsKey(plate)) return;
    _schedule(plate, _ActionKind.checkOut, method, amount);
  }

  Future<void> _requestRunaway(dynamic vehicle) async {
    final String plate = vehicle['currentPlate'];
    if (!mounted || _pending.containsKey(plate)) return;
    final type = vehicle['currentType'] ?? 'SMALL';
    final entry = DateTime.parse(vehicle['entryTime']).toLocal();
    final debt = _estimate(type, entry);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Kaçak işaretle'),
        content: Text('${formatPlate(plate)} kaçak olarak işaretlensin mi? Tahmini borç: ${debt == null ? '—' : formatMoney(debt)}'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Vazgeç')),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: ctx.bp.danger, foregroundColor: ctx.bp.onDanger, minimumSize: const Size(BpSize.tap, BpSize.tap)),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Kaçak işaretle'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    _schedule(plate, _ActionKind.runaway, null, debt ?? 0);
  }

  /// İşlem 5 sn BEKLETİLİR; ancak süre dolunca sunucuya gider. "Geri al" zamanlayıcıyı iptal eder, hiçbir şey gönderilmez.
  void _schedule(String plate, _ActionKind kind, String? method, double amount) {
    if (_pending.containsKey(plate)) return; // aynı işlem iki kez tetiklenemez
    final p = _PendingAction(plate, kind, method, amount);
    p.timer = Timer(_undoWindow, () => _execute(p));
    setState(() => _pending[plate] = p);
    _ensurePendingTick();
  }

  void _ensurePendingTick() {
    _pendingTick ??= Timer.periodic(const Duration(seconds: 1), (t) {
      if (!mounted || !_pending.values.any((p) => !p.sent)) {
        t.cancel();
        _pendingTick = null;
      }
      if (mounted) setState(() {});
    });
  }

  void _undo(String plate) {
    final p = _pending[plate];
    // İstek yola çıktıysa artık geri alınamaz
    if (p == null || p.sent) return;
    p.timer?.cancel();
    if (!mounted) return;
    setState(() => _pending.remove(plate));
    _showSnack('${formatPlate(plate)}: ${p.kind.label} iptal edildi.');
  }

  Future<void> _execute(_PendingAction p) async {
    p.sent = true;
    try {
      if (p.kind == _ActionKind.checkOut) {
        final data = await ApiClient.post('/parking/check-out', query: {
          'plate': p.plate,
          if (p.method != null) 'paymentMethod': p.method!,
        });
        final fee = (data is Map && data['fee'] is num) ? data['fee'] as num : p.amount;
        final method = (data is Map ? data['paymentMethod'] as String? : null) ?? p.method;
        _showSnack('${formatPlate(p.plate)} çıkış yapıldı — ${formatMoney(fee)} (${methodLabel(method)})', tone: BadgeTone.success);
      } else {
        final data = await ApiClient.post('/parking/runaway', query: {'plate': p.plate});
        final debt = (data is Map && data['debt'] is num) ? data['debt'] as num : p.amount;
        _showSnack('${formatPlate(p.plate)} kaçak olarak işaretlendi — borç ${formatMoney(debt)}', tone: BadgeTone.danger);
      }
      scheduleRefresh();
    } on ApiException catch (e) {
      if (!mounted) return;
      if (!e.isUnauthorized) {
        // Ödeme/kaçak asla kuyruğa alınmaz: bağlantı yoksa bunu açıkça söyle
        final msg = e.statusCode == null
            ? 'Bağlantı yok: ${p.kind == _ActionKind.checkOut ? 'ödeme' : 'kaçak kaydı'} kaydedilmedi. '
                'Ödeme ve kaçak işlemleri bekletilmez; bağlantı gelince tekrar deneyin.'
            : e.message;
        setState(() => _failed.insert(0, _FailedAction(p.plate, p.kind, p.method, p.amount, DateTime.now(), msg, e.code, e.statusCode)));
        _showSnack('${formatPlate(p.plate)}: $msg', tone: BadgeTone.danger, duration: const Duration(seconds: 7));
      }
      // Araç başka bir işlemle kapandıysa liste eskimiştir
      if (e.code == 'ALREADY_PROCESSED' || e.code == 'VEHICLE_NOT_FOUND') scheduleRefresh();
    } finally {
      if (mounted) setState(() => _pending.remove(p.plate));
    }
  }

  void _retry(_FailedAction f) {
    final p = _PendingAction(f.plate, f.kind, f.method, f.amount);
    setState(() {
      _failed.remove(f);
      _pending[f.plate] = p;
    });
    _execute(p); // kullanıcı işlemi zaten onaylamıştı: tekrar denemede bekleme yok
  }

  // --- ARAÇ GİRİŞİ (alt sayfa) ---
  Future<void> showEntryForm(String detectedPlate) async {
    if (LicenseStore.readOnly.value) {
      _showSnack(readOnlyReason, tone: BadgeTone.danger);
      return;
    }
    final result = await showEntrySheet(context, detectedPlate);
    if (result == null || !mounted) return;
    await checkInVehicle(result.plate, result.type, result.side);
  }

  void showSpotDetails(dynamic vehicle) {
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => VehicleDetailScreen(
        vehicle: vehicle,
        onPay: (method, amount) => _payFromDetail(vehicle, method, amount),
        onRunaway: () => _requestRunaway(vehicle),
        onCancelled: scheduleRefresh,
      ),
    ));
  }

  Future<void> scanPlate() async {
    if (LicenseStore.readOnly.value) {
      _showSnack(readOnlyReason, tone: BadgeTone.danger);
      return;
    }
    // Canlı kamera: aynı plaka 2 karede okununca kendiliğinden döner ('' = elle yaz, null = vazgeç)
    final plate = await Navigator.push<String>(context, MaterialPageRoute(builder: (_) => const PlateScannerScreen()));
    if (plate == null || !mounted) return;
    await showEntryForm(plate);
  }

  // --- GÖRÜNÜM ---
  // Çevrimdışı kuyrukta bekleyen giriş: sunucuya henüz gitmedi, ödeme alınamaz
  Widget _buildQueuedCard(PendingEntry e) {
    final c = context.bp;
    final inside = DateTime.now().difference(e.createdAt);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5, horizontal: 6),
      child: Material(
        color: c.warningSoft,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(BpSize.rMd), side: BorderSide(color: c.warning, width: 2)),
        child: InkWell(
          customBorder: RoundedRectangleBorder(borderRadius: BorderRadius.circular(BpSize.rMd)),
          onTap: () => _showSnack('Bağlantı gelince gönderilecek'),
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 104),
            child: Padding(
              padding: const EdgeInsets.all(10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Flexible(child: FittedBox(fit: BoxFit.scaleDown, child: PlateBadge(e.plate))),
                      const SizedBox(width: 4),
                      Icon(iconForCode(e.type), size: 20, color: c.text2),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(formatDurationText(inside), style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: c.text2)),
                  const SizedBox(height: 6),
                  // Dar kartta taşmasın diye küçülür
                  const FittedBox(
                      fit: BoxFit.scaleDown,
                      alignment: Alignment.centerLeft,
                      child: StatusBadge('Beklemede', tone: BadgeTone.warning, icon: Icons.cloud_upload_outlined)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildCarCard(dynamic vehicle) {
    if (vehicle == null) return const SizedBox.shrink();
    if (vehicle is PendingEntry) return _buildQueuedCard(vehicle);
    final c = context.bp;
    final String plate = vehicle['currentPlate'];
    final entry = DateTime.parse(vehicle['entryTime']).toLocal();
    final inside = DateTime.now().difference(entry);
    final isLong = inside >= _longStay;
    final isPending = _pending.containsKey(plate);
    final isSub = vehicle['hasSubscription'] == true;
    final debt = vehicle['accumulatedDebt'];
    final est = isSub ? 0.0 : _estimate((vehicle['currentType'] ?? 'SMALL').toString(), entry);
    final feeText = isSub ? 'Abone' : (est == null ? '—' : formatMoney(est));

    // Öncelik: bekleyen işlem > abone > uzun kalış
    final Color border = isPending ? c.warning : (isSub ? c.sub : (isLong ? c.warning : c.border));
    final Color bg = isPending ? c.warningSoft : (isSub ? c.subSoft : c.surface);
    final Color durColor = isLong ? c.warning : c.text2;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5, horizontal: 6),
      child: Semantics(
        button: true,
        child: Material(
          color: bg,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(BpSize.rMd), side: BorderSide(color: border, width: 2)),
          child: InkWell(
            customBorder: RoundedRectangleBorder(borderRadius: BorderRadius.circular(BpSize.rMd)),
            onTap: isPending ? null : () => showSpotDetails(vehicle),
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 104),
              child: Padding(
                padding: const EdgeInsets.all(10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Flexible(child: FittedBox(fit: BoxFit.scaleDown, child: PlateBadge(plate))),
                        const SizedBox(width: 4),
                        Icon(iconForCode(vehicle['currentType']), size: 20, color: c.text2),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      crossAxisAlignment: CrossAxisAlignment.baseline,
                      textBaseline: TextBaseline.alphabetic,
                      children: [
                        Flexible(
                          child: Text(formatDurationText(inside),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: durColor)),
                        ),
                        const SizedBox(width: 4),
                        Text(feeText,
                            style: TextStyle(
                                fontSize: 18, fontWeight: FontWeight.w800, color: isSub && !isPending ? c.onSubSoft : null)),
                      ],
                    ),
                    if (isPending || (debt is num && debt > 0) || isSub) ...[
                      const SizedBox(height: 6),
                      Wrap(spacing: 4, runSpacing: 4, children: [
                        if (isPending) const StatusBadge('Ödeme sürüyor', tone: BadgeTone.warning),
                        if (debt is num && debt > 0) StatusBadge('Eski borç ${formatMoney(debt)}', tone: BadgeTone.danger),
                        if (isSub) const StatusBadge('Abone', tone: BadgeTone.sub),
                      ]),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  // Gönderilmeyi bekleyen işlemler (5 sn içinde geri alınabilir): koyu bilgi çubuğu
  Widget _buildPendingBars() {
    final c = context.bp;
    final waiting = _pending.values.where((p) => !p.sent).toList();
    if (waiting.isEmpty) return const SizedBox.shrink();
    return Column(
      children: waiting.map((p) {
        final left = max(0, (_undoWindow.inMilliseconds - DateTime.now().difference(p.createdAt).inMilliseconds + 999) ~/ 1000);
        final what = p.kind == _ActionKind.checkOut
            ? 'Ödeme ${formatMoney(p.amount)} ${methodLabel(p.method)}'
            : 'Kaçak ${formatMoney(p.amount)}';
        return Container(
          margin: const EdgeInsets.only(top: 8),
          padding: const EdgeInsets.fromLTRB(14, 4, 4, 4),
          decoration: BoxDecoration(color: c.text, borderRadius: BorderRadius.circular(BpSize.rMd)),
          child: Row(
            children: [
              Expanded(
                child: Text('${formatPlate(p.plate)} · $what · $left sn', style: TextStyle(color: c.bg, fontSize: 15)),
              ),
              TextButton(
                style: TextButton.styleFrom(foregroundColor: c.bg),
                onPressed: () => _undo(p.plate),
                child: const Text('Geri al', style: TextStyle(decoration: TextDecoration.underline, fontWeight: FontWeight.w800)),
              ),
            ],
          ),
        );
      }).toList(),
    );
  }

  // Sunucunun reddettiği / ulaşamadığımız işlemler
  Widget _buildFailedPanel() {
    if (_failed.isEmpty) return const SizedBox.shrink();
    final c = context.bp;
    return Container(
      width: double.infinity,
      color: c.dangerSoft,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          InkWell(
            onTap: () => setState(() => _failedExpanded = !_failedExpanded),
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: BpSize.tap),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: Row(
                  children: [
                    Icon(Icons.error_outline, color: c.onDangerSoft, size: 20),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text('Başarısız işlemler (${_failed.length})',
                          style: TextStyle(color: c.onDangerSoft, fontWeight: FontWeight.w700, fontSize: 15)),
                    ),
                    Icon(_failedExpanded ? Icons.expand_less : Icons.expand_more, color: c.onDangerSoft),
                  ],
                ),
              ),
            ),
          ),
          if (_failedExpanded)
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 240),
              child: ListView(
                shrinkWrap: true,
                padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
                children: _failed.map(_buildFailedRow).toList(),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildFailedRow(_FailedAction f) {
    final c = context.bp;
    return Card(
      margin: const EdgeInsets.only(bottom: 6),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 4),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
                '${formatPlate(f.plate)} · ${f.kind.label}${f.method != null ? ' (${methodLabel(f.method)})' : ''} · ${formatHm(f.time)}',
                style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
            const SizedBox(height: 2),
            Text(f.message, style: TextStyle(color: c.danger, fontSize: 14)),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                if (f.retryable && !_pending.containsKey(f.plate))
                  TextButton(onPressed: () => _retry(f), child: const Text('Tekrar dene')),
                TextButton(onPressed: () => setState(() => _failed.remove(f)), child: const Text('Kapat')),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildRoadDivider() {
    final c = context.bp;
    return Container(
      width: 36,
      color: c.asphalt,
      child: Center(
        child: Container(width: 3, margin: const EdgeInsets.symmetric(vertical: 14), color: c.asphaltLine.withValues(alpha: 0.8)),
      ),
    );
  }

  List<dynamic> _applyFilters(List<dynamic> list) {
    final q = _search.text.toUpperCase().replaceAll(RegExp(r'[\s-]'), '');
    var out = list.where((v) {
      if (_hideSubscribers && v['hasSubscription'] == true) return false;
      if (q.isNotEmpty && !v['currentPlate'].toString().toUpperCase().contains(q)) return false;
      return true;
    }).toList();
    if (_sortLongest) {
      // Giriş zamanı eski olan (en uzun kalan) önce; ISO metinler zaman sırasına uyar
      out.sort((a, b) => a['entryTime'].toString().compareTo(b['entryTime'].toString()));
    }
    return out;
  }

  Widget _filterChip(String label, IconData icon, bool selected, ValueChanged<bool> onSelected) {
    return FilterChip(
      label: Text(label),
      avatar: Icon(icon, size: 18),
      selected: selected,
      showCheckmark: false,
      materialTapTargetSize: MaterialTapTargetSize.padded,
      visualDensity: VisualDensity.standard,
      onSelected: onSelected,
    );
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final c = context.bp;

    // Araçları "Yön"lerine göre iki farklı listeye ayırıyoruz
    final all = activeVehicles;
    // Kuyrukta bekleyen girişler kendi kaldırımının başında görünür
    final q = _search.text.toUpperCase().replaceAll(RegExp(r'[\s-]'), '');
    final queued = entryQueue.items.value
        .where((e) => e.street == _backendStreetEnum && e.zoneId == widget.zoneId && (q.isEmpty || e.plate.contains(q)))
        .toList();
    final leftVehicles = [
      ...queued.where((e) => e.side != 'RIGHT'),
      ..._applyFilters(all.where((v) => v['side'] == 'LEFT' || v['side'] == null).toList()),
    ];
    final rightVehicles = [
      ...queued.where((e) => e.side == 'RIGHT'),
      ..._applyFilters(all.where((v) => v['side'] == 'RIGHT').toList()),
    ];

    // Hangi taraf daha uzunsa, otoparkın uzunluğu o kadar olacak! (Sınırsız Büyüme)
    final int rowCount = max(leftVehicles.length, rightVehicles.length);
    final title = zoneTitle(widget.streetName, widget.zoneName);
    final subCount = all.where((v) => v['hasSubscription'] == true).length;
    final upFrom = TariffStore.current.value?.upcoming?.effectiveFrom;

    return ColoredBox(
      color: c.bg,
      child: Stack(
        children: [
          Column(
            children: [
              BpHeader(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    LiveConnectionBanner(pending: _pending.length),
                    const SizedBox(height: 10),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.baseline,
                      textBaseline: TextBaseline.alphabetic,
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Flexible(
                          child: Text(title,
                              maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
                        ),
                        const SizedBox(width: 8),
                        Text('${all.length} araç${subCount > 0 ? ' · $subCount abone' : ''}',
                            style: TextStyle(fontSize: 14, color: c.text2)),
                      ],
                    ),
                    if (upFrom != null) ...[
                      const SizedBox(height: 6),
                      Row(children: [
                        Icon(Icons.info_outline, size: 16, color: c.text2),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text("${formatDayMonth(upFrom)} ${formatHm(upFrom)}'den itibaren yeni tarife",
                              style: TextStyle(fontSize: 13, color: c.text2)),
                        ),
                      ]),
                    ],
                    const SizedBox(height: 10),
                    BpSearchField(controller: _search, hint: 'Plaka ara', onChanged: (_) => setState(() {})),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      runSpacing: 0,
                      children: [
                        _filterChip('En uzun önce', Icons.schedule, _sortLongest, (v) => setState(() => _sortLongest = v)),
                        _filterChip('Aboneleri gizle', Icons.card_membership, _hideSubscribers, (v) => setState(() => _hideSubscribers = v)),
                      ],
                    ),
                  ],
                ),
              ),
              _buildFailedPanel(),
              Padding(
                padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
                child: Row(
                  children: [
                    Expanded(
                        child: Text('SOL KALDIRIM · ${leftVehicles.length}',
                            style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: c.text2))),
                    const SizedBox(width: 36),
                    Expanded(
                        child: Text('SAĞ KALDIRIM · ${rightVehicles.length}',
                            textAlign: TextAlign.end, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: c.text2))),
                  ],
                ),
              ),
              Expanded(
                child: rowCount == 0
                    ? _buildEmpty(c, all.isEmpty)
                    : ListView.builder(
                        padding: const EdgeInsets.fromLTRB(6, 0, 6, 180),
                        itemCount: rowCount,
                        itemBuilder: (context, index) {
                          // Her satırda soldan ve sağdan sıradaki aracı alıyoruz
                          final leftVehicle = index < leftVehicles.length ? leftVehicles[index] : null;
                          final rightVehicle = index < rightVehicles.length ? rightVehicles[index] : null;

                          return IntrinsicHeight(
                            child: Row(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                Expanded(child: _buildCarCard(leftVehicle)), // Sol Kaldırım (Geldikçe dolar)
                                _buildRoadDivider(), // Asfalt
                                Expanded(child: _buildCarCard(rightVehicle)), // Sağ Kaldırım (Geldikçe dolar)
                              ],
                            ),
                          );
                        },
                      ),
              ),
            ],
          ),
          Positioned(
            left: 12,
            right: 12,
            bottom: 12,
            child: Column(
              key: _actionsKey,
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    ReadOnlyGate(
                      builder: (_, ro) => Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Tooltip(
                            message: ro ? readOnlyReason : 'Elle araç ekle',
                            child: SizedBox(
                              width: BpSize.tapLg,
                              height: BpSize.tapLg,
                              child: OutlinedButton(
                                style: OutlinedButton.styleFrom(
                                  padding: EdgeInsets.zero,
                                  foregroundColor: c.primary,
                                  side: BorderSide(color: ro ? c.border : c.primary, width: 2),
                                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(BpSize.rLg)),
                                ),
                                onPressed: ro ? null : () => showEntryForm(''),
                                child: const Icon(Icons.add, size: 26),
                              ),
                            ),
                          ),
                          const SizedBox(width: 10),
                          Tooltip(
                            message: ro ? readOnlyReason : 'Plaka tara',
                            child: FloatingActionButton.extended(
                              heroTag: null,
                              // FAB onPressed null olunca soluklaşmaz; pasif görünümü elle verilir
                              backgroundColor: ro ? c.surface2 : null,
                              foregroundColor: ro ? c.text2 : null,
                              elevation: ro ? 0 : null,
                              onPressed: ro ? null : scanPlate,
                              icon: const Icon(Icons.photo_camera_outlined),
                              label: const Text('Plaka tara'),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                _buildPendingBars(),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildEmpty(BilParkColors c, bool streetEmpty) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(streetEmpty ? Icons.local_parking : Icons.search_off, size: 56, color: c.borderStrong),
            const SizedBox(height: 12),
            Text(
              streetEmpty ? 'Sokak boş. Araç eklemek için “Plaka tara” veya + düğmesini kullanın.' : 'Aramanıza uyan araç yok.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 16, color: c.text2),
            ),
          ],
        ),
      ),
    );
  }
}
