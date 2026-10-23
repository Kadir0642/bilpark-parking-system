// BilPark tasarım sistemi: renk tokenları ve tema.
// Ekranlar renkleri yalnızca buradan alır: context.bp.primary, context.bp.dangerSoft ...
// Sabit Color(...) ya da Colors.xxx yalnızca bu dosyada bulunur.
import 'package:flutter/material.dart';

@immutable
class BilParkColors extends ThemeExtension<BilParkColors> {
  final Color bg;
  final Color surface;
  final Color surface2;
  final Color border;
  final Color borderStrong;
  final Color text;
  final Color text2;
  final Color primary;
  final Color onPrimary;
  final Color primarySoft;
  final Color onPrimarySoft;
  final Color success;
  final Color onSuccess;
  final Color successSoft;
  final Color onSuccessSoft;
  final Color danger;
  final Color onDanger;
  final Color dangerSoft;
  final Color onDangerSoft;
  final Color warning;
  final Color onWarning;
  final Color warningSoft;
  final Color onWarningSoft;
  final Color sub;
  final Color subSoft;
  final Color onSubSoft;
  final Color asphalt;
  final Color asphaltLine;
  final Color plateBg;
  final Color plateText;
  final Color plateStrip;
  final Color plateStripText;

  const BilParkColors({
    required this.bg,
    required this.surface,
    required this.surface2,
    required this.border,
    required this.borderStrong,
    required this.text,
    required this.text2,
    required this.primary,
    required this.onPrimary,
    required this.primarySoft,
    required this.onPrimarySoft,
    required this.success,
    required this.onSuccess,
    required this.successSoft,
    required this.onSuccessSoft,
    required this.danger,
    required this.onDanger,
    required this.dangerSoft,
    required this.onDangerSoft,
    required this.warning,
    required this.onWarning,
    required this.warningSoft,
    required this.onWarningSoft,
    required this.sub,
    required this.subSoft,
    required this.onSubSoft,
    required this.asphalt,
    required this.asphaltLine,
    required this.plateBg,
    required this.plateText,
    required this.plateStrip,
    required this.plateStripText,
  });

  static const light = BilParkColors(
    bg: Color(0xFFF3F5F8),
    surface: Color(0xFFFFFFFF),
    surface2: Color(0xFFEDF0F4),
    border: Color(0xFFCDD5DF),
    borderStrong: Color(0xFF94A3B8),
    text: Color(0xFF0E1726),
    text2: Color(0xFF4A5668),
    primary: Color(0xFF1747C6),
    onPrimary: Color(0xFFFFFFFF),
    primarySoft: Color(0xFFE3EBFF),
    onPrimarySoft: Color(0xFF12379A),
    success: Color(0xFF06734F),
    onSuccess: Color(0xFFFFFFFF),
    successSoft: Color(0xFFDDF5EA),
    onSuccessSoft: Color(0xFF05553B),
    danger: Color(0xFFB42318),
    onDanger: Color(0xFFFFFFFF),
    dangerSoft: Color(0xFFFDE7E5),
    onDangerSoft: Color(0xFF8A1A12),
    warning: Color(0xFF9A4A00),
    onWarning: Color(0xFFFFFFFF),
    warningSoft: Color(0xFFFFF1D6),
    onWarningSoft: Color(0xFF7A3A00),
    sub: Color(0xFF0B6B66),
    subSoft: Color(0xFFD7F3F0),
    onSubSoft: Color(0xFF0A5450),
    asphalt: Color(0xFF2B3442),
    asphaltLine: Color(0xFFF5F7FA),
    plateBg: Color(0xFFFFFFFF),
    plateText: Color(0xFF0E1726),
    plateStrip: Color(0xFF1747C6),
    plateStripText: Color(0xFFFFFFFF),
  );

  static const dark = BilParkColors(
    bg: Color(0xFF0A0F1A),
    surface: Color(0xFF121A29),
    surface2: Color(0xFF1A2436),
    border: Color(0xFF2A374D),
    borderStrong: Color(0xFF4A5A73),
    text: Color(0xFFF2F5F9),
    text2: Color(0xFFB3BFCF),
    primary: Color(0xFF7FA6FF),
    onPrimary: Color(0xFF08122B),
    primarySoft: Color(0xFF1B2B52),
    onPrimarySoft: Color(0xFFC9D8FF),
    success: Color(0xFF4ADE9B),
    onSuccess: Color(0xFF062A1B),
    successSoft: Color(0xFF0F3326),
    onSuccessSoft: Color(0xFFA7F3CE),
    danger: Color(0xFFFF8A80),
    onDanger: Color(0xFF2A0503),
    dangerSoft: Color(0xFF3D1716),
    onDangerSoft: Color(0xFFFFC9C3),
    warning: Color(0xFFFFB547),
    onWarning: Color(0xFF2A1A00),
    warningSoft: Color(0xFF3A2A0D),
    onWarningSoft: Color(0xFFFFDDA3),
    sub: Color(0xFF4FD1C5),
    subSoft: Color(0xFF0E3330),
    onSubSoft: Color(0xFFA6F0E8),
    asphalt: Color(0xFF05080F),
    asphaltLine: Color(0xFF8A97AB),
    plateBg: Color(0xFFF2F5F9),
    plateText: Color(0xFF0E1726),
    plateStrip: Color(0xFF3A66E0),
    plateStripText: Color(0xFFFFFFFF),
  );

