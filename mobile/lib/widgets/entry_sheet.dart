import 'package:material_ui/material_ui.dart';
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
  final bool foreign; // yabancı / özel plaka (TR biçim denetimi yok)
  final String? countryCode; // yalnızca yabancı plakada ve doluysa: normalize (A-Z, 1-3 harf)
  const EntryResult(this.plate, this.type, this.side, {this.foreign = false, this.countryCode});
}

/// Araç girişi alt sayfası: plaka, araç türü, park yönü. İptal edilirse null döner.
/// [foreign]: "Yabancı / özel plaka" anahtarının açılış değeri (yabancı modda taranan plaka için true).
/// [countryCode]: kameradan okunan ülke kodu; yabancı iken "Ülke kodu" alanına ön doldurulur.
Future<EntryResult?> showEntrySheet(BuildContext context, String initialPlate,
    {bool foreign = false, String? countryCode}) {
  return showModalBottomSheet<EntryResult>(
    context: context,
    isScrollControlled: true,
    builder: (_) => EntrySheet(initialPlate: initialPlate, foreign: foreign, countryCode: countryCode),
  );
}

class EntrySheet extends StatefulWidget {
  final String initialPlate;
  final bool foreign;
  final String? countryCode;
  const EntrySheet({super.key, required this.initialPlate, this.foreign = false, this.countryCode});

  @override
  State<EntrySheet> createState() => _EntrySheetState();
}

class _EntrySheetState extends State<EntrySheet> {
  late final TextEditingController _plate = TextEditingController(text: widget.initialPlate);
  late String _type = _firstActive();
  late bool _foreign = widget.foreign;
  // Ön doldurma yalnızca yabancı modda ve geçerli kodla; görevli düzenleyebilir ya da silebilir
  late final TextEditingController _country =
      TextEditingController(text: widget.foreign ? normalizeCountryCode(widget.countryCode) ?? '' : '');

  static String _firstActive() {
    final l = VehicleTypeStore.active.value;
    return l.isEmpty ? 'SMALL' : l.first.code;
  }

  @override
  void dispose() {
    _plate.dispose();
    _country.dispose();
    super.dispose();
  }

  // Anahtar kapanınca ülke kodu silinir (TR plakada kod gönderilmez; sunucu 400 verir)
  void _setForeign(bool on) => setState(() {
        _foreign = on;
        if (!on) _country.clear();
      });

  void _submit(String side) => Navigator.pop(
        context,
        // Yabancı plakada Kiril harfler (Rus plakası) Latin eşine iner: sunucu A-Z ister
        EntryResult(_foreign ? latinizeCyrillic(_plate.text) : _plate.text, _type, side,
            foreign: _foreign, countryCode: _foreign ? normalizeCountryCode(_country.text) : null),
      );

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
            // Başlık satırında "Yabancı / özel plaka" anahtarı (Türk biçimine uymayan plaka: yabancı ülke,
            // diplomatik CD...): açıkken TR denetimi yok. Başlık yüksekliğini aşmaz, küçük ekranda klavye
            // açıkken "Sola/Sağa park" görünür kalır.
            Row(
              children: [
                Expanded(
                  child: Text('Araç girişi', style: TextStyle(fontSize: kb > 0 ? 18 : 22, fontWeight: FontWeight.w700)),
                ),
                MergeSemantics(
                  child: InkWell(
                    borderRadius: BorderRadius.circular(BpSize.rMd),
                    onTap: () => _setForeign(!_foreign),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text('Yabancı / özel plaka',
                            style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: c.text2)),
                        SizedBox(
                          height: 24,
                          width: 44,
                          child: FittedBox(
                            child: Switch(
                              key: const Key('foreignPlateSwitch'),
                              value: _foreign,
                              onChanged: _setForeign,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
            SizedBox(height: gap),
            ValueListenableBuilder<TextEditingValue>(
              valueListenable: _plate,
              builder: (context, v, _) {
                final hint = plateHintFor(v.text, foreign: _foreign);
                final valid = hint == null;
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    TextField(
                      key: const Key('plateField'),
                      controller: _plate,
                      autofocus: widget.initialPlate.isEmpty,
                      textCapitalization: TextCapitalization.characters,
                      style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w800, letterSpacing: 2),
                      decoration: InputDecoration(
                        labelText: 'Plaka',
                        hintText: _foreign ? foreignPlateExample : plateExample,
                        constraints: const BoxConstraints(minHeight: BpSize.tapLg),
                        suffixIcon: v.text.isEmpty || _foreign
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
            if (_foreign) ...[
              SizedBox(height: gap),
              ValueListenableBuilder<TextEditingValue>(
                valueListenable: _country,
                builder: (context, v, _) => TextField(
                  key: const Key('foreignCountryField'),
                  controller: _country,
                  // En çok 3 harf: ülke kodu daha uzun yazılamaz (yapıştırma dahil); sayaç gösterilmez
                  maxLength: countryCodeMax,
                  textCapitalization: TextCapitalization.characters,
                  style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700, letterSpacing: 2),
                  decoration: InputDecoration(
                    labelText: 'Ülke kodu (isteğe bağlı)',
                    hintText: countryCodeExample,
                    counterText: '',
                    errorText: countryCodeHint(v.text),
                    constraints: const BoxConstraints(minHeight: BpSize.tapLg),
                  ),
                ),
              ),
            ],
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
              builder: (_, ro) => ListenableBuilder(
                listenable: Listenable.merge([_plate, _country]),
                builder: (_, _) {
                  // Geçersiz (boş dahil) plakada giriş düğmeleri kapalı: sunucudan "Geçersiz plaka" dönmesin
                  final blocked = ro ||
                      !isValidPlateFor(_plate.text, foreign: _foreign) ||
                      (_foreign && countryCodeHint(_country.text) != null);
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
