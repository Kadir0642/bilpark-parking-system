import 'package:flutter/material.dart';
import '../core/haptics.dart';
import '../core/theme.dart';
import 'status_badge.dart';

/// Tema renklerinden SnackBar üretir. [tone] success/danger dışında verilmezse koyu nötr görünür.
/// [behavior]/[margin]: varsayılan değişmez; cadde ekranı düğmelerin üstünde kalmak için floating + margin verir.
SnackBar bpSnackBar(
  BuildContext context,
  String message, {
  BadgeTone? tone,
  Duration duration = const Duration(seconds: 4),
  SnackBarAction? action,
  SnackBarBehavior? behavior,
  EdgeInsetsGeometry? margin,
}) {
  final c = context.bp;
  Color? bg;
  Color? fg;
  if (tone == BadgeTone.success) {
    bg = c.success;
    fg = c.onSuccess;
  } else if (tone == BadgeTone.danger) {
    bg = c.danger;
    fg = c.onDanger;
    errorHaptic(); // tüm hata bildirimleri buradan geçer; başarıda titreşim yok
  }
  return SnackBar(
    content: Text(message, style: fg != null ? TextStyle(color: fg, fontSize: 15) : null),
    backgroundColor: bg,
    duration: duration,
    action: action,
    behavior: behavior,
    margin: margin,
  );
}