  @override
  BilParkColors copyWith() => this;

  @override
  BilParkColors lerp(ThemeExtension<BilParkColors>? other, double t) {
    if (other is! BilParkColors) return this;
    Color l(Color a, Color b) => Color.lerp(a, b, t)!;
    return BilParkColors(
      bg: l(bg, other.bg),
      surface: l(surface, other.surface),
      surface2: l(surface2, other.surface2),
      border: l(border, other.border),
      borderStrong: l(borderStrong, other.borderStrong),
      text: l(text, other.text),
      text2: l(text2, other.text2),
      primary: l(primary, other.primary),
      onPrimary: l(onPrimary, other.onPrimary),
      primarySoft: l(primarySoft, other.primarySoft),
      onPrimarySoft: l(onPrimarySoft, other.onPrimarySoft),
      success: l(success, other.success),
      onSuccess: l(onSuccess, other.onSuccess),
      successSoft: l(successSoft, other.successSoft),
      onSuccessSoft: l(onSuccessSoft, other.onSuccessSoft),
      danger: l(danger, other.danger),
      onDanger: l(onDanger, other.onDanger),
      dangerSoft: l(dangerSoft, other.dangerSoft),
      onDangerSoft: l(onDangerSoft, other.onDangerSoft),
      warning: l(warning, other.warning),
      onWarning: l(onWarning, other.onWarning),
      warningSoft: l(warningSoft, other.warningSoft),
      onWarningSoft: l(onWarningSoft, other.onWarningSoft),
      sub: l(sub, other.sub),
      subSoft: l(subSoft, other.subSoft),
      onSubSoft: l(onSubSoft, other.onSubSoft),
      asphalt: l(asphalt, other.asphalt),
      asphaltLine: l(asphaltLine, other.asphaltLine),
      plateBg: l(plateBg, other.plateBg),
      plateText: l(plateText, other.plateText),
      plateStrip: l(plateStrip, other.plateStrip),
      plateStripText: l(plateStripText, other.plateStripText),
    );
  }
}

/// Boşluk, köşe ve dokunma ölçüleri (4'ün katları).
class BpSize {
  BpSize._();
  static const double s1 = 4, s2 = 8, s3 = 12, s4 = 16, s5 = 20, s6 = 24, s8 = 32, s12 = 48;
  static const double rSm = 8, rMd = 12, rLg = 16;
  static const double tap = 48; // her dokunulan öğe en az
  static const double tapLg = 56; // ana eylemler
  static const double screenPad = 16;
}

extension BilParkThemeX on BuildContext {
  BilParkColors get bp => Theme.of(this).extension<BilParkColors>()!;
}

class BilParkTheme {
  BilParkTheme._();

  static ThemeData light() => _build(BilParkColors.light, Brightness.light);
  static ThemeData dark() => _build(BilParkColors.dark, Brightness.dark);

