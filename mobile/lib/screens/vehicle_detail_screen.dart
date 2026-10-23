import 'dart:async';
import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';
import '../core/api_client.dart';
import '../core/format.dart';
import '../core/tariff.dart';
import '../core/theme.dart';
import '../widgets/bp_header.dart';
import '../widgets/bp_snack.dart';
import '../widgets/day_breakdown.dart';
import '../widgets/plate_badge.dart';
import '../widgets/status_badge.dart';
import '../widgets/toggle_tile.dart';

/// Araç detayı ve ödeme sayfası. Sayfanın kendisi onay özetidir: yöntem burada seçilir,
/// "Ödemeyi al" sayfayı kapatıp [onPay] ile 5 sn'lik geri alınabilir işlemi başlatır.
class VehicleDetailScreen extends StatefulWidget {
  final dynamic vehicle;

  /// Ödeme yöntemi ve sunucu tutarıyla çağrılır (abonelikte tutar 0).
  final void Function(String method, double amount) onPay;
  final VoidCallback onRunaway;

  /// Ücret önizlemesi (GET /parking/fee-preview). Testte değiştirilebilir.
  final Future<Map<String, dynamic>> Function(String plate)? loadPreview;

  const VehicleDetailScreen({
    super.key,
    required this.vehicle,
    required this.onPay,
    required this.onRunaway,
    this.loadPreview,
  });

  @override
  State<VehicleDetailScreen> createState() => _VehicleDetailScreenState();
}

class _VehicleDetailScreenState extends State<VehicleDetailScreen> {
  late final String _plate = widget.vehicle['currentPlate'].toString();
  late final String _type = (widget.vehicle['currentType'] ?? 'SMALL').toString();
  late final DateTime _entry = DateTime.parse(widget.vehicle['entryTime']).toLocal();
  bool? _hasSub;
  late String _method;
  bool _picked = false;
  Timer? _ticker;

  // Sunucu ücret önizlemesi
  FeeResult? _result;
  bool _loading = true;
  bool _fetching = false;
  String? _error;
  DateTime? _lastFetch;
  double? _estAtFetch; // son istekteki yerel tahmin: aynı tahmin için tekrar istek atılmaz

  bool get _isLarge => _type == 'LARGE';

  Future<Map<String, dynamic>> _defaultPreview(String plate) async {
    final data = await ApiClient.get('/parking/fee-preview', query: {'plate': plate});
    return Map<String, dynamic>.from(data as Map);
  }

  double? _estimateNow() {
    final t = TariffStore.current.value;
    return t == null ? null : estimateFee(t, _type, _entry, DateTime.now()).fee;
  }

