import 'package:flutter/material.dart';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'settings_screen.dart';
import 'history_screen.dart';

final String globalBaseUrl = "https://bilpark-api-rtdl.onrender.com/api/parking";

class DashboardScreen extends StatefulWidget {
  final String region;
  final String neighborhood;
  final String street;

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
  int activeVehicleCount = 0;

  String get _backendStreetEnum {
    if (widget.street.contains("Tevfik")) return "TEVFIK_BEY";
    if (widget.street.contains("Ali Rıza")) return "ALI_RIZA_OZKAY";
    if (widget.street.contains("Cumhuriyet")) return "CUMHURIYET";
    return "TEVFIK_BEY";
  }

  @override
  void initState() {
    super.initState();
    fetchStats();
  }

  Future<void> fetchStats() async {
    final uri = Uri.parse('$globalBaseUrl/filter').replace(queryParameters: {
      'street': _backendStreetEnum,
    });

    try {
      final response = await http.get(uri);
      if (!mounted) return;
      if (response.statusCode == 200) {
        List<dynamic> spots = json.decode(response.body);
        setState(() {
          activeVehicleCount = spots.length;
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
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(20),
            decoration: const BoxDecoration(
              color: Color(0xFF3F51B5),
              borderRadius: BorderRadius.only(bottomLeft: Radius.circular(30), bottomRight: Radius.circular(30)),
            ),
            child: Column(
              children: [
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
                const SizedBox(height: 25),
                const Text("Caddedeki Aktif Araç Sayısı", style: TextStyle(color: Colors.white70, fontSize: 16)),
                const SizedBox(height: 5),
                Text("$activeVehicleCount", style: const TextStyle(color: Colors.white, fontSize: 70, fontWeight: FontWeight.bold)),
                const SizedBox(height: 10),
                const Text("Saha Görünümü İçin Kaydırın 👉", style: TextStyle(color: Colors.white38, fontSize: 12)),
              ],
            ),
          ),
          const SizedBox(height: 20),

          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: GridView.count(
                crossAxisCount: 2,
                crossAxisSpacing: 15,
                mainAxisSpacing: 15,
                children: [
                  // 1. BUTON: NORMAL GEÇMİŞ
                  _buildMenuCard(Icons.history, "Geçmiş", Colors.blue, () {
                    Navigator.push(context, MaterialPageRoute(builder: (context) => HistoryScreen(
                      region: widget.region,
                      neighborhood: widget.neighborhood,
                      street: widget.street,
                      showOnlyRunaways: false, // Normal Mod
                    )));
                  }),
                  // 2. BUTON: SADECE KAÇAKLAR (Senin Fikrin 🚨)
                  _buildMenuCard(Icons.warning_rounded, "Kaçak Araçlar", Colors.red, () {
                    Navigator.push(context, MaterialPageRoute(builder: (context) => HistoryScreen(
                      region: widget.region,
                      neighborhood: widget.neighborhood,
                      street: widget.street,
                      showOnlyRunaways: true, // Sadece Kaçaklar Modu!
                    )));
                  }),
                  // 3. BUTON: AYARLAR
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
            Text(title, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.black87), textAlign: TextAlign.center,),
          ],
        ),
      ),
    );
  }
}