  static ThemeData _build(BilParkColors c, Brightness b) {
    final scheme = ColorScheme(
      brightness: b,
      primary: c.primary,
      onPrimary: c.onPrimary,
      primaryContainer: c.primarySoft,
      onPrimaryContainer: c.onPrimarySoft,
      secondary: c.sub,
      onSecondary: c.onPrimary,
      secondaryContainer: c.subSoft,
      onSecondaryContainer: c.onSubSoft,
      tertiary: c.warning,
      onTertiary: c.onWarning,
      error: c.danger,
      onError: c.onDanger,
      errorContainer: c.dangerSoft,
      onErrorContainer: c.onDangerSoft,
      surface: c.surface,
      onSurface: c.text,
      onSurfaceVariant: c.text2,
      surfaceContainerHighest: c.surface2,
      surfaceContainerHigh: c.surface2,
      surfaceContainer: c.surface2,
      surfaceContainerLow: c.surface,
      surfaceContainerLowest: c.surface,
      outline: c.borderStrong,
      outlineVariant: c.border,
      inverseSurface: c.text,
      onInverseSurface: c.bg,
      inversePrimary: c.primarySoft,
      shadow: c.text,
      scrim: c.text,
    );

    final base = ThemeData(useMaterial3: true, colorScheme: scheme, brightness: b);
    final text = base.textTheme.apply(bodyColor: c.text, displayColor: c.text);
    const btnShape = RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(BpSize.rMd)));
    const btnText = TextStyle(fontSize: 17, fontWeight: FontWeight.w700);

    OutlineInputBorder inputBorder(Color color, [double w = 2]) => OutlineInputBorder(
          borderRadius: BorderRadius.circular(BpSize.rSm),
          borderSide: BorderSide(color: color, width: w),
        );

    return base.copyWith(
      extensions: [c],
      scaffoldBackgroundColor: c.bg,
      canvasColor: c.surface,
      dividerColor: c.border,
      textTheme: text.copyWith(
        headlineSmall: text.headlineSmall?.copyWith(fontSize: 24, fontWeight: FontWeight.w700),
        titleLarge: text.titleLarge?.copyWith(fontSize: 20, fontWeight: FontWeight.w700),
        titleMedium: text.titleMedium?.copyWith(fontSize: 17, fontWeight: FontWeight.w700),
        bodyLarge: text.bodyLarge?.copyWith(fontSize: 16),
        bodyMedium: text.bodyMedium?.copyWith(fontSize: 15),
        labelLarge: text.labelLarge?.copyWith(fontSize: 14, fontWeight: FontWeight.w600),
        bodySmall: text.bodySmall?.copyWith(fontSize: 13, color: c.text2),
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: c.surface,
        foregroundColor: c.text,
        surfaceTintColor: c.surface,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: c.text),
        shape: Border(bottom: BorderSide(color: c.border)),
      ),
      cardTheme: CardThemeData(
        color: c.surface,
        surfaceTintColor: c.surface,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(BpSize.rMd),
          side: BorderSide(color: c.border),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: c.primary,
          foregroundColor: c.onPrimary,
          disabledBackgroundColor: c.surface2,
          disabledForegroundColor: c.text2,
          minimumSize: const Size(BpSize.tap, BpSize.tapLg),
          shape: btnShape,
          textStyle: btnText,
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: c.primary,
          foregroundColor: c.onPrimary,
          elevation: 0,
          minimumSize: const Size(BpSize.tap, BpSize.tapLg),
          shape: btnShape,
          textStyle: btnText,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: c.text,
          backgroundColor: c.surface,
          side: BorderSide(color: c.borderStrong, width: 2),
          minimumSize: const Size(BpSize.tap, BpSize.tap),
          shape: btnShape,
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: c.primary,
          minimumSize: const Size(BpSize.tap, BpSize.tap),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(minimumSize: const Size(BpSize.tap, BpSize.tap), foregroundColor: c.text),
      ),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        backgroundColor: c.primary,
        foregroundColor: c.onPrimary,
        elevation: 2,
        extendedTextStyle: btnText,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(BpSize.rLg))),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: c.surface,
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 16),
        labelStyle: TextStyle(color: c.text2, fontWeight: FontWeight.w600),
        hintStyle: TextStyle(color: c.text2),
        prefixIconColor: c.text2,
        suffixIconColor: c.text2,
        border: inputBorder(c.borderStrong),
        enabledBorder: inputBorder(c.borderStrong),
        focusedBorder: inputBorder(c.primary),
        errorBorder: inputBorder(c.danger),
        focusedErrorBorder: inputBorder(c.danger),
        errorStyle: TextStyle(color: c.danger, fontSize: 13),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: c.text,
        contentTextStyle: TextStyle(color: c.bg, fontSize: 15),
        actionTextColor: c.bg,
        behavior: SnackBarBehavior.floating,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(BpSize.rMd))),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: c.surface,
        surfaceTintColor: c.surface,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(BpSize.rLg))),
        titleTextStyle: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: c.text),
        contentTextStyle: TextStyle(fontSize: 16, color: c.text),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: c.surface,
        surfaceTintColor: c.surface,
        showDragHandle: true,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(BpSize.rLg))),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: c.surface,
        surfaceTintColor: c.surface,
        indicatorColor: c.primarySoft,
        height: 72,
        labelTextStyle: WidgetStateProperty.resolveWith((s) => TextStyle(
              fontSize: 13,
              fontWeight: s.contains(WidgetState.selected) ? FontWeight.w700 : FontWeight.w600,
              color: s.contains(WidgetState.selected) ? c.onPrimarySoft : c.text2,
            )),
        iconTheme: WidgetStateProperty.resolveWith(
            (s) => IconThemeData(color: s.contains(WidgetState.selected) ? c.onPrimarySoft : c.text2)),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: c.surface,
        selectedColor: c.primarySoft,
        labelStyle: TextStyle(color: c.text, fontWeight: FontWeight.w600, fontSize: 14),
        side: BorderSide(color: c.borderStrong),
        shape: const StadiumBorder(),
        checkmarkColor: c.onPrimarySoft,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? c.onPrimary : c.borderStrong),
        trackColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? c.primary : c.surface2),
      ),
      segmentedButtonTheme: SegmentedButtonThemeData(
        style: ButtonStyle(
          minimumSize: const WidgetStatePropertyAll(Size(BpSize.tap, BpSize.tap)),
          backgroundColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? c.primarySoft : c.surface),
          foregroundColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? c.onPrimarySoft : c.text),
          side: WidgetStatePropertyAll(BorderSide(color: c.borderStrong)),
        ),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(color: c.primary),
      listTileTheme: ListTileThemeData(iconColor: c.text2, textColor: c.text, minTileHeight: 56),
      dividerTheme: DividerThemeData(color: c.border, thickness: 1, space: 1),
    );
  }
}
