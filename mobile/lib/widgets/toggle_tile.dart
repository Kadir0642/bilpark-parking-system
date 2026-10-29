import 'package:flutter/material.dart';
import '../core/theme.dart';

/// Büyük seçim kutusu (ödeme yöntemi, araç türü). [onTap] null ise devre dışıdır.
class BpToggleTile extends StatelessWidget {
  final String label;
  final IconData? icon;
  final bool selected;
  final VoidCallback? onTap;
  final String? note;
  final double height;

  const BpToggleTile({
    super.key,
    required this.label,
    this.icon,
    required this.selected,
    required this.onTap,
    this.note,
    this.height = 64,
  });

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final enabled = onTap != null;
    final Color bg = !enabled ? c.surface2 : (selected ? c.primarySoft : c.surface);
    final Color fg = !enabled ? c.text2 : (selected ? c.onPrimarySoft : c.text);
    final Color border = !enabled ? c.border : (selected ? c.primary : c.borderStrong);
    return Semantics(
      button: true,
      selected: selected,
      enabled: enabled,
      label: note != null ? '$label, $note' : label,
      child: ExcludeSemantics(
        child: Material(
          color: bg,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(BpSize.rMd),
            side: BorderSide(color: border, width: 2),
          ),
          child: InkWell(
            onTap: onTap,
            customBorder: RoundedRectangleBorder(borderRadius: BorderRadius.circular(BpSize.rMd)),
            child: SizedBox(
              height: height,
              child: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (icon != null) Icon(icon, size: 22, color: fg),
                    Text(label,
                        textAlign: TextAlign.center,
                        style: TextStyle(color: fg, fontSize: 15, fontWeight: selected ? FontWeight.w700 : FontWeight.w600)),
                    if (note != null) Text(note!, style: TextStyle(color: fg, fontSize: 13)),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
