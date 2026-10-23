import 'dart:async';
import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';
import '../core/constants.dart';
import '../core/format.dart';
import '../core/theme.dart';
import '../widgets/bp_header.dart';
import '../widgets/day_breakdown.dart';
import '../widgets/plate_badge.dart';
import '../widgets/status_badge.dart';
import '../widgets/toggle_tile.dart';

/// Araç detayı ve ödeme sayfası. Sayfanın kendisi onay özetidir: yöntem burada seçilir,
/// "Ödemeyi al" sayfayı kapatıp [onPay] ile 5 sn'lik geri alınabilir işlemi başlatır.
class VehicleDetailScreen extends StatefulWidget {
  final dynamic vehicle;

  /// Sunucu aboneliği bildirmediyse çağrılır (`/subscription/check`). true/false; belirlenemezse null.
  final Future<bool?> Function() loadHasSubscription;

  /// Canlı ücret (sunucu mantığının aynısı).
  final double Function(String type, DateTime entry) feeOf;
  final void Function(String method) onPay;
  final VoidCallback onRunaway;

  const VehicleDetailScreen({
    super.key,
    required this.vehicle,
    required this.loadHasSubscription,
    required this.feeOf,
    required this.onPay,
    required this.onRunaway,
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

  bool get _isLarge => _type == 'LARGE';

  @override
  void initState() {
    super.initState();
    final known = widget.vehicle['hasSubscription'];
    _hasSub = known is bool ? known : null;
    _method = _hasSub == true ? 'SUBSCRIPTION' : 'CASH';
    if (_hasSub == null) {
      widget.loadHasSubscription().then((v) {
        if (!mounted) return;
        setState(() {
          _hasSub = v;
          if (!_picked && v == true) _method = 'SUBSCRIPTION';
          if (v == false && _method == 'SUBSCRIPTION') _method = 'CASH';
        });
      });
    }
    // Her saniye yalnızca bu sayfa yenilenir
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _ticker?.cancel();
    super.dispose();
  }

  void _pick(String m) => setState(() {
        _method = m;
        _picked = true;
      });

  void _pay() {
    Navigator.pop(context);
    widget.onPay(_method);
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
    final fee = widget.feeOf(_type, _entry);
    final amount = _method == 'SUBSCRIPTION' ? 0.0 : fee;
    final debt = widget.vehicle['accumulatedDebt'];
    final base = _isLarge ? AppConstants.largeBaseFee : AppConstants.smallBaseFee;
    final extra = _isLarge ? AppConstants.largeExtraFee : AppConstants.smallExtraFee;
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
                        child: _tile(c, 'Ödenecek tutar', formatMoney(amount),
                            bg: c.successSoft, fg: c.onSuccessSoft, border: c.success),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  DayBreakdown(
                    total: diff,
                    breakdown: widget.vehicle['breakdown'] is List ? widget.vehicle['breakdown'] as List : null,
                    footnote:
                        'Tarife: ilk saat ${formatMoney(base)}, sonraki her saat ${formatMoney(extra)} · ilk ${AppConstants.gracePeriodSeconds ~/ 60} dk ücretsiz',
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
                    onPressed: _pay,
                    child: Text('Ödemeyi al · ${formatMoney(amount)} ${methodLabel(_method)}',
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
