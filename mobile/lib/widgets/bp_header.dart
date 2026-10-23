import 'package:flutter/material.dart';
import '../core/theme.dart';

/// Ekran üst bölümü: yüzey rengi, durum çubuğu boşluğu ve alt çizgi.
class BpHeader extends StatelessWidget {
  final Widget child;
  final EdgeInsetsGeometry padding;

  const BpHeader({super.key, required this.child, this.padding = const EdgeInsets.fromLTRB(16, 8, 16, 12)});

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    return Container(
      decoration: BoxDecoration(color: c.surface, border: Border(bottom: BorderSide(color: c.border))),
      child: SafeArea(bottom: false, child: Padding(padding: padding, child: child)),
    );
  }
}
