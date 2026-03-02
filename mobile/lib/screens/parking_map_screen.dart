import 'dart:convert';
import 'dart:async';
import 'dart:math'; // Satır sayısı hesabı için eklendi
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:image_picker/image_picker.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';

final String globalBaseUrl = "https://bilpark-api-rtdl.onrender.com/api/parking";

class ParkingMapScreen extends StatefulWidget {
  final String region;
  final String neighborhood;
  final String street;

  const ParkingMapScreen({super.key, required this.region, required this.neighborhood, required this.street});

  @override
  State<ParkingMapScreen> createState() => _ParkingMapScreenState();
}

class _ParkingMapScreenState extends State<ParkingMapScreen> with AutomaticKeepAliveClientMixin {
  List<dynamic> activeVehicles = [];
  Timer? timer;

  String get _backendStreetEnum {
    if (widget.street.contains("Tevfik")) return "TEVFIK_BEY";
    if (widget.street.contains("Ali Rıza")) return "ALI_RIZA_OZKAY";
    if (widget.street.contains("Cumhuriyet")) return "CUMHURIYET";
    return "TEVFIK_BEY";
  }

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    fetchActiveVehicles();
    timer = Timer.periodic(const Duration(seconds: 3), (t) => fetchActiveVehicles());
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  // --- API İŞLEMLERİ ---
  Future<void> fetchActiveVehicles() async {
    final uri = Uri.parse('$globalBaseUrl/filter').replace(queryParameters: {'street': _backendStreetEnum});
    try {
      final response = await http.get(uri);
      if (!mounted) return;
      if (response.statusCode == 200) setState(() => activeVehicles = json.decode(response.body));
    } catch (e) { debugPrint("Hata: $e"); }
  }

