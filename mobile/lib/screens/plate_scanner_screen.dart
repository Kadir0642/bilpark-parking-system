import 'dart:async';
import 'dart:io';
import 'dart:math' show Point;
import 'package:camera/camera.dart';
import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';
import 'package:image_picker/image_picker.dart';
import '../core/foreign_ocr.dart';
import '../core/plate.dart';
import '../core/theme.dart';
import '../widgets/bp_snack.dart';
import '../widgets/status_badge.dart';

/// Üst üste aynı plaka [needed] kez okunursa onaylar (tek seferlik yanlış okumayı eler).
class ConsecutiveReads {
  final int needed;
  String? _last;
  int _count = 0;
  ConsecutiveReads({this.needed = 2});

  /// Okunan plakayı (okunamadıysa null) ekler; onaylanan plakayı ya da null döner.
  String? add(String? plate) {
    if (plate == null) {
      _last = null;
      _count = 0;
      return null;
    }
    if (plate == _last) {
      _count++;
    } else {
      _last = plate;
      _count = 1;
    }
    return _count >= needed ? plate : null;
  }
}

/// Kamera sensör yönü ve cihaz dönüşüne (0/90/180/270) göre ML Kit dönüşü.
/// Uygulama dikey kilitli olduğundan çağıran deviceDegrees = 0 verir.
InputImageRotation? mlkitRotation({
  required int sensorOrientation,
  required bool isAndroid,
  required bool frontCamera,
  int deviceDegrees = 0,
}) {
  if (!isAndroid) return InputImageRotationValue.fromRawValue(sensorOrientation);
  final comp = frontCamera ? (sensorOrientation + deviceDegrees) % 360 : (sensorOrientation - deviceDegrees + 360) % 360;
  return InputImageRotationValue.fromRawValue(comp);
}

/// Tarama sonucu: [plate] '' ise "Elle yaz". [foreign]: yabancı / özel plaka modunda okundu
/// (TR düzeltmesi yapılmadı; giriş formunda anahtar açık gelir). [countryCode]: yabancı modda
/// plakanın solunda okunan ülke kodu (D, NL ...); giriş formunda "Ülke kodu" alanına ön doldurulur.
class PlateScan {
  final String plate;
  final bool foreign;
  final String? countryCode;
  const PlateScan(this.plate, {this.foreign = false, this.countryCode});
}

/// OCR metninden plaka: TR modunda TR biçimine göre düzeltilmiş aday, yabancı / özel modda ham temiz metin.
String? plateFromOcr(String text, {required bool foreign}) => foreign ? extractRawPlate(text) : extractPlate(text);

/// Kaç aynı okuma onaylar: yabancı / özel modda düzeltme kuralı olmadığından 3 (son 6 karede,
/// bkz. [ForeignVotes]), TR'de üst üste 2.
int readsNeeded({required bool foreign}) => foreign ? 3 : 2;

/// Yabancı modda onay sayacına giden değer: yalnızca harf+rakam karışık adaylar sayılır,
/// düz yazı (tabela, bayi çerçevesi) kendiliğinden onaylanmaz; görevli isterse elle yazar.
String? foreignConfirmKey(String? plate) => plate != null && isMixedPlate(plate) ? plate : null;

/// Yabancı modda bu süre içinde onay çıkmazsa gösterilen ipucu.
const foreignHintDelay = Duration(seconds: 3);
const foreignHintText = 'Plakayı çerçevenin ortasına alın ve biraz yaklaşın';

/// ML Kit sonucunu saf [OcrBox] listesine çevirir (kelime düzeyi: blok > satır > kelime). Karakter
/// kutuları da taşınır: ızgarada yan yana iki plakanın tek kelimeye birleştiği yer bunlardan bulunur.
List<OcrBox> ocrBoxesFrom(RecognizedText recognized) => [
  for (final block in recognized.blocks)
    for (final line in block.lines)
      for (final e in line.elements)
        OcrBox(
          e.text,
          left: e.boundingBox.left,
          top: e.boundingBox.top,
          right: e.boundingBox.right,
          bottom: e.boundingBox.bottom,
          charHeight: cornerHeight(e.cornerPoints),
          symbols: [
            for (final c in e.symbols)
              OcrBox(
                c.text,
                left: c.boundingBox.left,
                top: c.boundingBox.top,
                right: c.boundingBox.right,
                bottom: c.boundingBox.bottom,
              ),
          ],
        ),
];

