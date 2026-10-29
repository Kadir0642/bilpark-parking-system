import 'package:flutter/material.dart';
import '../core/format.dart';
import '../core/plate.dart';
import '../core/theme.dart';
import '../core/vehicle_types.dart';
import 'license_banner.dart';
import 'status_badge.dart';
import 'toggle_tile.dart';

class EntryResult {
  final String plate;
  final String type; // vehicle_types kodu (SMALL, LARGE, MOTORCYCLE...)
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
  late String _type = _firstActive();

  static String _firstActive() {
    final l = VehicleTypeStore.active.value;
    return l.isEmpty ? 'SMALL' : l.first.code;
  }

  @override
  void dispose() {
    _plate.dispose();
    super.dispose();
  }

  void _submit(String side) => Navigator.pop(context, EntryResult(_plate.text, _type, side));

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final kb = MediaQuery.of(context).viewInsets.bottom;
    // Klavye açıkken boşluklar daralır: küçük ekranda "Sola/Sağa park" kaydırmadan görünür kalsın
    final gap = kb > 0 ? 4.0 : 16.0;
    return SafeArea(
      child: SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(20, 0, 20, kb + (kb > 0 ? 8 : 20)),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Araç girişi', style: TextStyle(fontSize: kb > 0 ? 18 : 22, fontWeight: FontWeight.w700)),
            SizedBox(height: gap),
            ValueListenableBuilder<TextEditingValue>(
              valueListenable: _plate,
              builder: (context, v, _) {
                final hint = plateHint(v.text);
                final valid = hint == null;
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    TextField(
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
                    SizedBox(height: kb > 0 ? 2 : 6),
                    // Canlı doğrulama: geçerliyse yeşil, değilse nedeni kırmızı gösterilir
                    Align(
                      alignment: Alignment.centerLeft,
                      child: FittedBox(
                        fit: BoxFit.scaleDown,
                        child: StatusBadge(
                          valid ? 'Geçerli plaka' : hint,
                          tone: valid ? BadgeTone.success : BadgeTone.danger,
                          icon: valid ? Icons.check_circle : Icons.error_outline,
                        ),
                      ),
                    ),
                  ],
                );
              },
            ),
            SizedBox(height: gap),
            Text('Araç türü', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: c.text2)),
            const SizedBox(height: 8),
            // Seçenekler sunucudaki etkin tiplerden gelir; sayfa açıkken liste değişirse yeniden kurulur
            ValueListenableBuilder<List<VehicleTypeInfo>>(
              valueListenable: VehicleTypeStore.active,
              builder: (_, types, _) {
                // Seçili tip listeden düştüyse ilk etkin tipe dönülür
                if (types.isNotEmpty && !types.any((t) => t.code == _type)) _type = types.first.code;
                return Row(
                  children: [
                    for (var i = 0; i < types.length; i++) ...[
                      if (i > 0) const SizedBox(width: 8),
                      Expanded(
                        child: BpToggleTile(
                          label: types[i].displayName,
                          icon: iconFor(types[i].iconKey),
                          selected: _type == types[i].code,
                          onTap: () => setState(() => _type = types[i].code),
                        ),
                      ),
                    ],
                  ],
                );
              },
            ),
            SizedBox(height: gap + 4),
            ReadOnlyGate(
              builder: (_, ro) => ValueListenableBuilder<TextEditingValue>(
                valueListenable: _plate,
                builder: (_, v, _) {
                  // Geçersiz (boş dahil) plakada giriş düğmeleri kapalı: sunucudan "Geçersiz plaka" dönmesin
                  final blocked = ro || !isValidTrPlate(v.text);
                  return Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: blocked ? null : () => _submit('LEFT'),
                      icon: const Icon(Icons.arrow_back),
                      label: const Text('Sola park'),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: blocked ? null : () => _submit('RIGHT'),
                      icon: const Icon(Icons.arrow_forward),
                      label: const Text('Sağa park'),
                    ),
                  ),
                ],
                  );
                },
              ),
            ),
            const ReadOnlyNote(),
          ],
        ),
      ),
    );
  }
}
