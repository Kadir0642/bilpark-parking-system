import 'dart:convert';
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:image_picker/image_picker.dart';
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';

final String globalBaseUrl = "https://bilpark-api-rtdl.onrender.com/api/parking";

class ParkingMapScreen extends StatefulWidget {
  final String region;
  final String neighborhood;
  final String street;

  const ParkingMapScreen({
    super.key,
    required this.region,
    required this.neighborhood,
    required this.street
  });

  @override
  State<ParkingMapScreen> createState() => _ParkingMapScreenState();
}

class _ParkingMapScreenState extends State<ParkingMapScreen> with AutomaticKeepAliveClientMixin {
  List<dynamic> spots = [];
  List<Map<String, String>> waitingVehicles = [];
  Map<int, String> activeVehicleTypes = {}; // Anlık gösterim için yerel hafıza
  Timer? timer;

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    fetchFilteredSpots();
    timer = Timer.periodic(const Duration(seconds: 3), (t) => fetchFilteredSpots());
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  Future<void> fetchFilteredSpots() async {
    // Şimdilik filtrelemeyi bypass edip, doğrudan tüm verileri çekmeyi test ediyoruz
    final uri = Uri.parse('$globalBaseUrl/spots');

    try {
      final response = await http.get(uri);
      if (!mounted) return;

      if (response.statusCode == 200) {
        setState(() => spots = json.decode(response.body));
        // BAŞARILI OLURSA EKRANDA YEŞİL MESAJ ÇIKACAK
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text("🎉 Veriler Başarıyla Çekildi: ${spots.length} Park Yeri!"), backgroundColor: Colors.green)
        );
      } else {
        // SUNUCU CEVAP VERİR AMA HATA OLURSA
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text("⚠️ Sunucu Hatası: ${response.statusCode}"), backgroundColor: Colors.orange)
        );
      }
    } catch (e) {
      // UYGULAMA İNTERNETE ÇIKAMAZSA VEYA ENGELLENİRSE
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text("❌ BAĞLANTI HATASI: $e", style: const TextStyle(fontSize: 12)), backgroundColor: Colors.red, duration: const Duration(seconds: 10))
      );
      debugPrint("Kritik Hata: $e");
    }
  }

  Future<void> _showEntryForm(String detectedPlate) async {
    TextEditingController plateController = TextEditingController(text: detectedPlate);
    String selectedType = "SMALL";

    await showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(25.0))),
      builder: (context) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setModalState) {
            return Padding(
              padding: EdgeInsets.only(
                  bottom: MediaQuery.of(context).viewInsets.bottom + 20,
                  left: 20, right: 20, top: 20
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Center(child: Container(width: 50, height: 5, color: Colors.grey[300])),
                  const SizedBox(height: 20),
                  const Text("Araç Girişi 📸", style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: Color(0xFF3F51B5))),
                  const SizedBox(height: 20),

                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(15),
                    decoration: BoxDecoration(color: Colors.grey[100], borderRadius: BorderRadius.circular(15)),
                    child: Column(
                      children: [
                        const Icon(Icons.camera_alt, color: Colors.grey, size: 30),
                        const SizedBox(height: 5),
                        Text("Okunan: $detectedPlate", style: const TextStyle(color: Colors.grey)),
                      ],
                    ),
                  ),
                  const SizedBox(height: 20),

                  const Text("Plaka", style: TextStyle(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 5),
                  TextField(
                    controller: plateController,
                    style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold, letterSpacing: 2),
                    decoration: InputDecoration(
                      prefixIcon: const Icon(Icons.confirmation_number),
                      border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                      filled: true,
                      fillColor: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 15),

                  const Text("Araç Tipi", style: TextStyle(fontWeight: FontWeight.bold)),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    decoration: BoxDecoration(
                      border: Border.all(color: Colors.grey),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<String>(
                        value: selectedType,
                        isExpanded: true,
                        items: const [
                          DropdownMenuItem(value: "SMALL", child: Row(children: [Icon(Icons.directions_car), SizedBox(width: 10), Text("Otomobil (Standart)")])),
                          DropdownMenuItem(value: "LARGE", child: Row(children: [Icon(Icons.local_shipping), SizedBox(width: 10), Text("Kamyonet / Büyük")])),
                        ],
                        onChanged: (val) {
                          setModalState(() => selectedType = val!);
                        },
                      ),
                    ),
                  ),
                  const SizedBox(height: 30),

                  SizedBox(
                    width: double.infinity,
                    height: 55,
                    child: ElevatedButton.icon(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFF3F51B5),
                        foregroundColor: Colors.white,
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(15)),
                      ),
                      onPressed: () {
                        setState(() {
                          waitingVehicles.add({
                            'plate': plateController.text.toUpperCase(),
                            'type': selectedType
                          });
                        });
                        Navigator.pop(context);
                      },
                      icon: const Icon(Icons.save),
                      label: const Text("HAVUZA EKLE", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                    ),
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }

  double calculateLiveFee(String type, DateTime entryTime) {
    Duration diff = DateTime.now().difference(entryTime);
    if (diff.inSeconds <= 300) return 0.0;

    int minutes = diff.inMinutes;
    double baseFee = (type == "LARGE") ? 50.0 : 25.0;
    double extraFee = (type == "LARGE") ? 30.0 : 15.0;

    if (minutes <= 60) {
      return baseFee;
    } else {
      int extraMinutes = minutes - 60;
      int extraHours = (extraMinutes / 60).ceil();
      return baseFee + (extraHours * extraFee);
    }
  }

  Future<void> scanPlate() async {
    final ImagePicker picker = ImagePicker();
    final XFile? photo = await picker.pickImage(source: ImageSource.camera);

    if (photo != null) {
      final inputImage = InputImage.fromFilePath(photo.path);
      final textRecognizer = TextRecognizer(script: TextRecognitionScript.latin);

      try {
        final RecognizedText recognizedText = await textRecognizer.processImage(inputImage);
        RegExp platePattern = RegExp(r'\b\d{2}\s*[A-Z]{1,3}\s*\d{2,4}\b');
        RegExpMatch? match = platePattern.firstMatch(recognizedText.text.toUpperCase());

        String initialPlate = match?.group(0)?.replaceAll(' ', '') ?? "";
        await _showEntryForm(initialPlate);
      } catch (e) { debugPrint(e.toString()); }
      finally { textRecognizer.close(); }
    }
  }

