import 'package:flutter/material.dart';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'settings_screen.dart'; // Ayarlar sayfasını bağladık
import 'history_screen.dart'; // Geçmiş sayfasını bağladık

// NGROK LİNKİNİ BURAYA DA EKLE (İleride bunu da tek bir config dosyasına alacağız)
final String globalBaseUrl = "https://oversufficiently-picturesque-eve.ngrok-free.dev/api/parking";

class DashboardScreen extends StatefulWidget {
  final String region;
  final String neighborhood;
  final String street;

  // Constructor: Artık bu sayfa açılırken lokasyon bilgisi isteyecek
  const DashboardScreen({
    super.key,
    required this.region,
    required this.neighborhood,
    required this.street
  });

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  int totalSpots = 0;
  int occupiedSpots = 0;
  int occupancyRate = 0;

  @override
  void initState() {
    super.initState();
    fetchStats();
  }

  Future<void> fetchStats() async {
    final uri = Uri.parse('$globalBaseUrl/filter').replace(queryParameters: {
      'region': widget.region,
      'neighborhood': widget.neighborhood,
      'street': widget.street,
    });

    try {
      final response = await http.get(uri);
      if (!mounted) return;
      if (response.statusCode == 200) {
        List<dynamic> spots = json.decode(response.body);
        setState(() {
          totalSpots = spots.length;
          occupiedSpots = spots.where((s) => s['occupied'] == true).length;
          occupancyRate = totalSpots > 0 ? ((occupiedSpots / totalSpots) * 100).toInt() : 0;
        });
      }
    } catch (e) {
      debugPrint("İstatistik Hatası: $e");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF5F5F5),
      appBar: AppBar(
        title: const Text("BilPark Panel", style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
        backgroundColor: const Color(0xFF3F51B5),
        elevation: 0,
        actions: [
          IconButton(icon: const Icon(Icons.refresh, color: Colors.white), onPressed: fetchStats)
        ],
      ),
      body: Column(
        children: [
          // MAVİ İSTATİSTİK VE LOKASYON KARTI (Senin Fikrin 💡)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(20),
            decoration: const BoxDecoration(
              color: Color(0xFF3F51B5),
              borderRadius: BorderRadius.only(bottomLeft: Radius.circular(30), bottomRight: Radius.circular(30)),
            ),
            child: Column(
              children: [
                // LOKASYON BİLGİSİ EKLENDİ
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  decoration: BoxDecoration(color: Colors.white.withOpacity(0.2), borderRadius: BorderRadius.circular(20)),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.location_on, color: Colors.white, size: 16),
                      const SizedBox(width: 5),
                      Text("${widget.region}, ${widget.street}", style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
                    ],
                  ),
                ),
                const SizedBox(height: 15),
                const Text("Anlık Doluluk", style: TextStyle(color: Colors.white70, fontSize: 16)),
                const SizedBox(height: 5),
                Text("%$occupancyRate", style: const TextStyle(color: Colors.white, fontSize: 60, fontWeight: FontWeight.bold)),
                Text("$occupiedSpots / $totalSpots Araç", style: const TextStyle(color: Colors.white70, fontSize: 14)),
                const SizedBox(height: 20),
                ClipRRect(
                  borderRadius: BorderRadius.circular(10),
                  child: LinearProgressIndicator(
                    value: totalSpots > 0 ? occupiedSpots / totalSpots : 0,
                    minHeight: 10,
                    backgroundColor: Colors.white24,
                    valueColor: const AlwaysStoppedAnimation<Color>(Colors.white),
                  ),
                ),
                const SizedBox(height: 20),
                const Text("Saha Görünümü İçin Kaydırın 👉", style: TextStyle(color: Colors.white38, fontSize: 12)),
              ],
            ),
          ),
          const SizedBox(height: 20),

          // SADELEŞTİRİLMİŞ MENÜ BUTONLARI
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: GridView.count(
                crossAxisCount: 2,
                crossAxisSpacing: 15,
                mainAxisSpacing: 15,
                children: [
                  _buildMenuCard(Icons.history, "Geçmiş", Colors.orange, () {
                    // DÜZELTİLEN KISIM BURASI 👇 (const kelimesi silindi ve lokasyon bilgileri eklendi)
                    Navigator.push(context, MaterialPageRoute(builder: (context) => HistoryScreen(
                      region: widget.region,
                      neighborhood: widget.neighborhood,
                      street: widget.street,
                    )));
                  }),
                  _buildMenuCard(Icons.settings, "Ayarlar", Colors.grey, () {
                    Navigator.push(context, MaterialPageRoute(builder: (context) => const SettingsScreen()));
                  }),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildMenuCard(IconData icon, String title, Color color, VoidCallback onTap) {
    return Card(
      elevation: 4,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(20),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              padding: const EdgeInsets.all(15),
              decoration: BoxDecoration(color: color.withOpacity(0.1), shape: BoxShape.circle),
              child: Icon(icon, size: 40, color: color),
            ),
            const SizedBox(height: 15),
            Text(title, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.black87)),
          ],
        ),
      ),
    );
  }
}