import 'package:flutter/material.dart';
import '../core/format.dart';
import '../core/live_sync.dart';
import '../core/theme.dart';

/// Bağlantı durumu bandı (canlı senkron durumuna göre renk ve metin).
class ConnectionBanner extends StatelessWidget {
  final LiveStatus status;
  final DateTime? lastSync;
  final int pending;

  const ConnectionBanner({super.key, required this.status, this.lastSync, this.pending = 0});

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final Color bg;
    final Color fg;
    final Color dot;
    final String text;
    final sync = lastSync != null ? ' · son senkron ${formatHm(lastSync!)}' : '';
    switch (status) {
      case LiveStatus.connecting:
        bg = c.surface2;
        fg = c.text2;
        dot = c.borderStrong;
        text = 'Bağlanılıyor…';
      case LiveStatus.online:
        bg = c.successSoft;
        fg = c.onSuccessSoft;
        dot = c.success;
        text = 'Çevrimiçi$sync';
      case LiveStatus.reconnecting:
        bg = c.warningSoft;
        fg = c.onWarningSoft;
        dot = c.warning;
        text = 'Yeniden bağlanıyor…$sync';
      case LiveStatus.offline:
        bg = c.dangerSoft;
        fg = c.onDangerSoft;
        dot = c.danger;
        text = 'Çevrimdışı$sync';
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

/// Global (veya verilen) [LiveSync] durumunu dinleyen bant.
class LiveConnectionBanner extends StatelessWidget {
  final int pending;
  final LiveSync? sync;

  const LiveConnectionBanner({super.key, this.pending = 0, this.sync});

  @override
  Widget build(BuildContext context) {
    final s = sync ?? liveSync;
    return ValueListenableBuilder<LiveStatus>(
      valueListenable: s.status,
      builder: (_, st, _) => ValueListenableBuilder<DateTime?>(
        valueListenable: s.lastSync,
        builder: (_, ls, _) => ConnectionBanner(status: st, lastSync: ls, pending: pending),
      ),
    );
  }
}
