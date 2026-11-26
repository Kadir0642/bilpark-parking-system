import 'package:material_ui/material_ui.dart';
import '../core/format.dart';
import '../core/plate.dart';
import '../core/theme.dart';

enum PlateSize { small, large }

/// Türk plakası görünümü: solda mavi şerit, beyaz zeminde koyu plaka metni.
/// [foreign]: yabancı / özel plaka (V10); "TR" yazısı yok, metin gruplanmadan olduğu gibi gösterilir.
/// [countryCode]: yabancı plakanın ülke kodu (V11, D, NL ...); şeritte gösterilir.
class PlateBadge extends StatelessWidget {
  final String plate;
  final PlateSize size;
  final bool foreign;
  final String? countryCode;

  const PlateBadge(this.plate, {super.key, this.size = PlateSize.small, this.foreign = false, this.countryCode});

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final large = size == PlateSize.large;
    final height = large ? 44.0 : 28.0;
    final border = large ? 3.0 : 2.0;
    // Yalnızca yabancı plakada ülke kodu şeritte yazılır; şerit koda göre genişler
    final code = foreign ? normalizeCountryCode(countryCode) : null;
    final stripWidth = code != null ? (large ? 34.0 : 22.0) : (large ? 28.0 : 12.0);
    final fontSize = large ? 26.0 : 15.0;
    // "TR" yalnızca Türk biçimli ve yabancı uyruklu (MA-MZ) olmayan plakada
    final label = code ?? (foreign || isForeignPlate(plate) || !isValidTrPlate(plate) ? '' : 'TR');
    return Semantics(
      label: code == null ? 'Plaka $plate' : 'Plaka $plate, ülke $code',
      child: ExcludeSemantics(
        child: Container(
          height: height,
          decoration: BoxDecoration(
            color: c.plateBg,
            border: Border.all(color: c.plateText, width: border),
            borderRadius: BorderRadius.circular(large ? 7 : 5),
          ),
          clipBehavior: Clip.antiAlias,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Container(
                width: stripWidth,
                color: c.plateStrip,
                alignment: Alignment.bottomCenter,
                padding: EdgeInsets.only(bottom: large ? 3 : 0),
                child: code != null
                    ? Padding(
                        padding: EdgeInsets.only(bottom: large ? 3 : 2),
                        child: FittedBox(
                          fit: BoxFit.scaleDown,
                          child: Text(code,
                              key: const Key('plateCountryCode'),
                              style: TextStyle(
                                  color: c.plateStripText, fontSize: large ? 13 : 9, fontWeight: FontWeight.w800)),
                        ),
                      )
                    : large && label.isNotEmpty
                        ? Text(label, style: TextStyle(color: c.plateStripText, fontSize: 13, fontWeight: FontWeight.w800))
                        : null,
              ),
              Padding(
                padding: EdgeInsets.symmetric(horizontal: large ? 10 : 6),
                child: Center(
                  child: Text(
                    foreign ? plate : formatPlate(plate),
                    maxLines: 1,
                    style: TextStyle(
                      color: c.plateText,
                      fontSize: fontSize,
                      fontWeight: FontWeight.w800,
                      letterSpacing: large ? 1.5 : 0.5,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