  // YENİ: CHECK-IN ARTIK "SIDE" (YÖN) İSTİYOR!
  Future<void> checkInVehicle(String plate, String type, String side) async {
    final url = Uri.parse('$globalBaseUrl/check-in').replace(queryParameters: {
      'plate': plate.toUpperCase().replaceAll(' ', ''),
      'street': _backendStreetEnum,
      'type': type,
      'side': side, // 'LEFT' veya 'RIGHT' olarak Backend'e gidiyor!
    });
    try {
      final response = await http.post(url);
      if (response.statusCode == 200 || response.statusCode == 201) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text("✅ Araç Sokağa Eklendi!"), backgroundColor: Colors.green));
        fetchActiveVehicles();
      } else {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("Hata: Kayıt Başarısız"), backgroundColor: Colors.red));
      }
    } catch (e) { debugPrint(e.toString()); }
  }

  Future<void> checkOutVehicle(String plate) async {
    final url = Uri.parse('$globalBaseUrl/check-out').replace(queryParameters: {'plate': plate});
    try {
      final response = await http.post(url);
      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("💸 Ödeme Alındı: ${data['fee']} TL"), backgroundColor: Colors.green));
        fetchActiveVehicles();
      }
    } catch (e) { debugPrint(e.toString()); }
  }

  Future<void> markAsRunaway(String plate) async {
    final url = Uri.parse('$globalBaseUrl/runaway').replace(queryParameters: {'plate': plate});
    try {
      final response = await http.post(url);
      if (response.statusCode == 200) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text("🚨 Araç Kaçak Olarak İşaretlendi!"), backgroundColor: Colors.red));
        fetchActiveVehicles();
      }
    } catch (e) { debugPrint(e.toString()); }
  }

  double calculateLiveFee(String type, DateTime entryTime) {
    Duration diff = DateTime.now().difference(entryTime);
    if (diff.inSeconds <= 300) return 0.0;
    int minutes = diff.inMinutes;
    double baseFee = (type == "LARGE") ? 50.0 : 25.0;
    double extraFee = (type == "LARGE") ? 30.0 : 15.0;
    if (minutes <= 60) return baseFee;
    return baseFee + (((minutes - 60) / 60).ceil() * extraFee);
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
                  TextField(controller: plateController, style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold, letterSpacing: 2), decoration: InputDecoration(prefixIcon: const Icon(Icons.confirmation_number), border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)))),
                  const SizedBox(height: 15),
                  DropdownButtonFormField<String>(
                    value: selectedType, decoration: InputDecoration(border: OutlineInputBorder(borderRadius: BorderRadius.circular(12))),
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

    showDialog(
      context: context,
      builder: (BuildContext dialogContext) {
        Timer? dialogTimer; // Saniyeleri sayacak İsviçre saatimiz!

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
                  Text(plate, style: const TextStyle(fontSize: 32, fontWeight: FontWeight.bold, letterSpacing: 2)),
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
              actions: [
                // YENİ BİLPARK 2.0 BUTONLARI
                ElevatedButton.icon(
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.black, foregroundColor: Colors.white, padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 12)),
                  onPressed: () {
                    dialogTimer?.cancel(); // Kart kapanırken saati durdur (Hafıza dostu)
                    Navigator.pop(dialogContext);
                    markAsRunaway(plate);
                  },
                  icon: const Icon(Icons.warning, color: Colors.redAccent),
                  label: const Text("Kaçtı"),
                ),
                ElevatedButton.icon(
                  style: ElevatedButton.styleFrom(backgroundColor: Colors.green, foregroundColor: Colors.white, padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 12)),
                  onPressed: () {
                    dialogTimer?.cancel(); // Kart kapanırken saati durdur
                    Navigator.pop(dialogContext);
                    checkOutVehicle(plate);
                  },
                  icon: const Icon(Icons.payment),
                  label: const Text("Ödeme Al"),
                )
              ],
            );
          },
        );
      },
    ).then((_) {
      // Kullanıcı dışarı tıklayıp kartı kapatırsa diye güvenliği elden bırakmıyoruz
      // (Burası tetiklenir ve sayaç arka planda çalışıp telefonu ısıtmaz)
    });
  }

  Future<void> scanPlate() async {
    final ImagePicker picker = ImagePicker();
    final XFile? photo = await picker.pickImage(source: ImageSource.camera);
    if (photo != null) {
      final textRecognizer = TextRecognizer(script: TextRecognitionScript.latin);
      try {
        final RecognizedText recognizedText = await textRecognizer.processImage(InputImage.fromFilePath(photo.path));
        RegExp platePattern = RegExp(r'\b\d{2}\s*[A-Z]{1,3}\s*\d{2,4}\b');
        RegExpMatch? match = platePattern.firstMatch(recognizedText.text.toUpperCase());
        await _showEntryForm(match?.group(0)?.replaceAll(' ', '') ?? "");
      } catch (e) { debugPrint(e.toString()); } finally { textRecognizer.close(); }
    }
  }

  Widget _buildCarCard(dynamic vehicle) {
    if (vehicle == null) return const SizedBox.shrink();
    bool isLarge = vehicle['currentType'] == 'LARGE';
    return InkWell(
      onTap: () => showSpotDetails(vehicle),
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 5, vertical: 8),
        padding: const EdgeInsets.symmetric(vertical: 12),
        decoration: BoxDecoration(
          color: isLarge ? Colors.orange[50] : Colors.blue[50],
          border: Border.all(color: isLarge ? Colors.orange : Colors.blueAccent, width: 2),
          borderRadius: BorderRadius.circular(12),
          boxShadow: const [BoxShadow(color: Colors.black12, blurRadius: 4, offset: Offset(0, 2))],
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(isLarge ? Icons.local_shipping : Icons.directions_car, size: 28, color: isLarge ? Colors.orange : Colors.blueAccent),
            const SizedBox(height: 5),
            Text(vehicle['currentPlate'], style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16, color: Colors.grey[800])),
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
      appBar: AppBar(title: Text(widget.street, style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold)), backgroundColor: const Color(0xFF3F51B5)),
      body: activeVehicles.isEmpty
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
                _buildRoadDivider(),                         // Asfalt
                Expanded(child: _buildCarCard(rightVehicle)),// Sağ Kaldırım (Geldikçe dolar)
              ],
            ),
          );
        },
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