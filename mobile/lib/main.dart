import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'core/api_client.dart';
import 'core/constants.dart';
import 'core/format.dart';
import 'core/license.dart';
import 'core/live_sync.dart';
import 'core/tariff.dart';
import 'core/theme.dart';
import 'screens/auth_screen.dart';
import 'screens/dashboard_screen.dart';
import 'screens/history_screen.dart';
import 'screens/parking_map_screen.dart';
import 'widgets/bp_snack.dart';
import 'widgets/license_banner.dart';

const String _themeModeKey = 'themeMode';
const String _legacyDarkKey = 'isDarkMode';

/// Tüm uygulamanın tema tercihini (Açık / Koyu / Sistem) dinleyen global haberci.
final ValueNotifier<ThemeMode> themeNotifier = ValueNotifier(ThemeMode.system);

ThemeMode _parseThemeMode(String? s) {
  switch (s) {
    case 'light':
      return ThemeMode.light;
    case 'dark':
      return ThemeMode.dark;
    default:
      return ThemeMode.system;
  }
}

/// Kayıtlı tercihi okur; eski `isDarkMode` (bool) anahtarı varsa yeni biçime taşır.
Future<ThemeMode> loadThemeMode(SharedPreferences prefs) async {
  final saved = prefs.getString(_themeModeKey);
  if (saved != null) return _parseThemeMode(saved);
  final legacy = prefs.getBool(_legacyDarkKey);
  if (legacy != null) {
    final mode = legacy ? ThemeMode.dark : ThemeMode.light;
    await prefs.setString(_themeModeKey, mode.name);
    await prefs.remove(_legacyDarkKey);
    return mode;
  }
  return ThemeMode.system;
}

Future<void> setThemeMode(ThemeMode mode) async {
  themeNotifier.value = mode;
  final prefs = await SharedPreferences.getInstance();
  await prefs.setString(_themeModeKey, mode.name);
}

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();
  themeNotifier.value = await loadThemeMode(prefs);
  runApp(const BilParkApp());
}

class BilParkApp extends StatelessWidget {
  const BilParkApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<ThemeMode>(
      valueListenable: themeNotifier,
      builder: (context, mode, child) {
        return MaterialApp(
          navigatorKey: navigatorKey,
          debugShowCheckedModeBanner: false,
          title: 'BilPark Pro',
          theme: BilParkTheme.light(),
          darkTheme: BilParkTheme.dark(),
          themeMode: mode,
          builder: (context, child) {
            final dark = Theme.of(context).brightness == Brightness.dark;
            return AnnotatedRegion<SystemUiOverlayStyle>(
              value: dark ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark,
              child: child ?? const SizedBox.shrink(),
            );
          },
          home: const AuthScreen(),
        );
      },
    );
  }
}

class MainContainer extends StatefulWidget {
  final String streetCode; // sunucudaki cadde kodu
  final String streetName; // görünen cadde adı
  final int? zoneId;
  final String? zoneName;

  const MainContainer({super.key, required this.streetCode, required this.streetName, this.zoneId, this.zoneName});

  @override
  State<MainContainer> createState() => _MainContainerState();
}

class _MainContainerState extends State<MainContainer> with WidgetsBindingObserver {
  static const int _home = 0, _street = 1, _history = 2, _runaways = 3;

  int _index = _home;
  // Cadde ekranı baştan kurulur ve hiç atılmaz (bekleyen işlemler ve yoklama onda yaşar); diğerleri ilk açılışta kurulur.
  final Set<int> _visited = {_home, _street};
  final _dashKey = GlobalKey<DashboardScreenState>();
  final _mapKey = GlobalKey<ParkingMapScreenState>();
  final _historyKey = GlobalKey<HistoryScreenState>();
  final _runawayKey = GlobalKey<HistoryScreenState>();

