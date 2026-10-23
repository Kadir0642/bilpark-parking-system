import 'package:flutter/material.dart';
import '../core/format.dart';
import '../core/theme.dart';

/// Bağlantı durumu bandı. [online] null ise ilk yanıt bekleniyor demektir.
class ConnectionBanner extends StatelessWidget {
  final bool? online;
  final DateTime? lastUpdate;
  final int pending;

  const ConnectionBanner({super.key, required this.online, this.lastUpdate, this.pending = 0});

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final Color bg;
    final Color fg;
    final Color dot;
    final String text;
    if (online == null) {
      bg = c.surface2;
      fg = c.text2;
      dot = c.borderStrong;
      text = 'Bağlanılıyor…';
    } else if (online == true) {
      bg = c.successSoft;
      fg = c.onSuccessSoft;
      dot = c.success;
      text = lastUpdate != null ? 'Çevrimiçi · ${formatHm(lastUpdate!)} güncellendi' : 'Çevrimiçi';
    } else {
      bg = c.dangerSoft;
      fg = c.onDangerSoft;
      dot = c.danger;
      text = lastUpdate != null ? 'Bağlantı yok · son veri ${formatHm(lastUpdate!)}' : 'Bağlantı yok';
    }
    return Semantics(
      liveRegion: true,
      child: Container(
        constraints: const BoxConstraints(minHeight: 32),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(BpSize.rSm)),
        child: Row(
          children: [
            Container(width: 10, height: 10, decoration: BoxDecoration(color: dot, shape: BoxShape.circle)),
            const SizedBox(width: 8),
            Expanded(child: Text(text, style: TextStyle(color: fg, fontSize: 14, fontWeight: FontWeight.w600))),
            if (pending > 0)
              Text('$pending işlem bekliyor', style: TextStyle(color: fg, fontSize: 14, fontWeight: FontWeight.w600)),
          ],
        ),
      ),
    );
  }
}
