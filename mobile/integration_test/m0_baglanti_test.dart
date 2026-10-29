// Altyapı denemesi: telefon backend'e ve host yardımcısına ulaşıyor mu, uygulama açılıyor mu.
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

import 'support/e2e.dart';

void main() {
  ensureE2eBinding();
  testWidgets('Bağlantı denemesi', (t) async {
    final health = await http.get(Uri.parse('${E2e.apiBase}/health'));
    expect(health.statusCode, 200, reason: 'Backend ${E2e.apiBase} ulaşılamıyor');
    final admin = await Api.admin();
    expect(admin.token, isNotEmpty);
    await startApp(t);
    await Host.screenshot('M0_giris_ekrani');
  });
}
