// Anlık senkron istemcisi (SSE). 6 sn'lik yoklamanın yerini alır. Sözleşme: docs/api-asama3.md §5.
// Akış: GET /sync (baz sürüm) -> reload -> GET /events?since=<sürüm>. Kopunca üstel beklemeyle
// GET /sync?since= ile eksikler alınır, akış tekrar açılır.

import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'api_client.dart';
import 'app_info.dart';
import 'constants.dart';

enum LiveStatus { connecting, online, reconnecting, offline }

/// Sunucudan gelen tek olay (zarf). Hepsi ayrıştırılmış JSON'dan üretilir.
class SyncEvent {
  final int version;
  final String type;
  final String? scope;
  final int? zoneId;
  final String? street;
  final int? targetUserId;
  final DateTime? at;
  final Map<String, dynamic> payload;

  const SyncEvent({
    required this.version,
    required this.type,
    this.scope,
    this.zoneId,
    this.street,
    this.targetUserId,
    this.at,
    this.payload = const {},
  });

  factory SyncEvent.fromJson(Map<String, dynamic> j) => SyncEvent(
        version: (j['version'] as num?)?.toInt() ?? 0,
        type: (j['type'] as String?) ?? '',
        scope: j['scope'] as String?,
        zoneId: (j['zoneId'] as num?)?.toInt(),
        street: j['street'] as String?,
        targetUserId: (j['targetUserId'] as num?)?.toInt(),
        at: j['at'] is String ? DateTime.tryParse(j['at'] as String) : null,
        payload: j['payload'] is Map ? Map<String, dynamic>.from(j['payload'] as Map) : const {},
      );
}

class _Unauthorized implements Exception {}

class _Failure implements Exception {
  final String reason;
  _Failure(this.reason);
  @override
  String toString() => 'LiveSync: $reason';
}

Duration _defaultBackoff(int attempt) {
  const secs = [1, 2, 4, 8, 16, 30];
  final i = (attempt - 1).clamp(0, secs.length - 1);
  return Duration(seconds: secs[i]);
}

class LiveSync {
  final String apiBase;
  final Future<String?> Function() token;
  final Future<String> Function()? deviceId;
  final http.Client Function() _clientFactory;
  final Duration heartbeatTimeout;
  final Duration Function(int attempt) _backoff;
  final int offlineAfterFailures;

  LiveSync({
    required this.apiBase,
    required this.token,
    this.deviceId,
    http.Client Function()? clientFactory,
    this.heartbeatTimeout = const Duration(seconds: 60),
    Duration Function(int attempt)? backoff,
    this.offlineAfterFailures = 3,
  })  : _clientFactory = clientFactory ?? http.Client.new,
        _backoff = backoff ?? _defaultBackoff;

  final ValueNotifier<LiveStatus> status = ValueNotifier(LiveStatus.connecting);

  /// Son başarılı kare / senkron zamanı.
  final ValueNotifier<DateTime?> lastSync = ValueNotifier(null);

  /// 401 geldiğinde (yeniden denemeden) çağrılır.
  void Function()? onUnauthorized;

  /// USER_DEACTIVATED olayında çağrılır; senkron durdurulur.
  void Function()? onDeactivated;

  final StreamController<SyncEvent> _events = StreamController<SyncEvent>.broadcast();
  final StreamController<void> _reloads = StreamController<void>.broadcast();

  Stream<SyncEvent> get events => _events.stream;

  /// Ekranların verisini baştan yüklemesi gereken anlar.
  Stream<void> get reloads => _reloads.stream;

  int? _version;
  int? get version => _version;

  int? _zoneId;
  int _gen = 0;
  bool _running = false;
  bool _disposed = false;
  int _failures = 0;
  bool _catching = false;
  final Set<http.Client> _clients = {};
  Timer? _sleepTimer;
  Completer<void>? _sleepC;

  bool _alive(int gen) => !_disposed && _running && gen == _gen;

  /// Senkronu başlatır. [zoneId] verilirse başka bölümlerin ZONE olayları atılır (admin: null).
  Future<void> start({int? zoneId}) async {
    if (_disposed) return;
    _halt();
    _zoneId = zoneId;
    _version = null;
    _failures = 0;
    _running = true;
    status.value = LiveStatus.connecting;
    final gen = ++_gen;
    unawaited(_loop(gen));
  }

  Future<void> stop() async {
    _halt();
    if (!_disposed) status.value = LiveStatus.offline;
  }

  /// Uygulama öne gelince vb.: geri çekilme beklemesini atla.
  void reconnectNow() {
    final c = _sleepC;
    if (c != null && !c.isCompleted) c.complete();
  }

  void dispose() {
    if (_disposed) return;
    _halt();
    _disposed = true;
    _events.close();
    _reloads.close();
    status.dispose();
    lastSync.dispose();
  }

  // --- İç işleyiş ---