/// Yabancı modda çerçevenin altındaki küçük tanı satırı: kameranın o an gördüğü en iyi aday, ülke kodu
/// ve onay ilerlemesi. Görevli "ne okuyor" sorusuna ekrandan cevap verebilsin diye; sunucuya gitmez.
String foreignDebugLine(ForeignPlateRead? read, {required int votes, required int needed}) {
  if (read == null) return 'aday yok';
  final cc = read.countryCode == null ? '' : ' · ${read.countryCode}';
  final progress = read.mixed ? '$votes/$needed' : 'harf+rakam yok, onaylanmaz';
  return 'aday: ${read.plate}$cc · $progress';
}

/// Döndürülmüş köşe noktalarından (sol-üstten saat yönünde) yazı yüksekliği; 4 nokta yoksa null.
double? cornerHeight(List<Point<int>> p) {
  if (p.length != 4) return null;
  final h = (p[0].distanceTo(p[3]) + p[1].distanceTo(p[2])) / 2;
  return h > 0 ? h : null;
}

/// ML Kit kutuları dik (döndürülmüş) görüntü koordinatındadır: 90/270 derecede en ve boy yer değiştirir.
Size uprightFrameSize(Size image, InputImageRotation rotation) =>
    rotation == InputImageRotation.rotation90deg || rotation == InputImageRotation.rotation270deg
    ? Size(image.height, image.width)
    : image;

/// Yabancı modda ML Kit sonucundan kutu konumlu okuma (plaka + ülke kodu).
ForeignPlateRead? foreignReadFromRecognized(RecognizedText recognized, {Size? frame}) =>
    readForeignPlate(ocrBoxesFrom(recognized), frameWidth: frame?.width, frameHeight: frame?.height);

/// ML Kit sonucundan plaka: TR modunda eski metin yolu (değişmedi), yabancı modda kutu konumlu okuma.
String? plateFromRecognized(RecognizedText recognized, {required bool foreign, Size? frame}) =>
    foreign ? foreignReadFromRecognized(recognized, frame: frame)?.plate : extractPlate(recognized.text);

/// Canlı kamerada plaka okur. Aynı plaka art arda 2 karede (yabancı modda 3) okununca kendiliğinden kapanıp
/// plakayı döner. PlateScan('') = "Elle yaz", null = kapat. Kamera açılamazsa fotoğraf çekme akışına düşer.
class PlateScannerScreen extends StatefulWidget {
  const PlateScannerScreen({super.key});

  @override
  State<PlateScannerScreen> createState() => _PlateScannerScreenState();
}

class _PlateScannerScreenState extends State<PlateScannerScreen> {
  static const _throttle = Duration(milliseconds: 400);

  CameraController? _cam;
  TextRecognizer? _recognizer;
  var _reads = ConsecutiveReads(needed: readsNeeded(foreign: false)); // TR modu (değişmedi)
  var _votes = ForeignVotes(needed: readsNeeded(foreign: true)); // yabancı mod: son karelerde çoğunluk
  ForeignPlateRead? _foreignRead; // yabancı modda son karenin en iyi adayı (tanı satırı)
  int _foreignVotesNow = 0;
  bool _busy = false;
  bool _done = false;
  bool _torch = false;
  bool _fallbackStarted = false;
  DateTime _lastRun = DateTime.fromMillisecondsSinceEpoch(0);
  String? _candidate;
  bool _foreign = false; // yabancı / özel plaka: ham metin, görevli formda onaylar
  String? _error; // kamera / OCR hatası: sessiz yutulmaz, ekranda görünür
  Timer? _hintTimer; // yabancı modda onay gecikirse ipucu
  bool _showHint = false;
  Size? _frame; // son karenin dik tutuş boyutu (merkeze yakınlık için)
  String? _countryCode; // yabancı modda son karede okunan ülke kodu

