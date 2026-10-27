import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:bilpark/core/api_client.dart';
import 'package:bilpark/core/constants.dart';
import 'package:bilpark/core/entry_queue.dart';

PendingEntry _e(String plate, {String? id, String? user}) => PendingEntry(
      requestId: id ?? newRequestId(),
      plate: plate,
      street: 'TEVFIK_BEY',
      type: 'SMALL',
      side: 'LEFT',
      username: user,
    );

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    AppConstants.clearAuth();
  });

  test('UUID v4 biçimi ve benzersizlik', () {
    final a = newRequestId();
    final b = newRequestId();
    expect(a, matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')));
    expect(a, isNot(b));
  });

  test('PendingEntry: plaka normalize, toQuery checkInRequestId içerir, json gidiş-dönüş', () {
    final e = PendingEntry(requestId: 'rid', plate: '34 abc-123', street: 'S', type: 'LARGE', side: 'RIGHT', zoneId: 3, username: 'u');
    expect(e.plate, '34ABC123');
    final q = e.toQuery();
    expect(q['checkInRequestId'], 'rid');
    expect(q['zoneId'], '3');
    expect(q['plate'], '34ABC123');
    final back = PendingEntry.fromJson(e.toJson());
    expect(back.toQuery(), q);
    expect(back.username, 'u');
  });

  test('enqueue kalıcıdır; yeni örnek load() ile geri yükler (yeniden başlatma)', () async {
    final q1 = EntryQueue(send: (_) async => null);
    expect(await q1.enqueue(_e('34ABC123', id: 'r1')), isTrue);
    expect(await q1.enqueue(_e('06XYZ99', id: 'r2')), isTrue);

    final q2 = EntryQueue(send: (_) async => null);
    expect(q2.items.value, isEmpty);
    await q2.load();
    expect(q2.items.value.map((e) => e.requestId), ['r1', 'r2']);
    expect(q2.items.value.first.plate, '34ABC123');
  });

  test('aynı plaka iki kez kuyruğa alınamaz', () async {
    final q = EntryQueue(send: (_) async => null);
    expect(await q.enqueue(_e('34ABC123')), isTrue);
    expect(await q.enqueue(_e('34 abc 123')), isFalse);
    expect(q.items.value.length, 1);
  });

  test('flush sırayla gönderir, checkInRequestId taşır ve kuyruğu boşaltır', () async {
    final sent = <Map<String, String>>[];
    final q = EntryQueue(send: (query) async {
      sent.add(query);
      return {'plate': query['plate'], 'accumulatedDebt': 50};
    });
    await q.enqueue(_e('34AAA111', id: 'r1'));
    await q.enqueue(_e('34BBB222', id: 'r2'));
    final results = <EntrySendResult>[];
    q.results.listen(results.add);

    await q.flush();
    await Future<void>.delayed(Duration.zero);

    expect(sent.map((m) => m['checkInRequestId']), ['r1', 'r2']);
    expect(sent.map((m) => m['plate']), ['34AAA111', '34BBB222']);
    expect(q.items.value, isEmpty);
    expect(results.map((r) => r.kind), [EntryResultKind.sent, EntryResultKind.sent]);
    expect((results.first.body as Map)['accumulatedDebt'], 50);
    // kalıcı depo da boşalmış
    final q2 = EntryQueue(send: (_) async => null);
    await q2.load();
    expect(q2.items.value, isEmpty);
  });

  test('ağ hatasında kayıtlar kalır ve gönderim durur', () async {
    var calls = 0;
    final q = EntryQueue(send: (_) async {
      calls++;
      throw const ApiException(null, 'NETWORK', 'yok');
    });
    await q.enqueue(_e('34AAA111'));
    await q.enqueue(_e('34BBB222'));
    await q.flush();
    expect(calls, 1);
    expect(q.items.value.length, 2);
  });

  test('5xx ve 401 gönderimi durdurur, kayıt kalır', () async {
    for (final status in [500, 401]) {
      final q = EntryQueue(send: (_) async => throw ApiException(status, null, 'x'));
      await q.enqueue(_e('34AAA111'));
      await q.flush();
      expect(q.items.value.length, 1, reason: '$status');
    }
  });

  test('409 ALREADY_PARKED: conflict sonucu plaka+mesajla, kayıt kuyruktan çıkar, sonraki gider', () async {
    final sent = <String>[];
    final q = EntryQueue(send: (query) async {
      sent.add(query['plate']!);
      if (query['plate'] == '34AAA111') {
        throw const ApiException(409, 'ALREADY_PARKED', 'Araç zaten başka sokakta park halinde.');
      }
      return {};
    });
    await q.enqueue(_e('34AAA111'));
    await q.enqueue(_e('34BBB222'));
    final results = <EntrySendResult>[];
    q.results.listen(results.add);
    await q.flush();
    await Future<void>.delayed(Duration.zero);

    expect(sent, ['34AAA111', '34BBB222']);
    expect(q.items.value, isEmpty);
    expect(results.first.kind, EntryResultKind.conflict);
    expect(results.first.entry.plate, '34AAA111');
    expect(results.first.message, contains('başka sokakta'));
    expect(results.last.kind, EntryResultKind.sent);
  });

  test('400/403/404/423 rejected sonucu; kayıt çıkar', () async {
    for (final status in [400, 403, 404, 423]) {
      final q = EntryQueue(send: (_) async => throw ApiException(status, null, 'ret'));
      await q.enqueue(_e('34AAA111'));
      final results = <EntrySendResult>[];
      q.results.listen(results.add);
      await q.flush();
      await Future<void>.delayed(Duration.zero);
      expect(q.items.value, isEmpty, reason: '$status');
      expect(results.single.kind, EntryResultKind.rejected);
      expect(results.single.message, 'ret');
    }
  });

  test('409 DUPLICATE_REQUEST sessizce çıkar (sent sayılır, gövde yok)', () async {
    final q = EntryQueue(send: (_) async => throw const ApiException(409, 'DUPLICATE_REQUEST', 'zaten'));
    await q.enqueue(_e('34AAA111'));
    final results = <EntrySendResult>[];
    q.results.listen(results.add);
    await q.flush();
    await Future<void>.delayed(Duration.zero);
    expect(q.items.value, isEmpty);
    expect(results.single.kind, EntryResultKind.sent);
    expect(results.single.body, isNull);
  });

  test('eşzamanlı iki flush her kaydı yalnızca bir kez gönderir', () async {
    final sent = <String>[];
    final q = EntryQueue(send: (query) async {
      sent.add(query['checkInRequestId']!);
      await Future<void>.delayed(const Duration(milliseconds: 20));
      return {};
    });
    await q.enqueue(_e('34AAA111', id: 'r1'));
    await q.enqueue(_e('34BBB222', id: 'r2'));
    await Future.wait([q.flush(), q.flush()]);
    expect(sent, ['r1', 'r2']);
    expect(q.items.value, isEmpty);
  });

  test('yalnızca geçerli kullanıcının kayıtları gönderilir; diğerleri kalır', () async {
    AppConstants.currentUser = 'ali';
    final sent = <String>[];
    final q = EntryQueue(send: (query) async {
      sent.add(query['plate']!);
      return {};
    });
    await q.enqueue(_e('34AAA111', user: 'veli'));
    await q.enqueue(_e('34BBB222', user: 'ali'));
    await q.flush();
    expect(sent, ['34BBB222']);
    expect(q.items.value.map((e) => e.plate), ['34AAA111']);
  });
}
