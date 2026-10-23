import 'dart:async';
import 'dart:math';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';
import 'package:qr_flutter/qr_flutter.dart';
import '../core/api_client.dart';
import '../core/constants.dart';

enum _ActionKind {
  checkOut('Ödeme'),
  runaway('Kaçak');

  final String label;
  const _ActionKind(this.label);
}

class _PendingAction {
  final String plate;
  final _ActionKind kind;
  final String? method; // CASH | POS | SUBSCRIPTION (yalnızca ödeme)
  final double amount;
  Timer? timer;
  bool sent = false; // true: istek yola çıktı, artık geri alınamaz

  _PendingAction(this.plate, this.kind, this.method, this.amount);
}

class _FailedAction {
  final String plate;
  final _ActionKind kind;
  final String? method;
  final double amount;
  final DateTime time;
  final String message;
  final String? code;
  final int? statusCode;

  _FailedAction(this.plate, this.kind, this.method, this.amount, this.time, this.message, this.code, this.statusCode);

  // Ağ/sunucu hatası ve kilit çakışması tekrar denenebilir; "zaten işlendi", "abonelik yok" gibi iş kuralı hataları denenemez
  bool get retryable => statusCode == null || statusCode! >= 500 || code == 'CONFLICT';
}

class ParkingMapScreen extends StatefulWidget {
  final String region;
  final String neighborhood;
  final String street;
  final int? zoneId;
  final String? zoneName;

  const ParkingMapScreen({super.key, required this.region, required this.neighborhood, required this.street, this.zoneId, this.zoneName});

  @override
  State<ParkingMapScreen> createState() => _ParkingMapScreenState();
}

class _ParkingMapScreenState extends State<ParkingMapScreen> with AutomaticKeepAliveClientMixin {
  List<dynamic> activeVehicles = [];
  Timer? timer;
  String? _listError;
  bool _failedExpanded = true;
  final Map<String, _PendingAction> _pending = {};
  final List<_FailedAction> _failed = [];

