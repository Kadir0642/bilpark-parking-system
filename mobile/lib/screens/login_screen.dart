import 'package:flutter/material.dart';
import '../core/api_client.dart';
import '../core/constants.dart';
import '../core/theme.dart';
import '../main.dart';
import '../widgets/bp_header.dart';

/// Saha (cadde + bölüm) seçimi: tüm sahalar tek ekranda büyük kartlar olarak listelenir.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _ZoneChoice {
  final String street; // görünen cadde adı
  final dynamic zone; // null: bölümsüz (tüm cadde)
  const _ZoneChoice(this.street, this.zone);

  String get title {
    final z = zone == null ? 'Tüm cadde' : (zone['zoneName'] ?? 'Bölüm').toString();
    return z;
  }

  String get fullTitle => '${street.replaceAll(' Caddesi', '')} — $title';
  String? get landmark {
    final l = zone == null ? null : zone['landmarkNames'];
    return l is String && l.isNotEmpty ? l : null;
  }
}

class _LoginScreenState extends State<LoginScreen> {
  static const String _region = 'Bilecik';
  static const String _neighborhood = 'Merkez';
  // BİLPARK 2.0 gerçek veritabanı haritası
  static const List<String> _streets = ['Tevfik Bey Caddesi', 'Ali Rıza Özkay Caddesi', 'Cumhuriyet Caddesi'];

  final Map<String, List<_ZoneChoice>> _choices = {};
  final Map<String, String> _errors = {};
  bool _loading = true;
  _ZoneChoice? _selected;

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  Future<void> _loadAll() async {
    setState(() {
      _loading = true;
      _selected = null;
      _errors.clear();
      _choices.clear();
    });
    await Future.wait(_streets.map(_loadStreet));
    if (!mounted) return;
    setState(() => _loading = false);
  }

  Future<void> _loadStreet(String street) async {
    try {
      final data = await ApiClient.get('/zones/by-street', query: {'street': AppConstants.toBackendEnum(street)});
      final zones = data is List ? data : <dynamic>[];
      _choices[street] = zones.isEmpty ? [_ZoneChoice(street, null)] : zones.map((z) => _ZoneChoice(street, z)).toList();
    } on ApiException catch (e) {
      _errors[street] = e.message;
    }
  }

  int get _zoneCount => _choices.values.fold(0, (a, l) => a + l.length);

  void _start() {
    final s = _selected;
    if (s == null) return;
    Navigator.pushReplacement(
      context,
      MaterialPageRoute(
        builder: (context) => MainContainer(
          region: _region,
          neighborhood: _neighborhood,
          street: s.street,
          zoneId: s.zone != null ? s.zone['id'] : null,
          zoneName: s.zone != null ? s.zone['zoneName'] : null,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    return Scaffold(
      backgroundColor: c.bg,
      body: Column(
        children: [
          BpHeader(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Çalışacağınız sahayı seçin', style: TextStyle(fontSize: 24, fontWeight: FontWeight.w700)),
                const SizedBox(height: 4),
                Row(
                  children: [
                    Icon(Icons.location_on_outlined, size: 18, color: c.text2),
                    const SizedBox(width: 6),
                    Text(_loading ? '$_region · $_neighborhood' : '$_region · $_neighborhood · $_zoneCount saha',
                        style: TextStyle(fontSize: 15, color: c.text2)),
                  ],
                ),
              ],
            ),
          ),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : ListView(
                    padding: const EdgeInsets.all(BpSize.screenPad),
                    children: [
                      for (final street in _streets) ...[
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: Text(street, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: c.text2)),
                        ),
                        if (_errors.containsKey(street))
                          _errorCard(c, _errors[street]!)
                        else
                          for (final choice in _choices[street] ?? const <_ZoneChoice>[])
                            Padding(padding: const EdgeInsets.only(bottom: 8), child: _zoneCard(c, choice)),
                        const SizedBox(height: 12),
                      ],
                    ],
                  ),
          ),
          Container(
            decoration: BoxDecoration(color: c.surface, border: Border(top: BorderSide(color: c.border))),
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
            child: SafeArea(
              top: false,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text.rich(TextSpan(style: TextStyle(fontSize: 14, color: c.text2), children: [
                    const TextSpan(text: 'Seçilen: '),
                    TextSpan(
                        text: _selected?.fullTitle ?? 'henüz seçilmedi',
                        style: TextStyle(color: c.text, fontWeight: FontWeight.w700)),
                  ])),
                  const SizedBox(height: 8),
                  FilledButton(onPressed: _selected == null ? null : _start, child: const Text('Vardiyayı başlat')),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _errorCard(BilParkColors c, String message) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: c.dangerSoft,
        border: Border.all(color: c.danger),
        borderRadius: BorderRadius.circular(BpSize.rMd),
      ),
      child: Row(
        children: [
          Icon(Icons.error_outline, color: c.onDangerSoft),
          const SizedBox(width: 10),
          Expanded(child: Text(message, style: TextStyle(fontSize: 14, color: c.onDangerSoft))),
          TextButton(onPressed: _loadAll, child: const Text('Tekrar dene')),
        ],
      ),
    );
  }

  Widget _zoneCard(BilParkColors c, _ZoneChoice choice) {
    final selected = identical(_selected, choice);
    return Semantics(
      button: true,
      selected: selected,
      child: Material(
        color: selected ? c.primarySoft : c.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(BpSize.rMd),
          side: BorderSide(color: selected ? c.primary : c.border, width: selected ? 2 : 1),
        ),
        child: InkWell(
          customBorder: RoundedRectangleBorder(borderRadius: BorderRadius.circular(BpSize.rMd)),
          onTap: () => setState(() => _selected = choice),
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 64),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              child: Row(
                children: [
                  Container(
                    width: 24,
                    height: 24,
                    decoration: BoxDecoration(
                        shape: BoxShape.circle, border: Border.all(color: selected ? c.primary : c.borderStrong, width: 2)),
                    alignment: Alignment.center,
                    child: selected
                        ? Container(width: 12, height: 12, decoration: BoxDecoration(color: c.primary, shape: BoxShape.circle))
                        : null,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(choice.title,
                            style: TextStyle(
                                fontSize: 17, fontWeight: FontWeight.w700, color: selected ? c.onPrimarySoft : c.text)),
                        if (choice.landmark != null)
                          Text(choice.landmark!, style: TextStyle(fontSize: 14, color: selected ? c.onPrimarySoft : c.text2)),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