  @override
  void initState() {
    super.initState();
    final known = widget.vehicle['hasSubscription'];
    _hasSub = known is bool ? known : null;
    _method = _hasSub == true ? 'SUBSCRIPTION' : 'CASH';
    TariffStore.current.addListener(_onTariff);
    _fetch();
    // Her saniye yalnızca "İçeride" saati yenilenir; ağ isteği ancak saat sınırı aşılınca atılır
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() {});
      _maybeRefresh();
    });
  }

  @override
  void dispose() {
    _ticker?.cancel();
    TariffStore.current.removeListener(_onTariff);
    super.dispose();
  }

  void _onTariff() {
    if (mounted) _fetch(silent: true);
  }

  /// Yerel tahmin son sunucu tutarından ayrıldıysa (saat/ücretli pencere sınırı) en çok 30 sn'de bir yeniler.
  void _maybeRefresh() {
    final res = _result;
    final est = _estimateNow();
    if (_fetching || res == null || est == null) return;
    if (est == res.fee || est == _estAtFetch) return;
    final last = _lastFetch;
    if (last != null && DateTime.now().difference(last) < const Duration(seconds: 30)) return;
    _fetch(silent: true);
  }

  Future<void> _fetch({bool silent = false}) async {
    if (_fetching) return;
    _fetching = true;
    _lastFetch = DateTime.now();
    _estAtFetch = _estimateNow();
    if (!silent || _result == null) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    try {
      final json = await (widget.loadPreview ?? _defaultPreview)(_plate);
      if (!mounted) return;
      final res = FeeResult.fromJson(json);
      final sub = json['hasSubscription'];
      setState(() {
        _result = res;
        _loading = false;
        _error = null;
        if (sub is bool) {
          _hasSub = sub;
          if (!_picked && sub) _method = 'SUBSCRIPTION';
          if (!sub && _method == 'SUBSCRIPTION') _method = 'CASH';
        }
      });
    } on ApiException catch (e) {
      if (!mounted || e.isUnauthorized) return;
      if (e.code == 'VEHICLE_NOT_FOUND' || e.statusCode == 404) {
        ScaffoldMessenger.of(context).showSnackBar(
            bpSnackBar(context, '${formatPlate(_plate)} artık içeride değil.', tone: BadgeTone.warning));
        Navigator.of(context).maybePop();
        return;
      }
      _onError(e.message);
    } catch (_) {
      if (mounted) _onError('Ücret hesabı alınamadı.');
    } finally {
      _fetching = false;
    }
  }

  void _onError(String msg) {
    setState(() {
      _loading = false;
      // Eldeki sunucu sonucu varsa korunur; sessiz yenileme hatası ekranı bozmaz
      if (_result == null) _error = msg;
    });
  }

  void _pick(String m) => setState(() {
        _method = m;
        _picked = true;
      });

  void _pay(double amount) {
    Navigator.pop(context);
    widget.onPay(_method, amount);
  }

  void _runaway() {
    Navigator.pop(context);
    widget.onRunaway();
  }

  void _showQr() {
    final c = context.bp;
    final url = 'https://kadir0642.github.io/bilpark-parking-system/web/customer-payment.html?plate=$_plate';
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('${formatPlate(_plate)} hızlı ödeme', textAlign: TextAlign.center),
        content: SizedBox(
          width: 250,
          height: 250,
          child: QrImageView(
            data: url,
            version: QrVersions.auto,
            size: 250,
            backgroundColor: c.plateBg,
            eyeStyle: QrEyeStyle(eyeShape: QrEyeShape.square, color: c.plateText),
            dataModuleStyle: QrDataModuleStyle(dataModuleShape: QrDataModuleShape.square, color: c.plateText),
          ),
        ),
        actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Kapat'))],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final now = DateTime.now();
    final diff = now.difference(_entry);
    final tariff = TariffStore.current.value;
    final res = _result;
    final isSubPay = _method == 'SUBSCRIPTION';
    // Sunucu tutarı; önizleme alınamadıysa tahmin (düğmede "tahmini" yazar)
    final double? serverFee = res?.fee;
    final double? estFee = _estimateNow();
    final bool isEstimate = serverFee == null && !_loading;
    final double? payable = isSubPay ? 0.0 : (serverFee ?? (isEstimate ? estFee : null));
    final amountText = payable == null ? '—' : formatMoney(payable);
    final debt = widget.vehicle['accumulatedDebt'];
    final side = widget.vehicle['side'] == 'RIGHT' ? 'Sağ kaldırım' : 'Sol kaldırım';
    final subAvailable = _hasSub != false;

    return Scaffold(
      backgroundColor: c.bg,
      body: Column(
        children: [
          BpHeader(
            padding: const EdgeInsets.fromLTRB(8, 4, 16, 8),
            child: Row(
              children: [
                IconButton(tooltip: 'Geri', icon: const Icon(Icons.arrow_back), onPressed: () => Navigator.maybePop(context)),
                const Text('Araç detayı', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              ],
            ),
          ),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(BpSize.screenPad),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Align(alignment: Alignment.centerLeft, child: PlateBadge(_plate, size: PlateSize.large)),
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      Icon(_isLarge ? Icons.local_shipping_outlined : Icons.directions_car_outlined, size: 20, color: c.text2),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          '${_isLarge ? 'Kamyonet' : 'Otomobil'} · $side · Giriş ${formatDayMonth(_entry)} ${formatHm(_entry)}',
                          style: TextStyle(fontSize: 14, color: c.text2),
                        ),
                      ),
                    ],
                  ),
                  if (_hasSub == true || (debt is num && debt > 0)) ...[
                    const SizedBox(height: 8),
                    Wrap(spacing: 8, runSpacing: 4, children: [
                      if (_hasSub == true) const StatusBadge('Abone', tone: BadgeTone.sub),
                      if (debt is num && debt > 0) StatusBadge('Eski borç ${formatMoney(debt)}', tone: BadgeTone.danger),
                    ]),
                  ],
                  const SizedBox(height: 14),
                  Row(
                    children: [
                      Expanded(
                        child: _tile(c, 'İçeride', formatClock(diff), bg: c.surface, fg: c.text, border: c.border),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: _tile(c, 'Ödenecek tutar', amountText,
                            bg: c.successSoft, fg: c.onSuccessSoft, border: c.success),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  DayBreakdown(
                    segments: res?.segments,
                    fee: serverFee,
                    loading: _loading && res == null,
                    error: res == null ? _error : null,
                    onRetry: () => _fetch(),
                    footnote: tariff == null ? null : tariffSummary(tariff, _type),
                  ),
                  const SizedBox(height: 16),
                  const Text('Ödeme yöntemi', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(
                        child: BpToggleTile(
                          label: 'Nakit',
                          icon: Icons.payments_outlined,
                          selected: _method == 'CASH',
                          onTap: () => _pick('CASH'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: BpToggleTile(
                          label: 'POS',
                          icon: Icons.credit_card,
                          selected: _method == 'POS',
                          onTap: () => _pick('POS'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: BpToggleTile(
                          label: 'Abonelik',
                          note: subAvailable ? null : 'abonelik yok',
                          selected: _method == 'SUBSCRIPTION',
                          onTap: subAvailable ? () => _pick('SUBSCRIPTION') : null,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          Container(
            decoration: BoxDecoration(color: c.surface, border: Border(top: BorderSide(color: c.border))),
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
            child: SafeArea(
              top: false,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  FilledButton(
                    style: FilledButton.styleFrom(backgroundColor: c.success, foregroundColor: c.onSuccess),
                    onPressed: payable == null ? null : () => _pay(payable),
                    child: Text('Ödemeyi al · $amountText ${methodLabel(_method)}${isEstimate && !isSubPay ? ' (tahmini)' : ''}',
                        style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
                  ),
                  const SizedBox(height: 10),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: _showQr,
                          icon: const Icon(Icons.qr_code_2),
                          label: const Text('QR göster'),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: OutlinedButton.icon(
                          style: OutlinedButton.styleFrom(
                            foregroundColor: c.danger,
                            side: BorderSide(color: c.danger, width: 2),
                          ),
                          onPressed: _runaway,
                          icon: const Icon(Icons.warning_amber_rounded),
                          label: const Text('Kaçtı'),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _tile(BilParkColors c, String label, String value, {required Color bg, required Color fg, required Color border}) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: bg, border: Border.all(color: border), borderRadius: BorderRadius.circular(BpSize.rMd)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: fg)),
          FittedBox(
            fit: BoxFit.scaleDown,
            alignment: Alignment.centerLeft,
            child: Text(value,
                style: TextStyle(
                    fontSize: 26, fontWeight: FontWeight.w800, color: fg, fontFeatures: const [FontFeature.tabularFigures()])),
          ),
        ],
      ),
    );
  }
}
