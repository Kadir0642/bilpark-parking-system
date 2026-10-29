import 'package:flutter/material.dart';
import '../core/format.dart';
import '../core/theme.dart';

/// Giriş yapılan aracın ödenmemiş kaçak borcu varsa gösterilen uyarı. Engellemez: giriş zaten yapılmıştır.
Future<void> showDebtAlert(BuildContext context, String plate, num debt) {
  return showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      icon: Icon(Icons.warning_amber_rounded, color: ctx.bp.danger, size: 36),
      title: Text('Borçlu araç', style: TextStyle(color: ctx.bp.danger, fontWeight: FontWeight.w800)),
      content: Text(
          '${formatPlate(plate)} plakalı aracın kaçak geçmişi var: ödenmemiş borç ${formatMoney(debt)}. '
          'Giriş kaydedildi.',
          style: const TextStyle(fontSize: 16)),
      actions: [
        FilledButton(
          style: FilledButton.styleFrom(
              backgroundColor: ctx.bp.danger, foregroundColor: ctx.bp.onDanger, minimumSize: const Size(BpSize.tap, BpSize.tap)),
          onPressed: () => Navigator.pop(ctx),
          child: const Text('Tamam'),
        ),
      ],
    ),
  );
}
