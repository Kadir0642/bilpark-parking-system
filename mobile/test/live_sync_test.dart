import 'dart:async';
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:bilpark/core/live_sync.dart';

/// SSE karesi. Spring iki nokta üstüste sonrası boşluk koymaz; ikisini de test ederiz.
String frame(String event, Map<String, dynamic> data, {bool space = false, String nl = '\n'}) {
  final sp = space ? ' ' : '';
  return 'event:$sp$event${nl}data:$sp${jsonEncode(data)}$nl$nl';
}

Map<String, dynamic> ev(int v, String type, {String scope = 'ZONE', int? zoneId = 1, Map<String, dynamic>? payload}) => {
      'version': v,
      'type': type,
      'scope': scope,
      'zoneId': ?zoneId,
      'street': 'TEVFIK_BEY',
      'at': '2026-10-23T14:05:00',
      'payload': payload ?? {'plate': '34ABC123'},
    };

Map<String, dynamic> syncBody(int v, {List<Map<String, dynamic>> events = const [], bool hasMore = false, bool reset = false}) =>
    {'version': v, 'events': events, 'hasMore': hasMore, 'resetRequired': reset};

/// Sahte sunucu: /sync yanıtları ve /events oturumları sırayla tüketilir.
class FakeServer {
  final requests = <http.BaseRequest>[];
  final syncResponses = <Map<String, dynamic>>[]; // boşsa varsayılan: version 10
  final syncStatus = <int>[];
  final eventSessions = <Future<http.StreamedResponse> Function()>[];
  final controllers = <StreamController<List<int>>>[];

  List<http.BaseRequest> get eventReqs => requests.where((r) => r.url.path.endsWith('/events')).toList();
  List<http.BaseRequest> get syncReqs => requests.where((r) => r.url.path.endsWith('/sync')).toList();

  StreamController<List<int>> openSession() {
    final c = StreamController<List<int>>();
    controllers.add(c);
    eventSessions.add(() async => http.StreamedResponse(c.stream, 200));
    return c;
  }

  void failSession(int status) {
    eventSessions.add(() async => http.StreamedResponse(Stream.value(utf8.encode('{"error":"x"}')), status));
  }

  http.Client client() => MockClient.streaming((req, body) async {
        requests.add(req);
        if (req.url.path.endsWith('/events')) {
          if (eventSessions.isEmpty) return http.StreamedResponse(Stream.value(const <int>[]), 500);
          return eventSessions.removeAt(0)();
        }
        final status = syncStatus.isEmpty ? 200 : syncStatus.removeAt(0);
        final body = syncResponses.isEmpty ? syncBody(10) : syncResponses.removeAt(0);
        return http.StreamedResponse(Stream.value(utf8.encode(jsonEncode(body))), status);
      });
}

void send(StreamController<List<int>> c, String s) => c.add(utf8.encode(s));

Future<void> waitFor(bool Function() cond, {int ms = 4000}) async {
  final end = DateTime.now().add(Duration(milliseconds: ms));
  while (!cond()) {
    if (DateTime.now().isAfter(end)) fail('zaman aşımı: koşul gerçekleşmedi');
    await Future<void>.delayed(const Duration(milliseconds: 5));
  }
}

