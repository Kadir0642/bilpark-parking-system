import 'package:flutter/material.dart';
import '../core/theme.dart';

enum BadgeTone { success, danger, warning, primary, sub, neutral }

/// Tona göre (zemin, yazı) renkleri.
class BadgeColors {
  final Color bg;
  final Color fg;
  const BadgeColors(this.bg, this.fg);
}

BadgeColors badgeColors(BilParkColors c, BadgeTone tone) {
  switch (tone) {
    case BadgeTone.success:
      return BadgeColors(c.successSoft, c.onSuccessSoft);
    case BadgeTone.danger:
      return BadgeColors(c.dangerSoft, c.onDangerSoft);
    case BadgeTone.warning:
      return BadgeColors(c.warning, c.onWarning);
    case BadgeTone.primary:
      return BadgeColors(c.primarySoft, c.onPrimarySoft);
    case BadgeTone.sub:
      return BadgeColors(c.subSoft, c.onSubSoft);
    case BadgeTone.neutral:
      return BadgeColors(c.surface2, c.text2);
  }
}

/// Küçük hap biçimli durum etiketi.
class StatusBadge extends StatelessWidget {
  final String label;
  final BadgeTone tone;
  final IconData? icon;

  const StatusBadge(this.label, {super.key, this.tone = BadgeTone.neutral, this.icon});

  @override
  Widget build(BuildContext context) {
    final col = badgeColors(context.bp, tone);
    return Container(
      height: 24,
      padding: const EdgeInsets.symmetric(horizontal: 8),
      decoration: BoxDecoration(color: col.bg, borderRadius: BorderRadius.circular(999)),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[Icon(icon, size: 14, color: col.fg), const SizedBox(width: 4)],
          Text(label, style: TextStyle(color: col.fg, fontSize: 13, fontWeight: FontWeight.w700)),
        ],
      ),
    );
  }
}
