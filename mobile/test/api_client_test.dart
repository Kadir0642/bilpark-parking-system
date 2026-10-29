import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:bilpark/core/api_client.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('ApiClient.parseError', () {
    test('sunucu gövdesindeki error ve code okunur', () {
      final e = ApiClient.parseError(409, '{"error":"34ABC123 az önce kapatıldı","code":"ALREADY_PROCESSED"}');
      expect(e.statusCode, 409);
      expect(e.code, 'ALREADY_PROCESSED');
      expect(e.message, '34ABC123 az önce kapatıldı');
    });

    test('gövde JSON değilse duruma göre genel mesaj', () {
      final e = ApiClient.parseError(500, '<html>boom</html>');
      expect(e.code, isNull);
      expect(e.message, ApiClient.defaultMessage(500));
    });

    test('error alanı yoksa genel mesaj', () {
      final e = ApiClient.parseError(404, '{"foo":"bar"}');
      expect(e.message, 'Kayıt bulunamadı.');
    });
  });

  group('device id', () {
    test('128 bit hex üretir', () {
      final id = ApiClient.generateDeviceId();
      expect(id, matches(RegExp(r'^[0-9a-f]{32}$')));
      expect(ApiClient.generateDeviceId(), isNot(id));
    });

    test('bir kez üretilir ve kalıcıdır', () async {
      SharedPreferences.setMockInitialValues({});
      ApiClient.resetCache();
      final first = await ApiClient.getDeviceId();
      ApiClient.resetCache(); // uygulama yeniden başlatma
      expect(await ApiClient.getDeviceId(), first);
    });
  });
}