  String get _backendStreetEnum => AppConstants.toBackendEnum(widget.street);

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    fetchActiveVehicles();
    timer = Timer.periodic(const Duration(seconds: 6), (t) => fetchActiveVehicles());
  }

  @override
  void dispose() {
    timer?.cancel();
    // Ekran kapanırken bekleyen işlemler SESSİZCE DÜŞÜRÜLMEZ, hemen gönderilir: görevli onay vermişti ve
    // tahsilat/kaçak kaydı kaybolursa kasa ile saha uyuşmaz. (UI yok; sonuç sunucu kaydında görünür.)
    for (final p in _pending.values.toList()) {
      if (!p.sent) {
        p.timer?.cancel();
        _execute(p);
      }
    }
    super.dispose();
  }

  // --- API İŞLEMLERİ ---
  Future<void> fetchActiveVehicles() async {
    try {
      final data = await ApiClient.get('/parking/filter', query: {'street': _backendStreetEnum});
      if (!mounted) return;
      var list = data is List ? data : <dynamic>[];
      if (widget.zoneId != null) {
        list = list.where((spot) => spot['zone'] != null && spot['zone']['id'] == widget.zoneId).toList();
      }
      setState(() {
        activeVehicles = list;
        _listError = null;
      });
    } on ApiException catch (e) {
      // Polling her 6 sn'de çalışır: hatayı SnackBar yağmuru yerine ekranda tek satır olarak göster
      if (mounted && !e.isUnauthorized) setState(() => _listError = e.message);
    }
  }

  void _showSnack(String msg, {Color? color, Duration duration = const Duration(seconds: 4), SnackBarAction? action}) {
    if (!mounted) return;
    final m = ScaffoldMessenger.of(context);
    m.hideCurrentSnackBar();
    m.showSnackBar(SnackBar(content: Text(msg), backgroundColor: color, duration: duration, action: action));
  }

  // YENİ: CHECK-IN ARTIK "SIDE" (YÖN) İSTİYOR!
  Future<void> checkInVehicle(String plate, String type, String side) async {
    final Map<String, String> params = {
      'plate': plate.toUpperCase().replaceAll(RegExp(r'[\s-]'), ''),
      'street': _backendStreetEnum,
      'type': type,
      'side': side, // 'LEFT' veya 'RIGHT' olarak Backend'e gidiyor!
    };
    if (widget.zoneId != null) {
      params['zoneId'] = widget.zoneId.toString();
    }
    try {
      await ApiClient.post('/parking/check-in', query: params);
      _showSnack("✅ ${params['plate']} sokağa eklendi!", color: Colors.green);
      fetchActiveVehicles();
    } on ApiException catch (e) {
      // 409 ALREADY_PARKED / 400 INVALID_PLATE: sunucunun mesajı olduğu gibi gösterilir
      if (e.isUnauthorized) return;
      _showSnack(e.message, color: Colors.red, duration: const Duration(seconds: 6));
      if (e.code == 'ALREADY_PARKED') fetchActiveVehicles();
    }
  }

  // --- GERİ ALINABİLİR İŞLEMLER (Ödeme Al / Kaçtı) ---
  static const Duration _undoWindow = Duration(seconds: 5);

  String _money(num v) => "₺${v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(2)}";

  String _methodLabel(String? m) {
    switch (m) {
      case 'CASH':
        return 'Nakit';
      case 'POS':
        return 'POS';
      case 'SUBSCRIPTION':
        return 'Abonelik';
      default:
        return m ?? '-';
    }
  }

  String _hhmm(DateTime t) => "${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}";

  /// Aracın aktif aboneliği var mı? true/false; belirlenemezse null.
  Future<bool?> _hasSubscription(dynamic vehicle) async {
    final known = vehicle['hasSubscription'];
    if (known is bool) return known;
    try {
      final r = await ApiClient.get('/subscription/check', query: {'plate': vehicle['currentPlate'].toString()});
      return r is Map ? r['hasSubscription'] == true : null;
    } on ApiException {
      return null;
    }
  }

  Future<void> _requestCheckout(dynamic vehicle) async {
    final String plate = vehicle['currentPlate'];
    if (_pending.containsKey(plate)) return;
    final type = vehicle['currentType'] ?? 'SMALL';
    final entry = DateTime.parse(vehicle['entryTime']).toLocal();
    final fee = calculateLiveFee(type, entry);
    final hasSub = await _hasSubscription(vehicle);
    if (!mounted) return;
    final method = await _askPaymentMethod(plate, fee, hasSub);
    if (method == null || !mounted) return;
    _schedule(plate, _ActionKind.checkOut, method, method == 'SUBSCRIPTION' ? 0 : fee);
  }

  Future<String?> _askPaymentMethod(String plate, double fee, bool? hasSub) {
    String selected = hasSub == true ? 'SUBSCRIPTION' : 'CASH';
    return showDialog<String>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setD) {
          final amount = selected == 'SUBSCRIPTION' ? 0.0 : fee;
          Widget option(String value, String label, IconData icon, {bool enabled = true, String? note}) {
            return RadioListTile<String>(
              value: value,
              enabled: enabled,
              title: Text(label, style: const TextStyle(fontWeight: FontWeight.bold)),
              subtitle: note != null ? Text(note) : null,
              secondary: Icon(icon),
              dense: true,
            );
          }

          return AlertDialog(
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
            title: Text("$plate - Ödeme Yöntemi", style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            content: RadioGroup<String>(
              groupValue: selected,
              onChanged: (v) => setD(() => selected = v!),
              child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                option('CASH', 'Nakit', Icons.payments),
                option('POS', 'POS', Icons.credit_card),
                option('SUBSCRIPTION', 'Abonelik', Icons.card_membership,
                    enabled: hasSub != false, note: hasSub == false ? 'Aktif abonelik yok' : (hasSub == true ? 'Aktif abonelik var' : null)),
                const SizedBox(height: 10),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(color: Colors.green[50], borderRadius: BorderRadius.circular(10), border: Border.all(color: Colors.green[200]!)),
                  child: Text("$plate için ${_money(amount)} ${_methodLabel(selected)} ile tahsil edilecek.",
                      style: const TextStyle(fontWeight: FontWeight.bold, color: Colors.black87)),
                ),
              ],
            )),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx), child: const Text("İptal")),
              ElevatedButton(
                style: ElevatedButton.styleFrom(backgroundColor: Colors.green, foregroundColor: Colors.white),
                onPressed: () => Navigator.pop(ctx, selected),
                child: const Text("Onayla"),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _requestRunaway(dynamic vehicle) async {
    final String plate = vehicle['currentPlate'];
    if (_pending.containsKey(plate)) return;
    final type = vehicle['currentType'] ?? 'SMALL';
    final entry = DateTime.parse(vehicle['entryTime']).toLocal();
    final debt = calculateLiveFee(type, entry);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        title: const Text("Kaçak İşaretle"),
        content: Text("$plate kaçak olarak işaretlensin mi? Borç: ${_money(debt)}"),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text("Vazgeç")),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red, foregroundColor: Colors.white),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text("Kaçak İşaretle"),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    _schedule(plate, _ActionKind.runaway, null, debt);
  }

  /// İşlem 5 sn BEKLETİLİR; ancak süre dolunca sunucuya gider. "Geri Al" zamanlayıcıyı iptal eder, hiçbir şey gönderilmez.
  void _schedule(String plate, _ActionKind kind, String? method, double amount) {
    if (_pending.containsKey(plate)) return; // aynı işlem iki kez tetiklenemez
    final p = _PendingAction(plate, kind, method, amount);
    p.timer = Timer(_undoWindow, () => _execute(p));
    setState(() => _pending[plate] = p);
    _showSnack("$plate: ${kind.label} — İşlem 5 sn içinde gönderilecek",
        duration: _undoWindow, action: SnackBarAction(label: "Geri Al", onPressed: () => _undo(plate)));
  }

  void _undo(String plate) {
    final p = _pending[plate];
    // İstek yola çıktıysa artık geri alınamaz
    if (p == null || p.sent) return;
    p.timer?.cancel();
    if (!mounted) return;
    setState(() => _pending.remove(plate));
    _showSnack("$plate: ${p.kind.label} iptal edildi.");
  }

  Future<void> _execute(_PendingAction p) async {
    p.sent = true;
    try {
      if (p.kind == _ActionKind.checkOut) {
        final data = await ApiClient.post('/parking/check-out', query: {
          'plate': p.plate,
          if (p.method != null) 'paymentMethod': p.method!,
        });
        final fee = (data is Map && data['fee'] is num) ? data['fee'] as num : p.amount;
        final method = (data is Map ? data['paymentMethod'] as String? : null) ?? p.method;
        _showSnack("${p.plate} çıkış yapıldı — ${_money(fee)} (${_methodLabel(method)})", color: Colors.green);
      } else {
        await ApiClient.post('/parking/runaway', query: {'plate': p.plate});
        _showSnack("🚨 ${p.plate} kaçak olarak işaretlendi — borç ${_money(p.amount)}", color: Colors.red);
      }
      if (mounted) fetchActiveVehicles();
    } on ApiException catch (e) {
      if (!mounted) return;
      if (!e.isUnauthorized) {
        setState(() => _failed.insert(0, _FailedAction(p.plate, p.kind, p.method, p.amount, DateTime.now(), e.message, e.code, e.statusCode)));
        _showSnack("${p.plate}: ${e.message}", color: Colors.red, duration: const Duration(seconds: 7));
      }
      // Araç başka bir işlemle kapandıysa liste eskimiştir
      if (e.code == 'ALREADY_PROCESSED' || e.code == 'VEHICLE_NOT_FOUND') fetchActiveVehicles();
    } finally {
      if (mounted) setState(() => _pending.remove(p.plate));
    }
  }

  void _retry(_FailedAction f) {
    final p = _PendingAction(f.plate, f.kind, f.method, f.amount);
    setState(() {
      _failed.remove(f);
      _pending[f.plate] = p;
    });
    _execute(p); // kullanıcı işlemi zaten onaylamıştı: tekrar denemede bekleme yok
  }

  double calculateLiveFee(String type, DateTime entryTime) {
    Duration diff = DateTime.now().difference(entryTime);
    int minutes = diff.inMinutes;
    // Sunucu dakika <= 5 iken 0 alır (5:59'a kadar ücretsiz)
    if (minutes <= AppConstants.gracePeriodSeconds ~/ 60) return 0.0;
    double baseFee = (type == "LARGE") ? AppConstants.largeBaseFee : AppConstants.smallBaseFee;
    double extraFee = (type == "LARGE") ? AppConstants.largeExtraFee : AppConstants.smallExtraFee;
    if (minutes <= 60) return baseFee;
    return baseFee + (((minutes - 60) / 60).ceil() * extraFee);
  }

  bool _isForeignPlate(String plate) {
    // 2 rakam, ardından MA-MZ veya MAA-MZZ arası harfler, ardından 3-4 rakam.
    return RegExp(r'^\d{2}M[A-Z]{1,2}\d{3,4}$').hasMatch(plate.toUpperCase().replaceAll(' ', ''));
  }

  String _getFlagEmoji(String plate) {
    if (plate.isEmpty) return '🇹🇷'; // Default
    return _isForeignPlate(plate) ? '🇪🇺' : '🇹🇷';
  }

  // --- YENİ UX MODALI (İki Yönlü Buton) ---
  Future<void> _showEntryForm(String detectedPlate) async {
    TextEditingController plateController = TextEditingController(text: detectedPlate);
    String selectedType = "SMALL";

    await showModalBottomSheet(
      context: context, isScrollControlled: true, backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(25.0))),
      builder: (context) {
        return StatefulBuilder(
          builder: (context, setModalState) {
            return Padding(
              padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom + 20, left: 20, right: 20, top: 20),
              child: Column(
                mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text("Yönlü Araç Girişi 📸", style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: Color(0xFF3F51B5))),
                  const SizedBox(height: 20),
                  TextField(
                    controller: plateController, 
                    style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold, letterSpacing: 2), 
                    decoration: InputDecoration(
                      prefixIcon: const Icon(Icons.confirmation_number), 
                      suffixIcon: Padding(
                         padding: const EdgeInsets.all(12.0),
                         child: Text(_getFlagEmoji(plateController.text), style: const TextStyle(fontSize: 24)),
                      ),
                      border: OutlineInputBorder(borderRadius: BorderRadius.circular(12))
                    ),
                    onChanged: (val) => setModalState((){}),
                  ),
                  const SizedBox(height: 15),
                  DropdownButtonFormField<String>(
                    initialValue: selectedType, decoration: InputDecoration(border: OutlineInputBorder(borderRadius: BorderRadius.circular(12))),
                    items: const [
                      DropdownMenuItem(value: "SMALL", child: Text("Otomobil 🚗")),
                      DropdownMenuItem(value: "LARGE", child: Text("Kamyonet / Ticari 🚐")),
                    ],
                    onChanged: (val) => setModalState(() => selectedType = val!),
                  ),
                  const SizedBox(height: 30),

                  // 🚀 YENİ İKİLİ BUTON YAPISI
                  Row(
                    children: [
                      Expanded(
                        child: ElevatedButton.icon(
                          style: ElevatedButton.styleFrom(backgroundColor: Colors.blue[700], foregroundColor: Colors.white, padding: const EdgeInsets.symmetric(vertical: 15)),
                          onPressed: () { Navigator.pop(context); checkInVehicle(plateController.text, selectedType, 'LEFT'); },
                          icon: const Icon(Icons.arrow_back), label: const Text("SOLA PARK"),
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: ElevatedButton.icon(
                          style: ElevatedButton.styleFrom(backgroundColor: Colors.green[700], foregroundColor: Colors.white, padding: const EdgeInsets.symmetric(vertical: 15)),
                          onPressed: () { Navigator.pop(context); checkInVehicle(plateController.text, selectedType, 'RIGHT'); },
                          icon: const Icon(Icons.arrow_forward), label: const Text("SAĞA PARK"),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }

  // --- YENİ UX: CANLI SAYAÇLI (TİMER) VE ESKİ TASARIMLI KART ---
  void showSpotDetails(dynamic vehicle) {
    String plate = vehicle['currentPlate'];
    String type = vehicle['currentType'] ?? 'SMALL';
    DateTime entryTime = DateTime.parse(vehicle['entryTime']).toLocal();
    bool isLarge = (type == 'LARGE');
    Timer? dialogTimer; // Dialog kapanınca her durumda iptal edilir

    showDialog(
      context: context,
      builder: (BuildContext dialogContext) {
        return StatefulBuilder(
          builder: (context, setStateDialog) {

            // Eğer sayaç çalışmıyorsa başlat (Her saniye ekranı yeniler)
            dialogTimer ??= Timer.periodic(const Duration(seconds: 1), (timer) {
              if (context.mounted) {
                setStateDialog(() {}); // Sadece bu küçük kartı yeniler, uygulamayı yormaz!
              }
            });

            // Süre Hesaplama (Saniyeler dahil!)
            Duration diff = DateTime.now().difference(entryTime);
            String hours = diff.inHours.toString().padLeft(2, "0");
            String minutes = diff.inMinutes.remainder(60).toString().padLeft(2, "0");
            String seconds = diff.inSeconds.remainder(60).toString().padLeft(2, "0");
            String durationText = "$hours:$minutes:$seconds"; // Örn: 02:14:45

            double currentFee = calculateLiveFee(type, entryTime);

            return AlertDialog(
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
              title: const Center(child: Text("Araç Detayları", style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16, color: Colors.grey))),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // ESKİ TASARIMIN İKONİK YAPISI
                  Icon(isLarge ? Icons.local_shipping : Icons.directions_car, size: 65, color: isLarge ? Colors.deepOrange : Colors.redAccent),
                  const SizedBox(height: 5),
                  Text(isLarge ? "Kamyonet / Ticari Araç" : "Otomobil / Standart", style: TextStyle(color: isLarge ? Colors.deepOrange : Colors.redAccent, fontWeight: FontWeight.bold, fontSize: 13)),
                  const SizedBox(height: 15),

                  // PLAKA
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(plate, style: const TextStyle(fontSize: 32, fontWeight: FontWeight.bold, letterSpacing: 2)),
                      const SizedBox(width: 10),
                      Text(_getFlagEmoji(plate), style: const TextStyle(fontSize: 32)),
                    ],
                  ),
                  const SizedBox(height: 25),

                  // CANLI SÜRE KUTUSU (Gri)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 12),
                    decoration: BoxDecoration(color: Colors.grey[100], borderRadius: BorderRadius.circular(10), border: Border.all(color: Colors.grey[300]!)),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text("İçerideki Süre:", style: TextStyle(color: Colors.grey, fontWeight: FontWeight.bold)),
                        Text(durationText, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Colors.black87)),
                      ],
                    ),
                  ),
                  const SizedBox(height: 10),

                  // FİYAT KUTUSU (Yeşil)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 12),
                    decoration: BoxDecoration(color: Colors.green[50], borderRadius: BorderRadius.circular(10), border: Border.all(color: Colors.green[200]!)),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text("Güncel Tutar:", style: TextStyle(color: Colors.green, fontWeight: FontWeight.bold)),
                        Text("${currentFee.toStringAsFixed(1)} TL", style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: Colors.green)),
                      ],
                    ),
                  ),
                ],
              ),
              actionsAlignment: MainAxisAlignment.center,
              // --- YENİ PROFESYONEL BUTON DİZİLİMİ ---
              actionsPadding: const EdgeInsets.symmetric(horizontal: 15, vertical: 15),
              actions: [
                Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    // ÜST SATIR: QR ve Kaçtı Butonları (Yan yana eşit)
                    Row(
                      children: [
                        Expanded(
                          child: ElevatedButton.icon(
                            style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.blueAccent,
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(vertical: 12),
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                            ),
                            onPressed: () {
                              // QR ÜRETME KODUN BURADA
                              String paymentUrl = "https://kadir0642.github.io/bilpark-parking-system/web/customer-payment.html?plate=$plate";
                              showDialog(
                                  context: context,
                                  builder: (ctx) => AlertDialog(
                                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
                                    title: Text("$plate Hızlı Ödeme", textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.bold)),
                                    content: SizedBox(
                                      width: 250, height: 250,
                                      child: QrImageView(data: paymentUrl, version: QrVersions.auto, size: 250.0),
                                    ),
                                    actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text("Kapat"))],
                                  )
                              );
                            },
                            icon: const Icon(Icons.qr_code),
                            label: const Text("QR"),
                          ),
                        ),
                        const SizedBox(width: 10), // İki buton arası boşluk
                        Expanded(
                          child: ElevatedButton.icon(
                            style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.black,
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(vertical: 12),
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                            ),
                            onPressed: () {
                              dialogTimer?.cancel();
                              Navigator.pop(dialogContext);
                              _requestRunaway(vehicle);
                            },
                            icon: const Icon(Icons.warning, color: Colors.redAccent),
                            label: const Text("Kaçtı"),
                          ),
                        ),
                      ],
                    ),

                    const SizedBox(height: 10), // Üst satır ile alt satır arası boşluk

                    // ALT SATIR: Ödeme Al Butonu (Boydan boya dev buton)
                    SizedBox(
                      width: double.infinity,
                      child: ElevatedButton.icon(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.green,
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 14),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                        ),
                        onPressed: () {
                          dialogTimer?.cancel();
                          Navigator.pop(dialogContext);
                          _requestCheckout(vehicle);
                        },
                        icon: const Icon(Icons.payment, size: 24),
                        label: const Text("Ödeme Al", style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                      ),
                    ),
                  ],
                )
              ],
            );
          },
        );
      },
    ).then((_) {
      // Kullanıcı dışarı tıklayıp kartı kapatırsa diye güvenliği elden bırakmıyoruz
      // (Burası tetiklenir ve sayaç arka planda çalışıp telefonu ısıtmaz)
      dialogTimer?.cancel();
    });
  }

  Future<void> scanPlate() async {
    final ImagePicker picker = ImagePicker();
    final XFile? photo = await picker.pickImage(source: ImageSource.camera);
    if (photo != null) {
      final textRecognizer = TextRecognizer(script: TextRecognitionScript.latin);
      try {
        final RecognizedText recognizedText = await textRecognizer.processImage(InputImage.fromFilePath(photo.path));
        
        // 1. Gelen metni büyüt, yeni satırları (\n) ve gereksiz işaretleri boşluğa çevir
        // Böylece "34\nABC\n123" (Kare Plaka) -> "34 ABC 123" (Standart) haline gelir.
        String cleanText = recognizedText.text.toUpperCase().replaceAll(RegExp(r'[\n\r\-\.\,]'), ' ');

        // 2. Güçlü Regex: (2 Rakam) + (1-3 Harf) + (2-4 Rakam)
        RegExp platePattern = RegExp(r'\b(\d{2})\s*([A-Z]{1,3})\s*(\d{2,4})\b');
        RegExpMatch? match = platePattern.firstMatch(cleanText);
        
        String foundPlate = "";
        if (match != null) {
           // Eşleşen grupları boşluksuz birleştir (Örn: "34", "ABC", "123" -> "34ABC123")
           foundPlate = "${match.group(1)}${match.group(2)}${match.group(3)}";
        }
        
        await _showEntryForm(foundPlate);
      } catch (e) { 
        debugPrint(e.toString()); 
      } finally { 
        textRecognizer.close(); 
      }
    }
  }

  Widget _buildCarCard(dynamic vehicle) {
    if (vehicle == null) return const SizedBox.shrink();
    bool isLarge = vehicle['currentType'] == 'LARGE';
    String? landmarkNames = (vehicle['zone'] != null && vehicle['zone']['landmarkNames'] != null) ? vehicle['zone']['landmarkNames'] : null;
    final bool isPending = _pending.containsKey(vehicle['currentPlate']);
    return InkWell(
      onTap: isPending ? null : () => showSpotDetails(vehicle),
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 5, vertical: 8),
        padding: const EdgeInsets.symmetric(vertical: 12),
        decoration: BoxDecoration(
          color: isPending ? Colors.grey[300] : (isLarge ? Colors.orange[50] : Colors.blue[50]),
          border: Border.all(color: isPending ? Colors.grey : (isLarge ? Colors.orange : Colors.blueAccent), width: 2),
          borderRadius: BorderRadius.circular(12),
          boxShadow: const [BoxShadow(color: Colors.black12, blurRadius: 4, offset: Offset(0, 2))],
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(isLarge ? Icons.local_shipping : Icons.directions_car, size: 28, color: isLarge ? Colors.orange : Colors.blueAccent),
            const SizedBox(height: 5),
            Text(vehicle['currentPlate'], style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16, color: Colors.grey[800])),
            if (isPending)
              const Padding(
                padding: EdgeInsets.only(top: 4.0),
                child: Text("Bekliyor…", style: TextStyle(fontSize: 11, color: Colors.black54, fontWeight: FontWeight.bold)),
              ),
            if (landmarkNames != null && landmarkNames.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4.0),
                child: Text(landmarkNames, style: const TextStyle(fontSize: 10, color: Colors.grey, fontStyle: FontStyle.italic), textAlign: TextAlign.center),
              )
          ],
        ),
      ),
    );
  }

  // Gönderilmeyi bekleyen işlemler (5 sn içinde Geri Al yapılabilir)
  Widget _buildPendingPanel() {
    final waiting = _pending.values.where((p) => !p.sent).toList();
    if (waiting.isEmpty) return const SizedBox.shrink();
    return Container(
      width: double.infinity,
      color: Colors.amber[100],
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Column(
        children: waiting
            .map((p) => Row(
                  children: [
                    Expanded(
                      child: Text("${p.plate}: ${p.kind.label} — 5 sn içinde gönderilecek",
                          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                    ),
                    TextButton(onPressed: () => _undo(p.plate), child: const Text("Geri Al")),
                  ],
                ))
            .toList(),
      ),
    );
  }

  // Sunucunun reddettiği / ulaşamadığımız işlemler
  Widget _buildFailedPanel() {
    if (_failed.isEmpty) return const SizedBox.shrink();
    return Container(
      width: double.infinity,
      color: Colors.red[50],
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          InkWell(
            onTap: () => setState(() => _failedExpanded = !_failedExpanded),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              child: Row(
                children: [
                  const Icon(Icons.error_outline, color: Colors.red, size: 18),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text("Başarısız işlemler (${_failed.length})",
                        style: const TextStyle(color: Colors.red, fontWeight: FontWeight.bold)),
                  ),
                  Icon(_failedExpanded ? Icons.expand_less : Icons.expand_more, color: Colors.red),
                ],
              ),
            ),
          ),
          if (_failedExpanded)
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 220),
              child: ListView(
                shrinkWrap: true,
                padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
                children: _failed.map(_buildFailedRow).toList(),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildFailedRow(_FailedAction f) {
    return Card(
      margin: const EdgeInsets.only(bottom: 6),
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text("${f.plate} • ${f.kind.label}${f.method != null ? ' (${_methodLabel(f.method)})' : ''} • ${_hhmm(f.time)}",
                style: const TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 2),
            Text(f.message, style: const TextStyle(color: Colors.red, fontSize: 13)),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                if (f.retryable && !_pending.containsKey(f.plate))
                  TextButton(onPressed: () => _retry(f), child: const Text("Tekrar dene")),
                TextButton(onPressed: () => setState(() => _failed.remove(f)), child: const Text("Kapat")),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildRoadDivider() {
    return Container(
      width: 50, color: const Color(0xFF2C3E50),
      child: Center(
        child: Container(
          width: 4, height: 40, color: Colors.white,
          margin: const EdgeInsets.symmetric(vertical: 15),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);

    // 🧠 MİMARİNİN KALBİ: Araçları "Yön"lerine göre iki farklı listeye ayırıyoruz
    var leftVehicles = activeVehicles.where((v) => v['side'] == 'LEFT' || v['side'] == null).toList();
    var rightVehicles = activeVehicles.where((v) => v['side'] == 'RIGHT').toList();

    // Hangi taraf daha uzunsa, otoparkın uzunluğu o kadar olacak! (Sınırsız Büyüme)
    int rowCount = max(leftVehicles.length, rightVehicles.length);

    return Scaffold(
      backgroundColor: Colors.grey[200],
      appBar: AppBar(title: Text('${widget.street}${widget.zoneName != null ? ' - ${widget.zoneName!}' : ''}', style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)), backgroundColor: const Color(0xFF3F51B5)),
      body: Column(
        children: [
          if (_listError != null)
            Container(
              width: double.infinity,
              color: Colors.red[50],
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              child: Text("Liste güncellenemedi: $_listError", style: const TextStyle(color: Colors.red, fontSize: 12)),
            ),
          _buildPendingPanel(),
          _buildFailedPanel(),
          Expanded(
            child: activeVehicles.isEmpty
                ? const Center(child: Text("Sokak boş. Araç eklemek için +'ya basın.", style: TextStyle(color: Colors.grey, fontSize: 16)))
                : ListView.builder(
                    itemCount: rowCount,
                    itemBuilder: (context, index) {
                      // Her satırda soldan ve sağdan sıradaki aracı alıyoruz
                      var leftVehicle = index < leftVehicles.length ? leftVehicles[index] : null;
                      var rightVehicle = index < rightVehicles.length ? rightVehicles[index] : null;

                      return IntrinsicHeight(
                        child: Row(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Expanded(child: _buildCarCard(leftVehicle)), // Sol Kaldırım (Geldikçe dolar)
                            _buildRoadDivider(), // Asfalt
                            Expanded(child: _buildCarCard(rightVehicle)), // Sağ Kaldırım (Geldikçe dolar)
                          ],
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
      floatingActionButton: Column(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          FloatingActionButton(heroTag: "btn_camera", backgroundColor: Colors.orange, onPressed: scanPlate, child: const Icon(Icons.camera_alt, color: Colors.white)),
          const SizedBox(height: 10),
          FloatingActionButton(heroTag: "btn_add", backgroundColor: const Color(0xFF3F51B5), onPressed: () => _showEntryForm(""), child: const Icon(Icons.add, color: Colors.white)),
        ],
      ),
    );
  }
}