  @override
  void initState() {
    super.initState();
    // Döndürme hesabı dikey tutuşa göre: tarama sırasında ekran dikeye kilitlenir
    SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
    _recognizer = TextRecognizer(script: TextRecognitionScript.latin);
    _initCamera();
  }

  Future<void> _initCamera() async {
    try {
      final cams = await availableCameras();
      final back = cams.firstWhere((c) => c.lensDirection == CameraLensDirection.back, orElse: () => cams.first);
      final ctrl = CameraController(
        back,
        ResolutionPreset.high,
        enableAudio: false,
        imageFormatGroup: Platform.isAndroid ? ImageFormatGroup.nv21 : ImageFormatGroup.bgra8888,
      );
      _cam = ctrl;
      await ctrl.initialize();
      if (!mounted) return;
      await ctrl.setFlashMode(FlashMode.off);
      await ctrl.startImageStream(_onFrame);
      if (mounted) setState(() {});
    } catch (e) {
      debugPrint('Kamera açılamadı: $e');
      _showError(e);
      _fallbackToPhoto();
    }
  }

  // Eski akış: fotoğraf çek, tanı, plakayı döndür
  Future<void> _fallbackToPhoto() async {
    if (_fallbackStarted || _done) return;
    _fallbackStarted = true;
    var result = '';
    try {
      final photo = await ImagePicker().pickImage(source: ImageSource.camera);
      if (photo == null) {
        _finish(null);
        return;
      }
      final text = await _recognizer!.processImage(InputImage.fromFilePath(photo.path));
      if (_foreign) {
        final r = foreignReadFromRecognized(text);
        _countryCode = r?.countryCode;
        result = r?.plate ?? '';
      } else {
        result = extractPlate(text.text) ?? '';
      }
    } catch (e) {
      debugPrint(e.toString());
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          bpSnackBar(context, 'Fotoğraf okunamadı: ${_shortReason(e)}', tone: BadgeTone.danger),
        );
      }
    }
    _finish(result);
  }

  /// Hatanın kısa, sahada okunabilir özeti (ilk satır, en çok 90 karakter).
  static String _shortReason(Object e) {
    final line = e.toString().split('\n').first.trim();
    return line.length > 90 ? '${line.substring(0, 90)}...' : line;
  }

  void _showError(Object e) {
    final msg = 'Kamera/OCR başlatılamadı: ${_shortReason(e)}';
    if (mounted && msg != _error) setState(() => _error = msg);
  }

  InputImage? _toInputImage(CameraImage image) {
    final cam = _cam;
    if (cam == null || image.planes.isEmpty) return null;
    final rotation = mlkitRotation(
      sensorOrientation: cam.description.sensorOrientation,
      isAndroid: Platform.isAndroid,
      frontCamera: cam.description.lensDirection == CameraLensDirection.front,
    );
    final format = InputImageFormatValue.fromRawValue(image.format.raw);
    if (rotation == null || format == null) return null;
    if (Platform.isAndroid && format != InputImageFormat.nv21) return null;
    if (Platform.isIOS && format != InputImageFormat.bgra8888) return null;
    final plane = image.planes.first;
    _frame = uprightFrameSize(Size(image.width.toDouble(), image.height.toDouble()), rotation);
    return InputImage.fromBytes(
      bytes: plane.bytes,
      metadata: InputImageMetadata(
        size: Size(image.width.toDouble(), image.height.toDouble()),
        rotation: rotation,
        format: format,
        bytesPerRow: plane.bytesPerRow,
      ),
    );
  }

  Future<void> _onFrame(CameraImage image) async {
    final now = DateTime.now();
    if (_busy || _done || now.difference(_lastRun) < _throttle) return;
    _busy = true;
    _lastRun = now;
    try {
      final input = _toInputImage(image);
      if (input == null) return;
      final text = await _recognizer!.processImage(input);
      String? plate;
      String? confirmed;
      if (_foreign) {
        final r = foreignReadFromRecognized(text, frame: _frame);
        plate = r?.plate;
        _countryCode = r?.countryCode;
        confirmed = _votes.add(foreignConfirmKey(plate));
        final votes = _votes.votesFor(foreignConfirmKey(plate));
        if (mounted && (r?.plate != _foreignRead?.plate || r?.countryCode != _foreignRead?.countryCode || votes != _foreignVotesNow)) {
          setState(() {
            _foreignRead = r;
            _foreignVotesNow = votes;
          });
        }
      } else {
        plate = extractPlate(text.text);
        confirmed = _reads.add(plate);
      }
      // Yabancı modda yeni bir harf+rakam adayı kare biriktirmeye başlayınca ipucu gizlenir, süre yeniden başlar
      final newForeignCandidate = _foreign && plate != _candidate && foreignConfirmKey(plate) != null;
      if (newForeignCandidate) _restartHintTimer();
      if (mounted && (plate != _candidate || _error != null || newForeignCandidate)) {
        setState(() {
          _candidate = plate;
          _error = null; // okuma çalışıyor
          if (newForeignCandidate) _showHint = false;
        });
      }
      if (confirmed != null) _finish(confirmed);
    } catch (e) {
      debugPrint(e.toString());
      _showError(e);
    } finally {
      _busy = false;
    }
  }

  void _finish(String? result) {
    if (_done) return;
    _done = true;
    _hintTimer?.cancel();
    final cam = _cam;
    if (cam != null && cam.value.isStreamingImages) {
      cam.stopImageStream().catchError((_) {});
    }
    if (mounted) {
      Navigator.pop(
        context,
        result == null ? null : PlateScan(result, foreign: _foreign, countryCode: _foreign && result.isNotEmpty ? _countryCode : null),
      );
    }
  }

  void _setForeign(bool on) {
    _hintTimer?.cancel();
    setState(() {
      _foreign = on;
      _candidate = null;
      _showHint = false;
      _countryCode = null;
      _foreignRead = null;
      _foreignVotesNow = 0;
    });
    // önceki moddaki okuma sayılmaz
    _reads = ConsecutiveReads(needed: readsNeeded(foreign: false));
    _votes = ForeignVotes(needed: readsNeeded(foreign: true));
    if (on) _restartHintTimer();
  }

  /// Yabancı modda [foreignHintDelay] içinde onay çıkmazsa ipucunu gösterir.
  void _restartHintTimer() {
    _hintTimer?.cancel();
    _hintTimer = Timer(foreignHintDelay, () {
      if (mounted && _foreign && !_done) setState(() => _showHint = true);
    });
  }

  Future<void> _toggleTorch() async {
    final cam = _cam;
    if (cam == null || !cam.value.isInitialized) return;
    try {
      await cam.setFlashMode(_torch ? FlashMode.off : FlashMode.torch);
      if (mounted) setState(() => _torch = !_torch);
    } catch (e) {
      debugPrint(e.toString());
    }
  }

  @override
  void dispose() {
    _done = true;
    _hintTimer?.cancel();
    _cam?.dispose();
    _recognizer?.close();
    SystemChrome.setPreferredOrientations(const []);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final cam = _cam;
    final ready = cam != null && cam.value.isInitialized;
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (ready)
            ClipRect(
              child: FittedBox(
                fit: BoxFit.cover,
                child: SizedBox(
                  width: cam.value.previewSize!.height,
                  height: cam.value.previewSize!.width,
                  child: CameraPreview(cam),
                ),
              ),
            )
          else
            const Center(child: CircularProgressIndicator()),
          // Plaka biçiminde kılavuz çerçeve
          Center(
            child: FractionallySizedBox(
              widthFactor: 0.9,
              child: AspectRatio(
                aspectRatio: 4.5,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    border: Border.all(color: _candidate == null ? Colors.white : c.success, width: 3),
                    borderRadius: BorderRadius.circular(BpSize.rMd),
                  ),
                ),
              ),
            ),
          ),
          // Yabancı modda çerçevenin hemen altında küçük tanı satırı (kamera ne görüyor)
          if (_foreign)
            Align(
              alignment: Alignment.center,
              child: Padding(
                // Align dolgu + metni birlikte ortalar: üst dolgu = çerçeve boyu (en x 0.9 / 4.5) + 40 olunca
                // metin çerçevenin alt kenarının ~12 px altına düşer
                padding: EdgeInsets.only(top: MediaQuery.sizeOf(context).width * 0.9 / 4.5 + 40),
                child: Text(
                  foreignDebugLine(_foreignRead, votes: _foreignVotesNow, needed: readsNeeded(foreign: true)),
                  key: const Key('scanForeignDebug'),
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: Colors.white70,
                    fontSize: 12,
                    shadows: [Shadow(blurRadius: 3, color: Colors.black)],
                  ),
                ),
              ),
            ),
          SafeArea(
            child: Column(
              children: [
                Padding(
                  padding: const EdgeInsets.all(8),
                  child: Row(
                    children: [
                      IconButton(
                        tooltip: 'Kapat',
                        color: Colors.white,
                        iconSize: 30,
                        onPressed: () => _finish(null),
                        icon: const Icon(Icons.close),
                      ),
                      const Spacer(),
                      IconButton(
                        tooltip: 'Fener',
                        color: _torch ? Colors.amber : Colors.white,
                        iconSize: 30,
                        onPressed: ready ? _toggleTorch : null,
                        icon: Icon(_torch ? Icons.flash_on : Icons.flash_off),
                      ),
                    ],
                  ),
                ),
                const Spacer(),
                Container(
                  margin: const EdgeInsets.symmetric(horizontal: 20),
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                  decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(BpSize.rMd)),
                  child: Text(
                    _error ?? _candidate ?? 'Plakayı çerçeveye getirin',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: _error != null ? Colors.redAccent : (_candidate == null ? Colors.white70 : Colors.white),
                      fontSize: _error != null ? 14 : (_candidate == null ? 16 : 28),
                      fontWeight: FontWeight.w800,
                      letterSpacing: (_candidate == null || _error != null) ? 0 : 2,
                    ),
                  ),
                ),
                if (_showHint && _foreign && _error == null)
                  Container(
                    key: const Key('scanForeignHint'),
                    margin: const EdgeInsets.fromLTRB(20, 8, 20, 0),
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(BpSize.rMd)),
                    child: const Text(
                      foreignHintText,
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Colors.amber, fontSize: 14, fontWeight: FontWeight.w700),
                    ),
                  ),
                // Onay gelmiyorsa (ör. yalnız rakamlı Suriye plakası, mühür harfi oynuyor) görülen adayı tek
                // dokunuşla forma taşı; görevli formda düzeltir. Aday yoksa "Elle yaz" yabancı modda açılır.
                if (_showHint && _foreign && _error == null && _candidate != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: TextButton.icon(
                      key: const Key('scanUseCandidate'),
                      style: TextButton.styleFrom(foregroundColor: Colors.white, backgroundColor: Colors.black54),
                      onPressed: () => _finish(_candidate),
                      icon: const Icon(Icons.check),
                      label: Text('Bunu kullan: $_candidate'),
                    ),
                  ),
                const SizedBox(height: 12),
                FilterChip(
                  key: const Key('scanForeignChip'),
                  label: const Text('Yabancı / özel plaka'),
                  selected: _foreign,
                  onSelected: _setForeign,
                ),
                const SizedBox(height: 8),
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
                  child: SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: () => _finish(''),
                      icon: const Icon(Icons.keyboard),
                      // Yabancı modda form yabancı plaka anahtarı açık gelir (Arapça / Farsça rakamlı plaka için hızlı yol)
                      label: Text(_foreign ? 'Elle yaz (yabancı)' : 'Elle yaz'),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