  StreamSubscription<SyncEvent>? _eventSub;
  StreamSubscription<void>? _reloadSub;
  Timer? _debounce;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    TariffStore.load();
    LicenseStore.attach();
    LicenseStore.load();
    _eventSub = liveSync.events.listen(_onEvent);
    _reloadSub = liveSync.reloads.listen((_) {
      TariffStore.load();
      LicenseStore.load();
      _scheduleRefresh();
    });
    liveSync.onDeactivated = _onDeactivated;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) liveSync.start(zoneId: widget.zoneId);
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _debounce?.cancel();
    _eventSub?.cancel();
    _reloadSub?.cancel();
    liveSync.onDeactivated = null;
    ApiClient.onReadOnly = null;
    LicenseStore.reset();
    liveSync.stop();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) liveSync.reconnectNow();
  }

  bool _isMine(SyncEvent e) {
    if (e.zoneId != null && widget.zoneId != null) return e.zoneId == widget.zoneId;
    return e.street == null || e.street == widget.streetCode;
  }

  void _onEvent(SyncEvent e) {
    if (!mounted) return;
    switch (e.type) {
      case 'TARIFF_UPDATED':
        final before = TariffStore.current.value;
        TariffStore.applyPayload(e.payload);
        final now = TariffStore.current.value;
        final up = now?.upcoming;
        final from = up?.effectiveFrom;
        final String msg;
        if (up != null && before?.upcoming?.id != up.id && from != null) {
          msg = 'Yeni tarife zamanlandı: ${formatDayMonth(from)} ${formatHm(from)}';
        } else if (before != null && now != null && before.id != now.id) {
          msg = 'Yeni tarife yürürlüğe girdi';
        } else {
          msg = 'Tarife güncellendi';
        }
        // Kuyrukta bekleyen eski tarife bildirimi yenisinin önüne geçmesin
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..showSnackBar(bpSnackBar(context, msg));
      case 'LICENSE_UPDATED':
        LicenseStore.load();
      case 'VEHICLE_ENTERED' || 'VEHICLE_EXITED' || 'VEHICLE_RUNAWAY' || 'VEHICLE_CANCELLED' || 'RECORD_CANCELLED':
        if (_isMine(e)) _scheduleRefresh();
    }
  }

  /// Art arda gelen olaylar tek yenilemeye indirgenir.
  void _scheduleRefresh() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 500), () {
      if (!mounted) return;
      _dashKey.currentState?.refresh();
      _historyKey.currentState?.refresh();
      _runawayKey.currentState?.refresh();
    });
  }

  /// Yönetici hesabı pasife aldı: oturum yerelde kapatılır (sunucuya /auth/logout gitmez).
  Future<void> _onDeactivated() async {
    await ApiClient.clearToken();
    AppConstants.clearAuth();
    final nav = navigatorKey.currentState;
    if (nav == null) return;
    nav.pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const AuthScreen(message: ApiClient.deactivatedMessage)), (route) => false);
  }

  void _select(int i) {
    final firstVisit = _visited.add(i);
    setState(() => _index = i);
    if (firstVisit) return; // ilk kurulumda ekran kendi verisini çeker
    switch (i) {
      case _home:
        _dashKey.currentState?.refresh();
      case _history:
        _historyKey.currentState?.refresh();
      case _runaways:
        _runawayKey.currentState?.refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: _index == _home,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _select(_home);
      },
      child: Scaffold(
        body: LicenseBannerHost(
          child: IndexedStack(
            index: _index,
            children: [
              DashboardScreen(
                key: _dashKey,
                streetCode: widget.streetCode,
                streetName: widget.streetName,
                zoneId: widget.zoneId,
                zoneName: widget.zoneName,
                onOpenStreet: () => _select(_street),
                onScanPlate: () {
                  _select(_street);
                  _mapKey.currentState?.scanPlate();
                },
                onManualEntry: () {
                  _select(_street);
                  _mapKey.currentState?.showEntryForm('');
                },
              ),
              ParkingMapScreen(
                key: _mapKey,
                streetCode: widget.streetCode,
                streetName: widget.streetName,
                zoneId: widget.zoneId,
                zoneName: widget.zoneName,
              ),
              _visited.contains(_history)
                  ? HistoryScreen(
                      key: _historyKey,
                      streetCode: widget.streetCode,
                      streetName: widget.streetName,
                      showOnlyRunaways: false)
                  : const SizedBox.shrink(),
              _visited.contains(_runaways)
                  ? HistoryScreen(
                      key: _runawayKey,
                      streetCode: widget.streetCode,
                      streetName: widget.streetName,
                      showOnlyRunaways: true)
                  : const SizedBox.shrink(),
            ],
          ),
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: _index,
          onDestinationSelected: _select,
          destinations: const [
            NavigationDestination(icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home), label: 'Ana sayfa'),
            NavigationDestination(icon: Icon(Icons.signpost_outlined), selectedIcon: Icon(Icons.signpost), label: 'Cadde'),
            NavigationDestination(icon: Icon(Icons.history), selectedIcon: Icon(Icons.history), label: 'Geçmiş'),
            NavigationDestination(
                icon: Icon(Icons.warning_amber_rounded), selectedIcon: Icon(Icons.warning_rounded), label: 'Kaçaklar'),
          ],
        ),
      ),
    );
  }
}
