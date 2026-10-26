import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../core/api_client.dart';
import '../core/constants.dart';
import '../core/format.dart' as fmt;
import '../core/live_sync.dart';
import '../core/theme.dart';
import '../main.dart'; // themeNotifier, setThemeMode
import '../widgets/bp_header.dart';
import '../widgets/bp_snack.dart';
import '../widgets/status_badge.dart';
import 'change_password_screen.dart';

class SettingsScreen extends StatefulWidget {
  final String? street;
  final String? zoneName;

  const SettingsScreen({super.key, this.street, this.zoneName});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  Future<void> endShift(BuildContext context) async {
    String? warning;
    try {
      // Sunucuda bu cihazın açık vardiyasını kapatır
      if (await ApiClient.getToken() != null) {
        await ApiClient.post('/auth/logout');
      }
    } on ApiException catch (e) {
      // 401 zaten girişe yönlendirir; diğer hatalarda yerel çıkış yine de yapılır ama kullanıcı bilgilendirilir
      if (!e.isUnauthorized) warning = 'Vardiya sunucuda kapatılamadı: ${e.message}';
    }
    await liveSync.stop();
    await ApiClient.clearToken();
    AppConstants.clearAuth();
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('user_role');
    await prefs.remove('username');

    if (!context.mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    final snack = warning != null ? bpSnackBar(context, warning, tone: BadgeTone.danger) : null;
    Navigator.of(context).pushNamedAndRemoveUntil('/', (Route<dynamic> route) => false);
    if (snack != null) messenger.showSnackBar(snack);
  }

  Future<void> _confirmEndShift() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Vardiya bitsin mi?'),
        content: const Text(
            'Bu işlem oturumunuzu kapatacak ve sizi sistemden çıkış yapmış olarak işaretleyecektir.\nOnaylıyor musunuz?'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('İptal')),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: ctx.bp.danger, foregroundColor: ctx.bp.onDanger, minimumSize: const Size(BpSize.tap, BpSize.tap)),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Vardiyayı bitir'),
          ),
        ],
      ),
    );
    if (ok == true && mounted) endShift(context);
  }

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final zoneTitle = widget.street == null
        ? null
        : fmt.zoneTitle(widget.street!, widget.zoneName);
    return Scaffold(
      backgroundColor: c.bg,
      body: Column(
        children: [
          BpHeader(
            padding: const EdgeInsets.fromLTRB(8, 4, 16, 8),
            child: Row(
              children: [
                IconButton(
                  tooltip: 'Geri',
                  icon: const Icon(Icons.arrow_back),
                  onPressed: () => Navigator.maybePop(context),
                ),
                const Text('Ayarlar', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              ],
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.all(BpSize.screenPad),
              children: [
                _sectionTitle(c, 'GÖRÜNÜM'),
                _card(c, [
                  Padding(
                    padding: const EdgeInsets.all(14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text('Tema', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                        const SizedBox(height: 10),
                        ValueListenableBuilder<ThemeMode>(
                          valueListenable: themeNotifier,
                          builder: (context, mode, _) => SizedBox(
                            width: double.infinity,
                            child: SegmentedButton<ThemeMode>(
                              showSelectedIcon: false,
                              segments: const [
                                ButtonSegment(value: ThemeMode.light, icon: Icon(Icons.light_mode_outlined), label: Text('Açık')),
                                ButtonSegment(value: ThemeMode.dark, icon: Icon(Icons.dark_mode_outlined), label: Text('Koyu')),
                                ButtonSegment(value: ThemeMode.system, icon: Icon(Icons.brightness_auto_outlined), label: Text('Sistem')),
                              ],
                              selected: {mode},
                              onSelectionChanged: (s) => setThemeMode(s.first),
                            ),
                          ),
                        ),
                        const SizedBox(height: 10),
                        Text('Koyu tema gece vardiyasında göz yorgunluğunu azaltır.',
                            style: TextStyle(fontSize: 14, color: c.text2)),
                      ],
                    ),
                  ),
                ]),
                const SizedBox(height: 20),
                _sectionTitle(c, 'HESAP'),
                _card(c, [
                  InkWell(
                    onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const ChangePasswordScreen())),
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(minHeight: 52),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                        child: Row(
                          children: [
                            Icon(Icons.lock_outline, size: 20, color: c.text2),
                            const SizedBox(width: 12),
                            const Expanded(child: Text('Şifremi değiştir', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600))),
                            Icon(Icons.chevron_right, color: c.text2),
                          ],
                        ),
                      ),
                    ),
                  ),
                ]),
                const SizedBox(height: 20),
                _sectionTitle(c, 'VARDİYA'),
                _card(c, [
                  _row(c, 'Personel', Text(AppConstants.currentUser ?? '-', style: _bold)),
                  if (zoneTitle != null) _row(c, 'Saha', Flexible(child: Text(zoneTitle, style: _bold, textAlign: TextAlign.end))),
                ]),
                const SizedBox(height: 20),
                _sectionTitle(c, 'CİHAZ VE BAĞLANTI'),
                _card(c, [
                  _row(
                    c,
                    'Sunucu',
                    ValueListenableBuilder<LiveStatus>(
                      valueListenable: liveSync.status,
                      builder: (_, st, _) {
                        final (Color col, String label) = switch (st) {
                          LiveStatus.online => (c.success, 'Bağlı'),
                          LiveStatus.connecting => (c.text2, 'Bağlanılıyor…'),
                          LiveStatus.reconnecting => (c.warning, 'Yeniden bağlanıyor…'),
                          LiveStatus.offline => (c.danger, 'Bağlantı yok'),
                        };
                        return Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Container(width: 10, height: 10, decoration: BoxDecoration(color: col, shape: BoxShape.circle)),
                            const SizedBox(width: 8),
                            Text(label, style: TextStyle(fontWeight: FontWeight.w700, color: col)),
                          ],
                        );
                      },
                    ),
                  ),
                  _row(
                    c,
                    'Cihaz kimliği',
                    FutureBuilder<String>(
                      future: ApiClient.getDeviceId(),
                      builder: (context, snap) {
                        final id = snap.data;
                        final short = id == null ? '-' : (id.length > 8 ? '${id.substring(0, 8)}…' : id);
                        return Text(short, style: const TextStyle(fontSize: 14, fontFamily: 'monospace'));
                      },
                    ),
                  ),
                  _row(c, 'Sürüm', Text(AppConstants.appVersion)),
                ]),
              ],
            ),
          ),
          Container(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
            child: SafeArea(
              top: false,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  OutlinedButton.icon(
                    style: OutlinedButton.styleFrom(
                      minimumSize: const Size(BpSize.tap, BpSize.tapLg),
                      foregroundColor: c.danger,
                      side: BorderSide(color: c.danger, width: 2),
                      textStyle: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
                    ),
                    onPressed: _confirmEndShift,
                    icon: const Icon(Icons.logout),
                    label: const Text('Vardiyayı bitir'),
                  ),
                  const SizedBox(height: 8),
                  Text('Onay sorulur; vardiya sunucuda da kapatılır.',
                      textAlign: TextAlign.center, style: TextStyle(fontSize: 13, color: c.text2)),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  static const TextStyle _bold = TextStyle(fontWeight: FontWeight.w700);

  Widget _sectionTitle(BilParkColors c, String t) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text(t, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: c.text2)),
      );

  Widget _card(BilParkColors c, List<Widget> children) {
    final items = <Widget>[];
    for (var i = 0; i < children.length; i++) {
      if (i > 0) items.add(Divider(color: c.border));
      items.add(children[i]);
    }
    return Container(
      decoration: BoxDecoration(
        color: c.surface,
        border: Border.all(color: c.border),
        borderRadius: BorderRadius.circular(BpSize.rMd),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(children: items),
    );
  }

  Widget _row(BilParkColors c, String label, Widget value) {
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 52),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label, style: TextStyle(fontSize: 15, color: c.text2)),
            const SizedBox(width: 12),
            DefaultTextStyle.merge(style: const TextStyle(fontSize: 15), child: value),
          ],
        ),
      ),
    );
  }
}
