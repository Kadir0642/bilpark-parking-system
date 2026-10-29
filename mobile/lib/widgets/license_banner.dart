import 'package:flutter/material.dart';
import '../core/license.dart';
import '../core/theme.dart';

/// Okunur modda yazma düğmelerinin yanında gösterilen kısa gerekçe.
const String readOnlyReason = 'Okunur mod: lisans süresi doldu.';

/// [LicenseStore.readOnly] değişince [builder]'ı yeniden kurar; lisans gelince düğmeler kendiliğinden açılır.
class ReadOnlyGate extends StatelessWidget {
  final Widget Function(BuildContext context, bool readOnly) builder;

  const ReadOnlyGate({super.key, required this.builder});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: LicenseStore.readOnly,
      builder: (context, ro, _) => builder(context, ro),
    );
  }
}

/// Düğmelerin altında gösterilen okunur mod notu; okunur mod değilse hiçbir şey çizmez.
class ReadOnlyNote extends StatelessWidget {
  const ReadOnlyNote({super.key});

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    return ReadOnlyGate(
      builder: (_, ro) => ro
          ? Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.lock_outline, size: 16, color: c.danger),
                  const SizedBox(width: 6),
                  Flexible(child: Text(readOnlyReason, style: TextStyle(fontSize: 13, color: c.danger))),
                ],
              ),
            )
          : const SizedBox.shrink(),
    );
  }
}

/// Lisans bandı: okunur modda kırmızı, bitime 7 ya da 1 gün kala / ek sürede sarı. Dokununca tam mesaj açılır.
class LicenseBanner extends StatelessWidget {
  const LicenseBanner({super.key});

  /// Bant gösterilecekse (metin, okunurMod); aksi halde null. 30 gün uyarısı mobilde gösterilmez.
  static (String, bool)? content(LicenseStatus? s, bool readOnly) {
    if (readOnly) {
      return ('Okunur mod: Lisans süresi doldu. Giriş, çıkış ve kaçak işlemleri kapalı. Kayıtları görüntüleyebilirsiniz.', true);
    }
    if (s == null) return null;
    if (s.state == LicenseState.grace) {
      final left = s.graceDaysLeft;
      return (left == null ? 'Lisans süresi doldu; ek süre işliyor.' : 'Lisans süresi doldu; ek sürenin bitmesine $left gün kaldı.', false);
    }
    final level = s.warningLevel;
    if (level == 7 || level == 1) {
      final d = s.daysLeft;
      return (d == null || d <= 0 ? 'Lisans bugün bitiyor.' : 'Lisansın bitmesine $d gün kaldı.', false);
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: LicenseStore.readOnly,
      builder: (context, ro, _) => ValueListenableBuilder<LicenseStatus?>(
        valueListenable: LicenseStore.status,
        builder: (context, s, _) {
          final info = content(s, ro);
          if (info == null) return const SizedBox.shrink();
          final (text, danger) = info;
          final c = context.bp;
          final bg = danger ? c.dangerSoft : c.warningSoft;
          final fg = danger ? c.onDangerSoft : c.onWarningSoft;
          return Material(
            color: bg,
            child: InkWell(
              onTap: () => _showDetails(context, danger ? LicenseStore.readOnlyMessage : (s?.message ?? text)),
              child: SafeArea(
                bottom: false,
                child: Container(
                  decoration: BoxDecoration(border: Border(bottom: BorderSide(color: danger ? c.danger : c.warning))),
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                  child: Row(
                    children: [
                      Icon(danger ? Icons.lock_outline : Icons.info_outline, size: 20, color: fg),
                      const SizedBox(width: 10),
                      Expanded(child: Text(text, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: fg))),
                      Icon(Icons.chevron_right, size: 20, color: fg),
                    ],
                  ),
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  static void _showDetails(BuildContext context, String message) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Lisans durumu'),
        content: Text(message),
        actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Kapat'))],
      ),
    );
  }
}

/// Bandı alttaki içeriğin üstüne koyar. Bant görünürken durum çubuğu boşluğunu bant üstlenir;
/// içerideki ekran başlıkları aynı boşluğu ikinci kez eklemesin diye üst dolgu sıfırlanır.
class LicenseBannerHost extends StatelessWidget {
  final Widget child;

  const LicenseBannerHost({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: LicenseStore.readOnly,
      builder: (context, ro, _) => ValueListenableBuilder<LicenseStatus?>(
        valueListenable: LicenseStore.status,
        builder: (context, s, _) {
          final visible = LicenseBanner.content(s, ro) != null;
          return Column(
            children: [
              const LicenseBanner(),
              Expanded(child: MediaQuery.removePadding(context: context, removeTop: visible, child: child)),
            ],
          );
        },
      ),
    );
  }
}
