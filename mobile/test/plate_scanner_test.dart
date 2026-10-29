import 'package:bilpark/screens/plate_scanner_screen.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';

void main() {
  test('ConsecutiveReads: aynı plaka 2 kez gelince onaylar', () {
    final r = ConsecutiveReads();
    expect(r.add('34ABC123'), isNull);
    expect(r.add('34ABC123'), '34ABC123');
  });
  test('ConsecutiveReads: farklı okuma veya boş kare sayacı sıfırlar', () {
    final r = ConsecutiveReads();
    expect(r.add('34ABC123'), isNull);
    expect(r.add('34ABC124'), isNull);
    expect(r.add(null), isNull);
    expect(r.add('34ABC124'), isNull);
    expect(r.add('34ABC124'), '34ABC124');
  });
  test('mlkitRotation', () {
    expect(mlkitRotation(sensorOrientation: 90, isAndroid: true, frontCamera: false), InputImageRotation.rotation90deg);
    expect(mlkitRotation(sensorOrientation: 270, isAndroid: true, frontCamera: true), InputImageRotation.rotation270deg);
    expect(mlkitRotation(sensorOrientation: 90, isAndroid: true, frontCamera: false, deviceDegrees: 90), InputImageRotation.rotation0deg);
    expect(mlkitRotation(sensorOrientation: 90, isAndroid: false, frontCamera: false), InputImageRotation.rotation90deg);
  });
}
