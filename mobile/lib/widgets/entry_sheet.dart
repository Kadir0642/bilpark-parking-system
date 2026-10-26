import 'package:flutter/material.dart';
import '../core/format.dart';
import '../core/theme.dart';
import 'license_banner.dart';
import 'status_badge.dart';
import 'toggle_tile.dart';

class EntryResult {
  final String plate;
  final String type; // SMALL | LARGE
  final String side; // LEFT | RIGHT
  const EntryResult(this.plate, this.type, this.side);
}

/// Araç girişi alt sayfası: plaka, araç türü, park yönü. İptal edilirse null döner.
Future<EntryResult?> showEntrySheet(BuildContext context, String initialPlate) {
  return showModalBottomSheet<EntryResult>(
    context: context,
    isScrollControlled: true,
    builder: (_) => _EntrySheet(initialPlate: initialPlate),
  );
}

class _EntrySheet extends StatefulWidget {
  final String initialPlate;
  const _EntrySheet({required this.initialPlate});

  @override
  State<_EntrySheet> createState() => _EntrySheetState();
}

class _EntrySheetState extends State<_EntrySheet> {
  late final TextEditingController _plate = TextEditingController(text: widget.initialPlate);
  String _type = 'SMALL';

  @override
  void dispose() {
    _plate.dispose();
    super.dispose();
  }

  void _submit(String side) => Navigator.pop(context, EntryResult(_plate.text, _type, side));

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    return SafeArea(
      child: SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(20, 0, 20, MediaQuery.of(context).viewInsets.bottom + 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('Araç girişi', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
            const SizedBox(height: 16),
            ValueListenableBuilder<TextEditingValue>(
              valueListenable: _plate,
              builder: (context, v, _) => TextField(
                controller: _plate,
                autofocus: widget.initialPlate.isEmpty,
                textCapitalization: TextCapitalization.characters,
                style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w800, letterSpacing: 2),
                decoration: InputDecoration(
                  labelText: 'Plaka',
                  constraints: const BoxConstraints(minHeight: BpSize.tapLg),
                  suffixIcon: v.text.isEmpty
                      ? null
                      : Padding(
                          padding: const EdgeInsets.all(12),
                          child: StatusBadge(isForeignPlate(v.text) ? 'Yabancı' : 'TR',
                              tone: isForeignPlate(v.text) ? BadgeTone.primary : BadgeTone.neutral),
                        ),
                ),
              ),
            ),
            const SizedBox(height: 16),
            Text('Araç türü', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: c.text2)),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: BpToggleTile(
                    label: 'Otomobil',
                    icon: Icons.directions_car_outlined,
                    selected: _type == 'SMALL',
                    onTap: () => setState(() => _type = 'SMALL'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: BpToggleTile(
                    label: 'Kamyonet',
                    icon: Icons.local_shipping_outlined,
                    selected: _type == 'LARGE',
                    onTap: () => setState(() => _type = 'LARGE'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            ReadOnlyGate(
              builder: (_, ro) => Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: ro ? null : () => _submit('LEFT'),
                      icon: const Icon(Icons.arrow_back),
                      label: const Text('Sola park'),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: ro ? null : () => _submit('RIGHT'),
                      icon: const Icon(Icons.arrow_forward),
                      label: const Text('Sağa park'),
                    ),
                  ),
                ],
              ),
            ),
            const ReadOnlyNote(),
          ],
        ),
      ),
    );
  }
}
