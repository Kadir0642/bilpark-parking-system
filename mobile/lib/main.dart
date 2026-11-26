import 'dart:async';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'core/api_client.dart';
import 'core/constants.dart';
import 'core/entry_queue.dart';
import 'core/format.dart';
import 'core/license.dart';
import 'core/live_sync.dart';
import 'core/refresh_coalescer.dart';
import 'core/spot_events.dart';
import 'core/tariff.dart';
import 'core/user_update.dart';
import 'core/vehicle_types.dart';
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

  // Canlı olay kaynaklı yenilemeler: olaylar yerelde uygulanır, sunucudan okuma tek yerde birleştirilir
  // (en çok 2,5 sn'de bir), yalnızca GÖRÜNEN sekme için ve uygulama öndeyken yapılır.
  late final RefreshCoalescer _gate =
      RefreshCoalescer(action: () => _flushTab(_index, includeAged: false), canRun: () => _foreground);
  bool _foreground = true;
  final Set<RefreshTarget> _dirty = {}; // eskidiği bilinen ama henüz çekilmemiş veriler
  final Map<RefreshTarget, DateTime> _fetchedAt = {};
  final DateTime _startedAt = DateTime.now();

  /// Sekme verisi bu süreden eskiyse (olay gelmese de) sekmeye dönüşte tazelenir.
  static const Duration _maxAge = Duration(seconds: 60);

  // Bağlantı gelince çevrimdışı giriş kuyruğu boşaltılır; bir kesintiden sonra bütün telefonlar aynı anda
  // göndermesin diye başlangıç 0-20 sn rastgele geciktirilir.
  void _onLiveStatus() {
    if (liveSync.status.value == LiveStatus.online) entryQueue.flushLater();
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    TariffStore.load();
    VehicleTypeStore.load();
    LicenseStore.attach();
    LicenseStore.load();
    _eventSub = liveSync.events.listen(_onEvent);
    _reloadSub = liveSync.reloads.listen((_) {
      // Açılışta ekranlar ve depolar zaten kendi verisini yükledi; ilk senkron yüklemesi çifte çekim yapmasın
      final fresh = DateTime.now().difference(_startedAt) < const Duration(seconds: 5);
      if (!fresh) {
        TariffStore.load();
        VehicleTypeStore.load();
        LicenseStore.load();
      }
      _markAllDirty(skipFresh: true);
      _gate.request();
    });
    liveSync.onDeactivated = _onDeactivated;
    entryQueue.load().then((_) => entryQueue.flushLater(maxJitter: const Duration(seconds: 5)));
    liveSync.status.addListener(_onLiveStatus);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) liveSync.start(zoneId: widget.zoneId);
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pages.dispose();
    _gate.dispose();
    _eventSub?.cancel();
    _reloadSub?.cancel();
    liveSync.status.removeListener(_onLiveStatus);
    liveSync.onDeactivated = null;
    ApiClient.onReadOnly = null;
    LicenseStore.reset();
    liveSync.stop();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (_foreground) {
      liveSync.reconnectNow();
      entryQueue.flushLater(maxJitter: const Duration(seconds: 5));
      // Arka plandayken biriken olaylar tek yenilemeyle yetişir; uzun süre kapalı kaldıysa görünen sekme tazelenir
      _gate.resume();
      _flushTab(_index, includeAged: true);
    }
  }

  /// Bir sekmenin gösterdiği veriler.
  Set<RefreshTarget> _targetsOf(int tab) => switch (tab) {
        _home => {RefreshTarget.dashSpots, RefreshTarget.dashSummary},
        _street => {RefreshTarget.map},
        _history => {RefreshTarget.history},
        _ => {RefreshTarget.runaways},
      };

  void _markAllDirty({bool skipFresh = false}) {
    final now = DateTime.now();
    for (final t in RefreshTarget.values) {
      if (skipFresh && now.difference(_fetchedAt[t] ?? _startedAt) < const Duration(seconds: 3)) continue;
      _dirty.add(t);
    }
  }

  /// [tab] sekmesinin kirli (ve [includeAged] ise eskimiş ya da bağlantı kopukken ziyaret edilen) verilerini çeker.
  void _flushTab(int tab, {required bool includeAged}) {
    if (!mounted) return;
    final now = DateTime.now();
    final aged = includeAged && liveSync.status.value != LiveStatus.online;
    final todo = <RefreshTarget>{};
    for (final t in _targetsOf(tab)) {
      final old = includeAged && now.difference(_fetchedAt[t] ?? _startedAt) > _maxAge;
      if (_dirty.contains(t) || old || aged) todo.add(t);
    }
    if (todo.isEmpty) return;
    _dirty.removeAll(todo);
    for (final t in todo) {
      _fetchedAt[t] = now;
    }
    switch (tab) {
      case _home:
        _dashKey.currentState?.refresh(
          spots: todo.contains(RefreshTarget.dashSpots),
          summary: todo.contains(RefreshTarget.dashSummary),
          vehicleTypes: includeAged, // araç tipleri olayla değil, ekrana dönüşte tazelenir (TYPES olayı ayrıca işlenir)
        );
      case _street:
        // Kendi işlemimizin yenilemesi az önce çektiyse yinelenen istek atlanır
        _mapKey.currentState?.fetchActiveVehicles(minAge: const Duration(seconds: 2));
      case _history:
        _historyKey.currentState?.refresh();
      case _runaways:
        _runawayKey.currentState?.refresh();
    }
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
        // Etkin tip kümesi tarife akışıyla değişir; olay ayrıca gelse de liste sunucudan tazelenir
        VehicleTypeStore.load();
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
      case 'VEHICLE_TYPES_UPDATED':
        VehicleTypeStore.applyPayload(e.payload);
        VehicleTypeStore.load();
      case 'LICENSE_UPDATED':
        LicenseStore.load();
      case 'USER_UPDATED':
        final who = e.payload['username'];
        if (who == AppConstants.currentUser) _onUserUpdated(e.payload);
      case 'VEHICLE_ENTERED' || 'VEHICLE_EXITED' || 'VEHICLE_RUNAWAY' || 'VEHICLE_CANCELLED' || 'RECORD_CANCELLED':
        if (!_isMine(e)) return;
        // 1) Yük yeterliyse araç listesine ağsız uygulanır (çıkan araç silinir, giren eklenir)
        _dashKey.currentState?.applyEvent(e);
        _mapKey.currentState?.applyEvent(e);
        // 2) Geri kalan (özet, geçmiş, borç bilgisi) birleştirilmiş tek yenilemeyle, yalnızca görünen sekme için
        _dirty.addAll(targetsForEvent(e.type));
        _gate.request();
    }
  }

  /// Oturumu yerelde kapatıp giriş ekranına döner. [logoutFirst]: önce sunucuda vardiya kapatılmaya çalışılır, hata yutulur.
  Future<void> _signOut(String? message, {bool logoutFirst = false}) async {
    if (logoutFirst) {
      try {
        if (await ApiClient.getToken() != null) await ApiClient.post('/auth/logout');
      } catch (_) {
        // sunucuya ulaşılamasa da yerel çıkış yapılır
      }
    }
    await liveSync.stop();
    await ApiClient.clearToken();
    AppConstants.clearAuth();
    final nav = navigatorKey.currentState;
    if (nav == null) return;
    nav.pushAndRemoveUntil(MaterialPageRoute(builder: (_) => AuthScreen(message: message)), (route) => false);
  }

  /// Yönetici hesabı pasife aldı: oturum yerelde kapatılır (sunucuya /auth/logout gitmez).
  Future<void> _onDeactivated() => _signOut(ApiClient.deactivatedMessage);

  bool _handlingUserUpdate = false;

  /// Rol ya da saha değişti: güncel bilgi /me'den alınır. Saha değişince yeniden giriş en güvenli yoldur;
  /// giriş akışı sahayı login yanıtından alır ve vardiyayı yeni sahada açar.
  Future<void> _onUserUpdated(Map<String, dynamic> payload) async {
    if (_handlingUserUpdate) return;
    _handlingUserUpdate = true;
    try {
      var role = payload['role'] as String?;
      var zoneId = (payload['zoneId'] as num?)?.toInt();
      String? zoneName;
      try {
        final me = await ApiClient.get('/me');
        if (me is Map) {
          role = me['role'] as String?;
          zoneId = (me['zoneId'] as num?)?.toInt();
          zoneName = me['zoneName'] as String?;
        }
      } on ApiException catch (ex) {
        if (ex.isUnauthorized) return;
      } catch (_) {
        // /me alınamazsa olay yükü kullanılır
      }
      if (!mounted) return;
      switch (decideUserUpdate(role: role, zoneId: zoneId, currentZoneId: widget.zoneId)) {
        case UserUpdateAction.none:
          return;
        case UserUpdateAction.roleChanged:
          await _signOut('Hesabınızın rolü değiştirildi. Saha uygulaması yalnızca memurlar içindir.');
        case UserUpdateAction.zoneChanged:
          await _askRelogin('Sahanız değiştirildi: ${zoneName ?? 'yeni saha'}. '
              'Açık vardiyanız eski sahada kalır; yeni sahada çalışmak için yeniden giriş yapın.');
        case UserUpdateAction.zoneRemoved:
          await _askRelogin('Size atanmış saha kalmadı. Yöneticinize başvurun.');
      }
    } finally {
      _handlingUserUpdate = false;
    }
  }

  Future<void> _askRelogin(String text) async {
    final ctx = navigatorKey.currentContext;
    if (ctx == null) return;
    await showDialog<void>(
      context: ctx,
      barrierDismissible: false,
      builder: (d) => PopScope(
        canPop: false,
        child: AlertDialog(
          title: const Text('Saha değişti'),
          content: Text(text),
          actions: [FilledButton(onPressed: () => Navigator.pop(d), child: const Text('Yeniden giriş yap'))],
        ),
      ),
    );
    await _signOut(null, logoutFirst: true);
  }

  final _pages = PageController();
  int? _target; // dokunmayla başlatılan kaydırma sürerken ara sayfalar seçim sayılmaz

  void _select(int i) {
    final firstVisit = _visited.add(i);
    setState(() => _index = i);
    final cur = _pages.hasClients ? _pages.page?.round() : null;
    if (cur != null && cur != i) {
      _target = i;
      _pages.animateToPage(i, duration: const Duration(milliseconds: 250), curve: Curves.easeOut).whenComplete(() {
        if (_target == i) _target = null;
      });
    }
    if (firstVisit) {
      // ilk kurulumda ekran kendi verisini çeker
      final now = DateTime.now();
      for (final t in _targetsOf(i)) {
        _dirty.remove(t);
        _fetchedAt[t] = now;
      }
      return;
    }
    _flushTab(i, includeAged: true); // yalnızca eskimiş/kirli ya da bağlantı kopukken
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
          // Kaydırarak sekme değişir; tüm sayfalar canlı tutulur (Cadde ekranı hiç atılmaz).
          child: PageView(
            controller: _pages,
            allowImplicitScrolling: true, // komşu sayfa (Cadde) baştan kurulur
            onPageChanged: (i) {
              if (_target == null && i != _index) _select(i);
            },
            children: [
              _KeepAlive(child: DashboardScreen(
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
              )),
              _KeepAlive(
                  child: ParkingMapScreen(
                key: _mapKey,
                streetCode: widget.streetCode,
                streetName: widget.streetName,
                zoneId: widget.zoneId,
                zoneName: widget.zoneName,
              )),
              _KeepAlive(
                  child: _visited.contains(_history)
                      ? HistoryScreen(
                          key: _historyKey,
                          streetCode: widget.streetCode,
                          streetName: widget.streetName,
                          showOnlyRunaways: false)
                      : const SizedBox.shrink()),
              _KeepAlive(
                  child: _visited.contains(_runaways)
                      ? HistoryScreen(
                          key: _runawayKey,
                          streetCode: widget.streetCode,
                          streetName: widget.streetName,
                          showOnlyRunaways: true)
                      : const SizedBox.shrink()),
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

/// PageView sayfasını ekran dışına çıkınca da canlı tutar (durum, zamanlayıcı ve kuyruk korunur).
class _KeepAlive extends StatefulWidget {
  final Widget child;
  const _KeepAlive({required this.child});

  @override
  State<_KeepAlive> createState() => _KeepAliveState();
}

class _KeepAliveState extends State<_KeepAlive> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return widget.child;
  }
}