  /// Her şeyi eşzamanlı durdurur: döngü, zamanlayıcılar, açık http istemcileri.
  void _halt() {
    _running = false;
    _gen++;
    _sleepTimer?.cancel();
    _sleepTimer = null;
    final c = _sleepC;
    if (c != null && !c.isCompleted) c.complete();
    _sleepC = null;
    for (final cl in _clients.toList()) {
      cl.close();
    }
    _clients.clear();
  }

  void _setStatus(LiveStatus s) {
    if (!_disposed) status.value = s;
  }

  void _touch() {
    if (!_disposed) lastSync.value = DateTime.now();
  }

  void _unauthorized(int gen) {
    if (!_alive(gen)) return;
    _halt();
    _setStatus(LiveStatus.offline);
    onUnauthorized?.call();
  }

  /// Başarısız deneme: durum reconnecting, yeterince üst üste olunca offline.
  void _fail() {
    _failures++;
    _setStatus(_failures >= offlineAfterFailures ? LiveStatus.offline : LiveStatus.reconnecting);
  }

  Future<void> _sleep(int gen, Duration d) async {
    if (!_alive(gen)) return;
    final c = Completer<void>();
    _sleepC = c;
    _sleepTimer = Timer(d, () {
      if (!c.isCompleted) c.complete();
    });
    await c.future;
    _sleepTimer?.cancel();
    _sleepTimer = null;
    if (identical(_sleepC, c)) _sleepC = null;
  }

  Future<Map<String, String>> _headers({bool stream = false}) async {
    final h = <String, String>{};
    final t = await token();
    if (t != null) h['Authorization'] = 'Bearer $t';
    final d = deviceId;
    if (d != null) h['X-Device-Id'] = await d();
    h.addAll(await AppInfo.headers());
    if (stream) {
      h['Accept'] = 'text/event-stream';
      h['Cache-Control'] = 'no-cache';
    }
    return h;
  }

  http.Client _newClient() {
    final c = _clientFactory();
    _clients.add(c);
    return c;
  }

  void _release(http.Client c) {
    _clients.remove(c);
    c.close();
  }

  Future<void> _loop(int gen) async {
    try {
      // 1) Baz sürüm
      while (_alive(gen) && _version == null) {
        try {
          final r = await _getSync(null);
          if (!_alive(gen)) return;
          _version = (r['version'] as num?)?.toInt() ?? 0;
          _touch();
          if (!_reloads.isClosed) _reloads.add(null);
        } on _Unauthorized {
          _unauthorized(gen);
          return;
        } catch (_) {
          if (!_alive(gen)) return;
          _fail();
          await _sleep(gen, _backoff(_failures));
        }
      }

      // 2) Akış + yeniden bağlanma
      var needCatchUp = false;
      while (_alive(gen)) {
        if (needCatchUp) {
          await _sleep(gen, _backoff(_failures));
          if (!_alive(gen)) return;
          try {
            await _catchUp(gen);
          } on _Unauthorized {
            _unauthorized(gen);
            return;
          } catch (_) {
            if (!_alive(gen)) return;
            _fail();
            continue;
          }
          if (!_alive(gen)) return;
        }
        try {
          await _stream(gen);
          if (!_alive(gen)) return;
          _fail(); // akış sunucu tarafından kapandı
        } on _Unauthorized {
          _unauthorized(gen);
          return;
        } catch (_) {
          if (!_alive(gen)) return;
          _fail();
        }
        needCatchUp = true;
      }
    } catch (_) {
      // Beklenmeyen hata döngüyü sessizce bitirir; kullanıcı yeniden start() çağırabilir.
    }
  }

  /// GET /sync[?since=]. 401 -> _Unauthorized, diğer hatalar _Failure.
  Future<Map<String, dynamic>> _getSync(int? since) async {
    final client = _newClient();
    try {
      final uri = Uri.parse('$apiBase/sync').replace(queryParameters: since == null ? null : {'since': '$since'});
      final req = http.Request('GET', uri)..headers.addAll(await _headers());
      final resp = await client.send(req).timeout(const Duration(seconds: 15));
      final body = await resp.stream.bytesToString().timeout(const Duration(seconds: 15));
      if (resp.statusCode == 401) throw _Unauthorized();
      if (resp.statusCode != 200) throw _Failure('sync ${resp.statusCode}');
      final j = jsonDecode(body);
      if (j is! Map) throw _Failure('sync gövdesi geçersiz');
      return Map<String, dynamic>.from(j);
    } finally {
      _release(client);
    }
  }

