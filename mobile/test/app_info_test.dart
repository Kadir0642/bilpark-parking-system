import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:bilpark/core/app_info.dart';
import 'package:bilpark/core/live_sync.dart';

void main() {
  tearDown(AppInfo.reset);

  test('başlıklar sürüm ve cihazı içerir, bir kez okunur', () async {
    var reads = 0;
    AppInfo.versionReader = () async { reads++; return '1.0.0+1'; };
    AppInfo.deviceReader = () async => 'SM-M127F / Android 13';
    final h = await AppInfo.headers();
    expect(h['X-App-Version'], '1.0.0+1');
    expect(h['X-Device'], 'SM-M127F / Android 13');
    await AppInfo.headers();
    expect(reads, 1);
  });

  test('cihaz en fazla 80 karakter', () async {
    AppInfo.versionReader = () async => '1.0.0+1';
    AppInfo.deviceReader = () async => 'X' * 200;
    expect((await AppInfo.headers())['X-Device']!.length, 80);
  });

  test('okuma hatasında başlık gönderilmez, çökmez', () async {
    AppInfo.versionReader = () async => throw Exception('yok');
    AppInfo.deviceReader = () async => throw Exception('yok');
    expect(await AppInfo.headers(), isEmpty);
  });

  test('canlı senkron isteğine başlıklar eklenir', () async {
    AppInfo.versionReader = () async => '1.0.0+1';
    AppInfo.deviceReader = () async => 'iPhone14,2 / iOS 17.5';
    http.BaseRequest? seen;
    final client = MockClient.streaming((req, body) async {
      seen ??= req;
      return http.StreamedResponse(const Stream.empty(), 500);
    });
    final sync = LiveSync(
      apiBase: 'http://x/api',
      token: () async => 't',
      clientFactory: () => client,
      backoff: (_) => const Duration(seconds: 30),
    );
    unawaited(sync.start(zoneId: 1));
    for (var i = 0; i < 50 && seen == null; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 20));
    }
    sync.dispose();
    expect(seen, isNotNull);
    expect(seen!.headers['X-App-Version'], '1.0.0+1');
    expect(seen!.headers['X-Device'], 'iPhone14,2 / iOS 17.5');
  });
}
