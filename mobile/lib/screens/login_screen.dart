import 'package:flutter/material.dart';
import '../core/api_client.dart';
import '../core/streets.dart';
import '../core/theme.dart';
import '../main.dart';
import '../widgets/bp_header.dart';

/// Saha (cadde + bölüm) seçimi: tüm sahalar tek ekranda büyük kartlar olarak listelenir.
class LoginScreen extends StatefulWidget {
  /// Cadde listesini getirir (testlerde ağsız değiştirilebilir). Varsayılan: GET /streets.
  final Future<List<StreetInfo>> Function({bool force})? loader;

  const LoginScreen({super.key, this.loader});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _ZoneChoice {
  final StreetInfo street;
  final ZoneInfo? zone; // null: bölümsüz (tüm cadde)
  const _ZoneChoice(this.street, this.zone);

  String get title => zone == null ? 'Tüm cadde' : (zone!.zoneName.isEmpty ? 'Bölüm' : zone!.zoneName);

  String get fullTitle => '${street.displayName.replaceAll(' Caddesi', '')} — $title';

  /// Tohum verideki "[Sol Landmark - Güncelleyin]" gibi yer tutucular gizlenir.
  String? get landmark {
    final l = zone?.landmarks;
    return l == null || l.startsWith('[') ? null : l;
  }
}

class _LoginScreenState extends State<LoginScreen> {
  List<_ZoneChoice> _flat = const [];
  List<StreetInfo> _streets = const [];
  String? _error;
  bool _loading = true;
  _ZoneChoice? _selected;

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  Future<void> _loadAll({bool force = false}) async {
    setState(() {
      _loading = true;
      _selected = null;
      _error = null;
    });
    List<StreetInfo> list;
    try {
      list = await (widget.loader ?? StreetStore.load)(force: force);
    } on ApiException catch (e) {
      list = const [];
      _error = e.message;
    } catch (_) {
      list = const [];
    }
    if (!mounted) return;
    final sorted = [...list]..sort((a, b) => a.sortOrder.compareTo(b.sortOrder));
    setState(() {
      _streets = sorted;
      _flat = [
        for (final st in sorted)
          if (st.zones.isEmpty) _ZoneChoice(st, null) else for (final z in st.zones) _ZoneChoice(st, z),
      ];
      if (sorted.isEmpty) _error ??= 'Saha listesi alınamadı. Bağlantınızı kontrol edin.';
      _loading = false;
    });
  }

  List<_ZoneChoice> _choicesOf(StreetInfo st) => [for (final ch in _flat) if (ch.street.id == st.id && ch.street.code == st.code) ch];

  void _start() {
    final s = _selected;
    if (s == null) return;
    Navigator.pushReplacement(
      context,
      MaterialPageRoute(
        builder: (context) => MainContainer(
          streetCode: s.street.code,
          streetName: s.street.displayName,
          zoneId: s.zone?.id,
          zoneName: s.zone?.zoneName,
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
                    Text(_loading ? 'Sahalar yükleniyor' : '${_flat.length} saha',
                        style: TextStyle(fontSize: 15, color: c.text2)),
                  ],
                ),
              ],
            ),
          ),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _error != null
                    ? ListView(padding: const EdgeInsets.all(BpSize.screenPad), children: [_errorCard(c, _error!)])
                    : ListView(
                        padding: const EdgeInsets.all(BpSize.screenPad),
                        children: [
                          for (final st in _streets) ...[
                            Padding(
                              padding: const EdgeInsets.only(bottom: 8),
                              child: Text(st.displayName,
                                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: c.text2)),
                            ),
                            for (final choice in _choicesOf(st))
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
          TextButton(onPressed: () => _loadAll(force: true), child: const Text('Tekrar dene')),
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
