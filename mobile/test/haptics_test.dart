import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:bilpark/core/haptics.dart';
import 'package:bilpark/core/theme.dart';
import 'package:bilpark/widgets/bp_snack.dart';
import 'package:bilpark/widgets/status_badge.dart';
import 'package:bilpark/widgets/cancel_entry_dialog.dart';

void main() {
  tearDown(() => errorHapticOverride = null);

  testWidgets('titreşim yalnızca hata (danger) bildiriminde, bir kez', (tester) async {
    var n = 0;
    errorHapticOverride = () => n++;
    late BuildContext ctx;
    await tester.pumpWidget(MaterialApp(
      theme: BilParkTheme.light(),
      home: Builder(builder: (c) {
        ctx = c;
        return const SizedBox();
      }),
    ));
    bpSnackBar(ctx, 'tamam', tone: BadgeTone.success);
    bpSnackBar(ctx, 'nötr');
    expect(n, 0);
    bpSnackBar(ctx, 'hata', tone: BadgeTone.danger);
    expect(n, 1);
  });

  test('iptal gerekçeleri geçerli ve tekrarsız', () {
    expect(cancelReasonChips.length, greaterThanOrEqualTo(7));
    expect(cancelReasonChips.toSet().length, cancelReasonChips.length);
    for (final r in cancelReasonChips) {
      expect(validateCancelReason(r), isNull);
    }
  });

  testWidgets('gerekçe çipi tek dokunuşla seçilir ve alanı doldurur, 360 px taşmaz', (tester) async {
    tester.view.physicalSize = const Size(720, 1280);
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(MaterialApp(
      theme: BilParkTheme.light(),
      home: Scaffold(body: CancelEntryDialog(submit: (_) async {})),
    ));
    await tester.tap(find.text('Araç park etmeden ayrıldı'));
    await tester.pump();
    expect(tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, 'Araç park etmeden ayrıldı')).selected, isTrue);
    expect(tester.widget<TextField>(find.byType(TextField)).controller!.text, 'Araç park etmeden ayrıldı');
    expect(tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, 'Mükerrer giriş')).selected, isFalse);
    expect(tester.takeException(), isNull);
  });
}