void main() {
  late FakeServer server;
  late LiveSync sync;
  late List<SyncEvent> got;
  late int reloads;
  late List<int> backoffAttempts;

  LiveSync make({Duration heartbeat = const Duration(seconds: 5), int offlineAfter = 3}) {
    final s = LiveSync(
      apiBase: 'http://test/api',
      token: () async => 'tok',
      deviceId: () async => 'dev1',
      clientFactory: server.client,
      heartbeatTimeout: heartbeat,
      backoff: (a) {
        backoffAttempts.add(a);
        return Duration(milliseconds: 10 * a);
      },
      offlineAfterFailures: offlineAfter,
    );
    s.events.listen(got.add);
    s.reloads.listen((_) => reloads++);
    return s;
  }

  setUp(() {
    server = FakeServer();
    got = [];
    reloads = 0;
    backoffAttempts = [];
  });

  tearDown(() async {
    await sync.stop();
    sync.dispose();
    for (final c in server.controllers) {
      if (!c.isClosed) unawaited(c.close());
    }
  });

  test('kareler ayrıştırılır (boşluklu/boşluksuz, CRLF, çok satırlı data), olaylar yayınlanır, durum online olur', () async {
    sync = make();
    final c = server.openSession();
    await sync.start();
    await waitFor(() => server.eventReqs.isNotEmpty);

    final req = server.eventReqs.first;
    expect(req.url.queryParameters['since'], '10');
    expect(req.headers['Authorization'], 'Bearer tok');
    expect(req.headers['Accept'], 'text/event-stream');
    expect(req.headers['Cache-Control'], 'no-cache');
    expect(req.headers['X-Device-Id'], 'dev1');
    expect(reloads, 1); // baz sürümden sonra bir kez

    send(c, 'id:0\n${frame('HELLO', {'version': 10, 'userId': 2})}');
    send(c, ': yorum satırı\n\n');
    send(c, frame('VEHICLE_ENTERED', ev(11, 'VEHICLE_ENTERED'))); // boşluksuz (Spring)
    send(c, 'id: 12\n${frame('VEHICLE_EXITED', ev(12, 'VEHICLE_EXITED'), space: true, nl: '\r\n')}'); // boşluklu + CRLF
    // çok satırlı data: satırlar \n ile birleşir
    send(c, 'event:VEHICLE_RUNAWAY\ndata:{"version":13,"type":"VEHICLE_RUNAWAY",\ndata: "scope":"GLOBAL","payload":{}}\n\n');
    await waitFor(() => got.length == 3);

    expect(got.map((e) => e.version), [11, 12, 13]);
    expect(got.map((e) => e.type), ['VEHICLE_ENTERED', 'VEHICLE_EXITED', 'VEHICLE_RUNAWAY']);
    expect(got.first.zoneId, 1);
    expect(got.first.payload['plate'], '34ABC123');
    expect(got.first.at, DateTime(2026, 10, 23, 14, 5));
    expect(sync.version, 13);
    expect(sync.status.value, LiveStatus.online);
    expect(sync.lastSync.value, isNotNull);
  });

  test('parça parça gelen bayt akışı da doğru birleştirilir', () async {
    sync = make();
    final c = server.openSession();
    await sync.start();
    await waitFor(() => server.eventReqs.isNotEmpty);
    final full = utf8.encode(frame('VEHICLE_ENTERED', ev(11, 'VEHICLE_ENTERED', payload: {'plate': 'ŞÜ'})));
    for (var i = 0; i < full.length; i += 7) {
      c.add(full.sublist(i, i + 7 > full.length ? full.length : i + 7));
      await Future<void>.delayed(const Duration(milliseconds: 1));
    }
    await waitFor(() => got.length == 1);
    expect(got.single.payload['plate'], 'ŞÜ');
  });

  test('eski/yinelenen sürümler yok sayılır', () async {
    sync = make();
    final c = server.openSession();
    await sync.start();
    await waitFor(() => server.eventReqs.isNotEmpty);
    send(c, frame('VEHICLE_ENTERED', ev(11, 'VEHICLE_ENTERED')));
    send(c, frame('VEHICLE_ENTERED', ev(11, 'VEHICLE_ENTERED')));
    send(c, frame('VEHICLE_ENTERED', ev(9, 'VEHICLE_ENTERED')));
    send(c, frame('VEHICLE_EXITED', ev(12, 'VEHICLE_EXITED')));
    await waitFor(() => got.length == 2);
    await Future<void>.delayed(const Duration(milliseconds: 30));
    expect(got.map((e) => e.version), [11, 12]);
  });

  test('akış kopar: reconnecting, artan bekleme, /sync?since ile eksikler sırayla gelir, akış yeni since ile açılır', () async {
    sync = make();
    final c1 = server.openSession();
    server.syncResponses
      ..add(syncBody(10)) // baz
      ..add(syncBody(15, events: [ev(13, 'VEHICLE_ENTERED'), ev(14, 'VEHICLE_EXITED')])) // 1. kopma sonrası
      ..add(syncBody(15)); // 2. deneme
    server.failSession(503); // ilk yeniden bağlanma başarısız
    final c3 = server.openSession();

    final statuses = <LiveStatus>[];
    await sync.start();
    sync.status.addListener(() => statuses.add(sync.status.value));
    await waitFor(() => server.eventReqs.isNotEmpty);
    send(c1, frame('HELLO', {'version': 10}));
    send(c1, frame('VEHICLE_ENTERED', ev(11, 'VEHICLE_ENTERED')));
    await waitFor(() => got.length == 1);
    expect(sync.status.value, LiveStatus.online);

    unawaited(c1.close()); // kopma
    await waitFor(() => server.eventReqs.length == 3);
    send(c3, frame('HELLO', {'version': 15}));
    await waitFor(() => sync.status.value == LiveStatus.online);

    expect(statuses, contains(LiveStatus.reconnecting));
    expect(backoffAttempts, [1, 2]); // artan bekleme
    expect(got.map((e) => e.version), [11, 13, 14]);
    expect(server.syncReqs.map((r) => r.url.queryParameters['since']), [null, '11', '15']);
    expect(server.eventReqs.map((r) => r.url.queryParameters['since']), ['10', '15', '15']);
    expect(sync.version, 15);
  });

  test('hasMore true iken /sync döngüsü son olayın sürümüyle devam eder', () async {
    sync = make();
    final c1 = server.openSession();
    server.syncResponses
      ..add(syncBody(10))
      ..add(syncBody(30, events: [ev(11, 'VEHICLE_ENTERED'), ev(12, 'VEHICLE_ENTERED')], hasMore: true))
      ..add(syncBody(30, events: [ev(13, 'VEHICLE_EXITED')]));
    server.openSession();
    await sync.start();
    await waitFor(() => server.eventReqs.isNotEmpty);
    send(c1, frame('HELLO', {'version': 10}));
    unawaited(c1.close());
    await waitFor(() => server.eventReqs.length == 2);
    expect(got.map((e) => e.version), [11, 12, 13]);
    expect(server.syncReqs.map((r) => r.url.queryParameters['since']), [null, '10', '12']);
    expect(server.eventReqs.last.url.queryParameters['since'], '30');
  });

  test('sessizlik (HEARTBEAT dahil kare yok) bağlantıyı ölü sayıp yeniden bağlanır', () async {
    sync = make(heartbeat: const Duration(milliseconds: 150));
    final c1 = server.openSession();
    server.openSession();
    await sync.start();
    await waitFor(() => server.eventReqs.isNotEmpty);
    send(c1, frame('HELLO', {'version': 10}));
    await waitFor(() => sync.status.value == LiveStatus.online);
    await waitFor(() => server.eventReqs.length == 2);
    expect(server.syncReqs.length, 2); // baz + catch-up
  });

  test('HEARTBEAT karesi bağlantıyı canlı tutar; geride kalınca catch-up çalışır', () async {
    sync = make(heartbeat: const Duration(milliseconds: 200));
    final c1 = server.openSession();
    server.syncResponses
      ..add(syncBody(10))
      ..add(syncBody(12, events: [ev(12, 'VEHICLE_ENTERED')]));
    await sync.start();
    await waitFor(() => server.eventReqs.isNotEmpty);
    send(c1, frame('HELLO', {'version': 10}));
    for (var i = 0; i < 4; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 100));
      send(c1, frame('HEARTBEAT', {'version': i == 1 ? 12 : 10}));
    }
    expect(server.eventReqs.length, 1); // yeniden bağlanmadı
    await waitFor(() => got.length == 1);
    expect(got.single.version, 12);
  });

  test('resetRequired: true reload yayınlar ve sürümü benimser', () async {
    sync = make();
    final c1 = server.openSession();
    server.syncResponses
      ..add(syncBody(10))
      ..add(syncBody(99, reset: true));
    server.openSession();
    await sync.start();
    await waitFor(() => server.eventReqs.isNotEmpty);
    expect(reloads, 1);
    send(c1, frame('HELLO', {'version': 10}));
    unawaited(c1.close());
    await waitFor(() => server.eventReqs.length == 2);
    expect(reloads, 2);
    expect(sync.version, 99);
    expect(server.eventReqs.last.url.queryParameters['since'], '99');
  });

  test('RESET karesi reload yayınlar', () async {
    sync = make();
    final c1 = server.openSession();
    await sync.start();
    await waitFor(() => server.eventReqs.isNotEmpty);
    send(c1, frame('RESET', {'version': 50}));
    await waitFor(() => reloads == 2);
    expect(sync.version, 50);
  });

  test('401 onUnauthorized çağırır ve yeniden denemez', () async {
    sync = make();
    var called = 0;
    sync.onUnauthorized = () => called++;
    server.failSession(401);
    await sync.start();
    await waitFor(() => called == 1);
    await Future<void>.delayed(const Duration(milliseconds: 100));
    expect(called, 1);
    expect(server.eventReqs.length, 1);
    expect(backoffAttempts, isEmpty);
    expect(sync.status.value, LiveStatus.offline);
  });

  test('baz /sync 401 ise de onUnauthorized çağrılır', () async {
    sync = make();
    var called = 0;
    sync.onUnauthorized = () => called++;
    server.syncStatus.add(401);
    await sync.start();
    await waitFor(() => called == 1);
    expect(server.eventReqs, isEmpty);
  });

  test('üst üste başarısızlıkta offline, başarıdan sonra tekrar online', () async {
    sync = make(offlineAfter: 3);
    server.failSession(500);
    server.failSession(500);
    server.failSession(500);
    final c = server.openSession();
    final seen = <LiveStatus>[];
    await sync.start();
    sync.status.addListener(() => seen.add(sync.status.value));
    await waitFor(() => server.eventReqs.length == 4);
    expect(seen, containsAllInOrder([LiveStatus.reconnecting, LiveStatus.offline]));
    send(c, frame('HELLO', {'version': 10}));
    await waitFor(() => sync.status.value == LiveStatus.online);
    // başarıdan sonra sayaç sıfırlanır: yeni kopmada yine reconnecting
    seen.clear();
    server.openSession();
    unawaited(c.close());
    await waitFor(() => seen.contains(LiveStatus.reconnecting));
    expect(seen.contains(LiveStatus.offline), isFalse);
  });

  test('zoneId verilirse başka bölümün ZONE olayları atılır, GLOBAL geçer', () async {
    sync = make();
    final c = server.openSession();
    await sync.start(zoneId: 1);
    await waitFor(() => server.eventReqs.isNotEmpty);
    send(c, frame('VEHICLE_ENTERED', ev(11, 'VEHICLE_ENTERED', zoneId: 2)));
    send(c, frame('VEHICLE_ENTERED', ev(12, 'VEHICLE_ENTERED', zoneId: 1)));
    send(c, frame('TARIFF_UPDATED', ev(13, 'TARIFF_UPDATED', scope: 'GLOBAL', zoneId: null)));
    await waitFor(() => got.length == 2);
    expect(got.map((e) => e.version), [12, 13]);
    expect(sync.version, 13); // atılan olayın sürümü de ilerler
  });

  test('zoneId verilmezse (admin) tüm bölümler gelir', () async {
    sync = make();
    final c = server.openSession();
    await sync.start();
    await waitFor(() => server.eventReqs.isNotEmpty);
    send(c, frame('VEHICLE_ENTERED', ev(11, 'VEHICLE_ENTERED', zoneId: 2)));
    await waitFor(() => got.length == 1);
  });

  test('USER_DEACTIVATED onDeactivated çağırır ve senkronu durdurur', () async {
    sync = make();
    var deact = 0;
    sync.onDeactivated = () => deact++;
    final c = server.openSession();
    await sync.start();
    await waitFor(() => server.eventReqs.isNotEmpty);
    send(c, frame('USER_DEACTIVATED', ev(11, 'USER_DEACTIVATED', scope: 'USER', zoneId: null)));
    await waitFor(() => deact == 1);
    await Future<void>.delayed(const Duration(milliseconds: 80));
    expect(server.eventReqs.length, 1);
    expect(deact, 1);
  });

  test('stop sonrası yeni istek açılmaz', () async {
    sync = make();
    final c = server.openSession();
    await sync.start();
    await waitFor(() => server.eventReqs.isNotEmpty);
    send(c, frame('HELLO', {'version': 10}));
    await sync.stop();
    await Future<void>.delayed(const Duration(milliseconds: 100));
    expect(server.eventReqs.length, 1);
    expect(sync.status.value, LiveStatus.offline);
  });
}
