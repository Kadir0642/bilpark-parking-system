import 'package:flutter/material.dart';
import '../core/format.dart';
import '../core/theme.dart';

enum PlateSize { small, large }

/// Türk plakası görünümü: solda mavi şerit, beyaz zeminde koyu plaka metni.
class PlateBadge extends StatelessWidget {
  final String plate;
  final PlateSize size;

  const PlateBadge(this.plate, {super.key, this.size = PlateSize.small});

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final large = size == PlateSize.large;
    final height = large ? 44.0 : 28.0;
    final border = large ? 3.0 : 2.0;
    final stripWidth = large ? 28.0 : 12.0;
    final fontSize = large ? 26.0 : 15.0;
    final label = isForeignPlate(plate) ? '' : 'TR';
    return Semantics(
      label: 'Plaka $plate',
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
                child: large && label.isNotEmpty
                    ? Text(label, style: TextStyle(color: c.plateStripText, fontSize: 13, fontWeight: FontWeight.w800))
                    : null,
              ),
              Padding(
                padding: EdgeInsets.symmetric(horizontal: large ? 10 : 6),
                child: Center(
                  child: Text(
                    formatPlate(plate),
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