  /// Kopma sonrası (veya HEARTBEAT'te geride kalınca) eksik olayları alır.
  Future<void> _catchUp(int gen) async {
    if (_catching) return;
    _catching = true;
    try {
      while (_alive(gen)) {
        final r = await _getSync(_version);
        if (!_alive(gen)) return;
        final v = (r['version'] as num?)?.toInt();
        if (r['resetRequired'] == true) {
          if (v != null) _version = v;
          _touch();
          if (!_reloads.isClosed) _reloads.add(null);
          return;
        }
        final evs = r['events'];
        var got = 0;
        if (evs is List) {
          for (final e in evs) {
            if (e is! Map) continue;
            got++;
            _deliver(SyncEvent.fromJson(Map<String, dynamic>.from(e)));
            if (!_alive(gen)) return;
          }
        }
        if (r['hasMore'] == true && got > 0) continue;
        if (v != null && (_version == null || v > _version!)) _version = v;
        _touch();
        return;
      }
    } finally {
      _catching = false;
    }
  }

  /// Olayı sürüm sırasıyla yayınlar; eski/yinelenen sürümler atılır.
  void _deliver(SyncEvent e) {
    final cur = _version ?? 0;
    if (e.version <= cur) return;
    _version = e.version;
    _touch();
    final zoneFilter = _zoneId;
    final dropped = e.scope == 'ZONE' && zoneFilter != null && e.zoneId != zoneFilter;
    if (!dropped && !_events.isClosed) _events.add(e);
    if (e.type == 'USER_DEACTIVATED') {
      _halt();
      _setStatus(LiveStatus.offline);
      onDeactivated?.call();
    }
  }

  /// Tek bir SSE bağlantısı; kapanana, hata alana veya sessizlik süresi dolana kadar bekler.
  Future<void> _stream(int gen) async {
    final client = _newClient();
    StreamSubscription<String>? sub;
    Timer? watchdog;
    final done = Completer<void>();
    try {
      final uri = Uri.parse('$apiBase/events').replace(queryParameters: {'since': '${_version ?? 0}'});
      final req = http.Request('GET', uri)..headers.addAll(await _headers(stream: true));
      final resp = await client.send(req).timeout(heartbeatTimeout);
      if (!_alive(gen)) return;
      if (resp.statusCode == 401) throw _Unauthorized();
      if (resp.statusCode != 200) throw _Failure('events ${resp.statusCode}');

      void arm() {
        watchdog?.cancel();
        watchdog = Timer(heartbeatTimeout, () {
          if (!done.isCompleted) done.completeError(_Failure('kare gelmedi'));
        });
      }

      arm();
      String? event;
      final dataLines = <String>[];

      void dispatch() {
        final name = event;
        final data = dataLines.join('\n');
        event = null;
        dataLines.clear();
        if (name == null && data.isEmpty) return;
        _onFrame(gen, name ?? 'message', data);
      }

      sub = resp.stream.transform(utf8.decoder).transform(const LineSplitter()).listen(
        (line) {
          arm();
          if (!_alive(gen)) return;
          if (line.isEmpty) {
            dispatch();
          } else if (line.startsWith(':')) {
            // yorum satırı: yalnızca canlılık sinyali
          } else {
            final i = line.indexOf(':');
            final field = i < 0 ? line : line.substring(0, i);
            var value = i < 0 ? '' : line.substring(i + 1);
            if (value.startsWith(' ')) value = value.substring(1);
            if (field == 'event') {
              event = value;
            } else if (field == 'data') {
              dataLines.add(value);
            }
            // id: alanı gerekmiyor; sürüm veri içinde taşınıyor
          }
        },
        onError: (Object e) {
          if (!done.isCompleted) done.completeError(e);
        },
        onDone: () {
          if (!done.isCompleted) done.complete();
        },
        cancelOnError: true,
      );
      await done.future;
    } finally {
      watchdog?.cancel();
      await sub?.cancel();
      _release(client);
    }
  }

  void _onFrame(int gen, String name, String data) {
    Map<String, dynamic> j = const {};
    if (data.isNotEmpty) {
      try {
        final d = jsonDecode(data);
        if (d is Map) j = Map<String, dynamic>.from(d);
      } catch (_) {
        return; // bozuk kare: yok say
      }
    }
    switch (name) {
      case 'HELLO':
        _failures = 0;
        _setStatus(LiveStatus.online);
        _touch();
        break;
      case 'HEARTBEAT':
        _touch();
        final v = (j['version'] as num?)?.toInt();
        if (v != null && v > (_version ?? 0)) {
          unawaited(_catchUp(gen).catchError((Object e) {
            if (e is _Unauthorized) _unauthorized(gen);
          }));
        }
        break;
      case 'RESET':
        final v = (j['version'] as num?)?.toInt();
        if (v != null) _version = v;
        _touch();
        if (!_reloads.isClosed) _reloads.add(null);
        break;
      default:
        if (j.isEmpty) return;
        j.putIfAbsent('type', () => name);
        _deliver(SyncEvent.fromJson(j));
    }
  }
}

/// Uygulamanın tek canlı senkron örneği.
final LiveSync liveSync = LiveSync(
  apiBase: AppConstants.apiBase,
  token: ApiClient.getToken,
  deviceId: ApiClient.getDeviceId,
)..onUnauthorized = () {
    ApiClient.handleUnauthorized();
  };
