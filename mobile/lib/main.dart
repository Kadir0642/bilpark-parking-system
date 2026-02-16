import 'dart:convert';
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

void main() {
  runApp(const BilParkApp());
}

class BilParkApp extends StatelessWidget {
  const BilParkApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'BilPark',
      theme: ThemeData(
        primarySwatch: Colors.indigo,
        useMaterial3: true,
      ),
      home: const ParkingScreen(),
    );
  }
}

class ParkingScreen extends StatefulWidget {
  const ParkingScreen({super.key});

  @override
  State<ParkingScreen> createState() => _ParkingScreenState();
}

class _ParkingScreenState extends State<ParkingScreen> {
  // --- AYARLAR ---
  // BURAYA KENDİ BİLGİSAYARININ IP ADRESİNİ YAZ (Örn: 192.168.1.35)
  final String baseUrl = "http://192.168.1.102:8080/api/parking";
  // ----------------

  List<dynamic> spots = [];
  bool isLoading = true;
  Timer? timer;

  @override
  void initState() {
    super.initState();
    fetchParkingSpots();
    // Her 2 saniyede bir verileri yenile
    timer = Timer.periodic(const Duration(seconds: 2), (Timer t) => fetchParkingSpots());
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  // 1. PARK YERLERİNİ GETİR (GET)
  Future<void> fetchParkingSpots() async {
    try {
      final response = await http.get(Uri.parse('$baseUrl/spots'));
      if (response.statusCode == 200) {
        setState(() {
          spots = json.decode(response.body);
          isLoading = false;
        });
      }
    } catch (e) {
      print("Bağlantı Hatası: $e");
    }
  }

  // 2. ARAÇ GİRİŞİ YAP (POST)
  Future<void> checkInVehicle(int spotId, String licensePlate) async {
    final url = Uri.parse('$baseUrl/check-in?spotId=$spotId&licensePlate=$licensePlate');

    try {
      final response = await http.post(url);
      if (response.statusCode == 200) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text("Giriş Başarılı: $licensePlate"), backgroundColor: Colors.green),
        );
        fetchParkingSpots(); // Listeyi hemen güncelle
      } else {
        showError("Giriş Hatası: ${response.body}");
      }
    } catch (e) {
      showError("Bağlantı Hatası: $e");
    }
  }

  // 3. ARAÇ ÇIKIŞI YAP (POST)
  Future<void> checkOutVehicle(int spotId) async {
    final url = Uri.parse('$baseUrl/check-out?spotId=$spotId');

    try {
      final response = await http.post(url);
      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        double fee = data['fee'];

        // Ücreti Göster
        showDialog(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text("Çıkış Başarılı! 💸"),
            content: Text(
              "Ödenecek Tutar: ${fee} TL\nSüre: ${data['entryTime']} - ${data['exitTime']}",
              style: const TextStyle(fontSize: 18),
            ),
            actions: [
              TextButton(onPressed: () => Navigator.pop(context), child: const Text("Tamam"))
            ],
          ),
        );
        fetchParkingSpots();
      } else {
        showError("Çıkış Hatası: ${response.body}");
      }
    } catch (e) {
      showError("Bağlantı Hatası: $e");
    }
  }

  // Hata Gösterme Yardımcısı
  void showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), backgroundColor: Colors.red),
    );
  }

  // Park Yeri Tıklanınca Açılacak Pencere
  void handleSpotClick(dynamic spot) {
    bool isOccupied = spot['occupied'] == true;
    int spotId = spot['id'];
    String spotName = spot['spotName'];

    if (!isOccupied) {
      // BOŞ İSE -> GİRİŞ PENCERESİ AÇ
      TextEditingController plateController = TextEditingController();
      showDialog(
        context: context,
        builder: (context) => AlertDialog(
          title: Text("$spotName - Araç Girişi"),
          content: TextField(
            controller: plateController,
            decoration: const InputDecoration(labelText: "Plaka Giriniz (Örn: 34ABC99)"),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context), // İptal
              child: const Text("İptal"),
            ),
            ElevatedButton(
              onPressed: () {
                if (plateController.text.isNotEmpty) {
                  checkInVehicle(spotId, plateController.text);
                  Navigator.pop(context);
                }
              },
              child: const Text("Giriş Yap"),
            ),
          ],
        ),
      );
    } else {
      // DOLU İSE -> ÇIKIŞ ONAYI İSTE
      showDialog(
        context: context,
        builder: (context) => AlertDialog(
          title: Text("$spotName - Çıkış Yap"),
          content: const Text("Bu aracı çıkarmak ve ücreti hesaplamak istiyor musunuz?"),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context), child: const Text("Hayır")),
            ElevatedButton(
              style: ElevatedButton.styleFrom(backgroundColor: Colors.red, foregroundColor: Colors.white),
              onPressed: () {
                checkOutVehicle(spotId);
                Navigator.pop(context);
              },
              child: const Text("Çıkış Yap ve Hesapla"),
            ),
          ],
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('BilPark Mobil', style: TextStyle(color: Colors.white)),
        backgroundColor: Colors.indigo,
        actions: [
          IconButton(icon: const Icon(Icons.refresh, color: Colors.white), onPressed: fetchParkingSpots)
        ],
      ),
      body: isLoading
          ? const Center(child: CircularProgressIndicator())
          : Padding(
        padding: const EdgeInsets.all(10.0),
        child: GridView.builder(
          gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 2,
            crossAxisSpacing: 10,
            mainAxisSpacing: 10,
            childAspectRatio: 1.5,
          ),
          itemCount: spots.length,
          itemBuilder: (context, index) {
            final spot = spots[index];
            final isOccupied = spot['occupied'] == true;

            return InkWell( // TIKLAMA ÖZELLİĞİ EKLENDİ
              onTap: () => handleSpotClick(spot),
              child: Card(
                color: isOccupied ? Colors.red.shade100 : Colors.green.shade100,
                elevation: 4,
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(15),
                    side: BorderSide(color: isOccupied ? Colors.red : Colors.green, width: 2)
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(
                      isOccupied ? Icons.directions_car : Icons.local_parking,
                      size: 40,
                      color: isOccupied ? Colors.red : Colors.green,
                    ),
                    const SizedBox(height: 10),
                    Text(
                      spot['spotName'],
                      style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
                    ),
                    Text(
                      isOccupied ? "DOLU" : "BOŞ",
                      style: TextStyle(
                          color: isOccupied ? Colors.red : Colors.green,
                          fontWeight: FontWeight.bold
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}