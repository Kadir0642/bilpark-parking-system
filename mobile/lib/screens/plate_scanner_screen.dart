import 'dart:io';
import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';
import 'package:image_picker/image_picker.dart';
import '../core/plate.dart';
import '../core/theme.dart';

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

/// Canlı kamerada plaka okur. Aynı plaka art arda 2 karede okununca kendiliğinden kapanıp
/// plakayı döner. '' = "Elle yaz", null = kapat. Kamera açılamazsa fotoğraf çekme akışına düşer.
class PlateScannerScreen extends StatefulWidget {
  const PlateScannerScreen({super.key});

  @override
  State<PlateScannerScreen> createState() => _PlateScannerScreenState();
}

class _PlateScannerScreenState extends State<PlateScannerScreen> {
  static const _throttle = Duration(milliseconds: 400);

  CameraController? _cam;
  TextRecognizer? _recognizer;
  final _reads = ConsecutiveReads();
  bool _busy = false;
  bool _done = false;
  bool _torch = false;
  bool _fallbackStarted = false;
  DateTime _lastRun = DateTime.fromMillisecondsSinceEpoch(0);
  String? _candidate;

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
      result = extractPlate(text.text) ?? '';
    } catch (e) {
      debugPrint(e.toString());
    }
    _finish(result);
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
      final plate = extractPlate(text.text);
      final confirmed = _reads.add(plate);
      if (mounted && plate != _candidate) setState(() => _candidate = plate);
      if (confirmed != null) _finish(confirmed);
    } catch (e) {
      debugPrint(e.toString());
    } finally {
      _busy = false;
    }
  }

  void _finish(String? result) {
    if (_done) return;
    _done = true;
    final cam = _cam;
    if (cam != null && cam.value.isStreamingImages) {
      cam.stopImageStream().catchError((_) {});
    }
    if (mounted) Navigator.pop(context, result);
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
                    _candidate ?? 'Plakayı çerçeveye getirin',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: _candidate == null ? Colors.white70 : Colors.white,
                      fontSize: _candidate == null ? 16 : 28,
                      fontWeight: FontWeight.w800,
                      letterSpacing: _candidate == null ? 0 : 2,
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
                  child: SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: () => _finish(''),
                      icon: const Icon(Icons.keyboard),
                      label: const Text('Elle yaz'),
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