// --- API: PARK ET (GÜVENLİ URL İLE) ---
  Future<void> checkInVehicle(int spotId, String plate, String type) async {
    // 🪄 ÇÖZÜM: Boşlukların URL'yi bozmaması için queryParameters kullanıyoruz!
    final url = Uri.parse('$globalBaseUrl/check-in').replace(queryParameters: {
      'spotId': spotId.toString(),
      'licensePlate': plate,
      'type': type, // Artık LARGE bilgisi backend'e kesin ulaşacak!
    });

    try {
      await http.post(url);
      if (!mounted) return;

      setState(() {
        waitingVehicles.removeWhere((v) => v['plate'] == plate);
        activeVehicleTypes[spotId] = type;
        int index = spots.indexWhere((s) => s['id'] == spotId);
        if(index != -1) {
          spots[index]['occupied'] = true;
          spots[index]['tempPlate'] = plate;
        }
      });
      fetchFilteredSpots();
    } catch (e) { debugPrint(e.toString()); }
  }

  // --- API: ÇIKIŞ YAP (GÜVENLİ URL İLE) ---
  Future<void> checkOutVehicle(int spotId, String? currentPlate) async {
    bool? confirm = await showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text("Çıkış İşlemi ⚠️"),
        content: Text("Plaka: ${currentPlate ?? 'Bilinmiyor'}\n\nBu aracın çıkışını onaylıyor musunuz?"),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text("İptal")),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: Colors.red, foregroundColor: Colors.white),
            onPressed: () => Navigator.pop(context, true),
            child: const Text("Evet, Çıkış Yap"),
          )
        ],
      ),
    );

    if (confirm == true) {
      // 🪄 ÇÖZÜM: Güvenli URL
      final url = Uri.parse('$globalBaseUrl/check-out').replace(queryParameters: {
        'spotId': spotId.toString(),
      });

      try {
        final response = await http.post(url);
        if (response.statusCode == 200) {
          final data = json.decode(response.body);
          setState(() {
            activeVehicleTypes.remove(spotId);
          });
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text("Çıkış Yapıldı. Tutar: ${data['fee']} TL"), backgroundColor: Colors.green));
          fetchFilteredSpots();
        }
      } catch (e) { debugPrint(e.toString()); }
    }
  }

  void showSpotDetails(dynamic spot) {
    String plate = spot['currentPlate'] ?? spot['tempPlate'] ?? "DOLU";

    // 🪄 MADDE 1: Artık Backend'den gelen 'currentType' verisini de okuyor!
    String backendType = spot['currentType'] ?? spot['suitableFor'] ?? "SMALL";
    String type = activeVehicleTypes[spot['id']] ?? backendType.toString().toUpperCase();

    bool isLarge = (type == "LARGE");
    IconData vehicleIcon = isLarge ? Icons.local_shipping : Icons.directions_car;
    Color typeColor = isLarge ? Colors.deepOrange : Colors.redAccent;
    String typeText = isLarge ? "Kamyonet / Ticari Araç" : "Otomobil / Standart";

    DateTime? entryTime;
    if (spot['entryTime'] != null) {
      entryTime = DateTime.parse(spot['entryTime']);
    }

    showDialog(
      context: context,
      builder: (context) {
        return StatefulBuilder(
          builder: (context, setStateDialog) {
            String durationText = "Hesaplanıyor...";
            double currentFee = 0.0;

            if (entryTime != null) {
              Duration diff = DateTime.now().difference(entryTime);
              String twoDigits(int n) => n.toString().padLeft(2, "0");
              durationText = "${twoDigits(diff.inHours)}:${twoDigits(diff.inMinutes.remainder(60))}:${twoDigits(diff.inSeconds.remainder(60))}";
              currentFee = calculateLiveFee(type, entryTime);

              Future.delayed(const Duration(seconds: 1), () {
                if (context.mounted) setStateDialog(() {});
              });
            }

            return AlertDialog(
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
              title: Center(child: Text("Park Yeri: ${spot['spotName']}", style: const TextStyle(fontWeight: FontWeight.bold))),
              content: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(vehicleIcon, size: 60, color: typeColor),
                  const SizedBox(height: 5),
                  Text(typeText, style: TextStyle(color: typeColor, fontWeight: FontWeight.bold, fontSize: 14)),
                  const SizedBox(height: 15),
                  Text(plate, style: const TextStyle(fontSize: 28, fontWeight: FontWeight.bold, letterSpacing: 2)),
                  const SizedBox(height: 20),
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(color: Colors.grey[100], borderRadius: BorderRadius.circular(10)),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text("Süre:", style: TextStyle(color: Colors.grey, fontWeight: FontWeight.bold)),
                        Text(durationText, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.black87)),
                      ],
                    ),
                  ),
                  const SizedBox(height: 10),
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(color: Colors.green[50], borderRadius: BorderRadius.circular(10)),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text("Güncel Tutar:", style: TextStyle(color: Colors.green, fontWeight: FontWeight.bold)),
                        Text("${currentFee.toStringAsFixed(1)} TL", style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Colors.green)),
                      ],
                    ),
                  ),
                ],
              ),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: const Text("Kapat", style: TextStyle(color: Colors.grey))
                ),
                ElevatedButton(
                  style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.redAccent,
                      foregroundColor: Colors.white,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10))
                  ),
                  onPressed: () {
                    Navigator.pop(context);
                    checkOutVehicle(spot['id'], plate);
                  },
                  child: const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                    child: Text("Çıkış Yap"),
                  ),
                )
              ],
            );
          },
        );
      },
    );
  }

  Widget _buildParkingSlot(dynamic spot) {
    bool isOccupied = spot['occupied'] == true;
    String plateInfo = spot['currentPlate'] ?? spot['tempPlate'] ?? "";
    String displayText = (isOccupied && plateInfo.isNotEmpty) ? plateInfo : spot['spotName'];

    // 🪄 MADDE 1: Yine Backend'den gelen 'currentType' verisine bakıyoruz
    String backendType = spot['currentType'] ?? spot['suitableFor'] ?? "SMALL";
    String type = activeVehicleTypes[spot['id']] ?? backendType.toString().toUpperCase();

    bool isLarge = (type == "LARGE");
    IconData spotIcon = isLarge ? Icons.local_shipping : Icons.directions_car;
    Color iconColor = isLarge ? Colors.deepOrange : Colors.red[700]!;

    return DragTarget<Map<String, String>>(
      onWillAcceptWithDetails: (details) => !isOccupied,
      onAcceptWithDetails: (details) {
        checkInVehicle(spot['id'], details.data['plate']!, details.data['type']!);
      },
      builder: (context, candidate, rejected) {
        return Container(
          margin: const EdgeInsets.all(6),
          height: 70,
          decoration: BoxDecoration(
            color: isOccupied ? Colors.red[100] : Colors.green[100],
            border: Border.all(
                color: candidate.isNotEmpty ? Colors.blue : (isOccupied ? Colors.red : Colors.green),
                width: 2
            ),
            borderRadius: BorderRadius.circular(12),
          ),
          child: InkWell(
            onTap: isOccupied ? () => showSpotDetails(spot) : null,
            child: Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                      displayText,
                      style: TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: isOccupied ? 15 : 14,
                          color: isOccupied ? Colors.red[900] : Colors.green[900]
                      )
                  ),
                  if(isOccupied) Icon(spotIcon, size: 20, color: iconColor),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildDraggableVehicle(Map<String, String> vehicle) {
    return Draggable<Map<String, String>>(
      data: vehicle,
      feedback: Material(
          color: Colors.transparent,
          child: _vehicleChip(vehicle)
      ),
      childWhenDragging: Opacity(opacity: 0.3, child: _vehicleChip(vehicle)),
      child: _vehicleChip(vehicle),
    );
  }

  Widget _vehicleChip(Map<String, String> vehicle) {
    bool isLarge = vehicle['type'] == 'LARGE';
    return Container(
      margin: const EdgeInsets.only(right: 12),
      padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 10),
      decoration: BoxDecoration(
        color: isLarge ? Colors.deepOrange : const Color(0xFF3F51B5),
        borderRadius: BorderRadius.circular(12),
        boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 4, offset: Offset(0, 2))],
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(isLarge ? Icons.local_shipping : Icons.directions_car, color: Colors.white, size: 22),
          const SizedBox(width: 8),
          Text(vehicle['plate']!, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 16)),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);

    var aSpots = spots.where((s) => s['spotName'].toString().startsWith('A')).toList();
    var bSpots = spots.where((s) => s['spotName'].toString().startsWith('B')).toList();

    // 🪄 MADDE 2: DİNAMİK SIRALAMA MANTIĞI (Boşlar Üste, Dolular Alta)
    int smartSort(dynamic a, dynamic b) {
      bool aOccupied = a['occupied'] == true;
      bool bOccupied = b['occupied'] == true;

      // Eğer biri boş, biri doluysa BOŞ OLAN ÜSTE çıkar (-1)
      if (aOccupied != bOccupied) {
        return aOccupied ? 1 : -1;
      }

      // İkisi de boşsa veya ikisi de doluysa, isimlerindeki sayıya göre sırala (Örn: A-2, A-10'dan önce gelir)
      int numA = int.tryParse(a['spotName'].toString().replaceAll(RegExp(r'[^0-9]'), '')) ?? 0;
      int numB = int.tryParse(b['spotName'].toString().replaceAll(RegExp(r'[^0-9]'), '')) ?? 0;
      return numA.compareTo(numB);
    }

    // Listeleri sıralıyoruz
    aSpots.sort(smartSort);
    bSpots.sort(smartSort);

    return Scaffold(
      appBar: AppBar(
          title: Text("${widget.street} Operasyonu", style: const TextStyle(color: Colors.white)),
          backgroundColor: const Color(0xFF3F51B5),
          actions: [IconButton(icon: const Icon(Icons.refresh, color: Colors.white), onPressed: fetchFilteredSpots)]
      ),
      body: Column(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 5),
            decoration: const BoxDecoration(
                color: Colors.white,
                boxShadow: [BoxShadow(color: Colors.black12, blurRadius: 4, offset: Offset(0, 2))]
            ),
            child: Row(
              children: [
                Expanded(child: Text("⬅️ Öncü Döner", textAlign: TextAlign.center, style: TextStyle(fontWeight: FontWeight.bold, color: Colors.indigo[700], fontSize: 13))),
                Expanded(child: Text(widget.street, textAlign: TextAlign.center, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
                Expanded(child: Text("Ziraat Bankası ➡️", textAlign: TextAlign.center, style: TextStyle(fontWeight: FontWeight.bold, color: Colors.indigo[700], fontSize: 13))),
              ],
            ),
          ),

          Expanded(
            child: Row(
              children: [
                Expanded(
                  child: ListView.builder(
                    padding: const EdgeInsets.all(5),
                    itemCount: aSpots.length,
                    itemBuilder: (c, i) => _buildParkingSlot(aSpots[i]),
                  ),
                ),
                Container(
                  width: 60, color: const Color(0xFF2C3E50),
                  child: Column(children: List.generate(15, (i) => Expanded(child: Container(margin: const EdgeInsets.symmetric(vertical: 10), width: 6, color: Colors.white)))),
                ),
                Expanded(
                  child: ListView.builder(
                    padding: const EdgeInsets.all(5),
                    itemCount: bSpots.length,
                    itemBuilder: (c, i) => _buildParkingSlot(bSpots[i]),
                  ),
                ),
              ],
            ),
          ),

          Container(
            height: 110,
            padding: const EdgeInsets.all(15),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: const BorderRadius.vertical(top: Radius.circular(25)),
              boxShadow: [BoxShadow(color: Colors.black.withOpacity(0.15), blurRadius: 15, offset: const Offset(0, -5))],
            ),
            child: Row(
              children: [
                Expanded(
                  child: waitingVehicles.isEmpty
                      ? const Center(child: Text("Bekleyen Araç Yok", style: TextStyle(color: Colors.grey, fontStyle: FontStyle.italic)))
                      : ListView.builder(
                      scrollDirection: Axis.horizontal,
                      itemCount: waitingVehicles.length,
                      itemBuilder: (c, i) => _buildDraggableVehicle(waitingVehicles[i])
                  ),
                ),
                const SizedBox(width: 10),
                FloatingActionButton(
                  onPressed: scanPlate,
                  backgroundColor: const Color(0xFF3F51B5),
                  elevation: 2,
                  child: const Icon(Icons.camera_alt, color: Colors.white, size: 28),
                )
              ],
            ),
          )
        ],
      ),
    );
  }
}