import 'package:flutter/material.dart';
import 'dart:convert';
import 'package:http/http.dart' as http;

// BURAYA KENDİ NGROK ADRESİNİ YAZ!
final String globalBaseUrl = "https://oversufficiently-picturesque-eve.ngrok-free.dev/api/parking";

class HistoryScreen extends StatefulWidget {
  final String region;
  final String neighborhood;
  final String street;

  const HistoryScreen({super.key, required this.region, required this.neighborhood, required this.street});

  @override
  State<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends State<HistoryScreen> {
  List<dynamic> historyRecords = [];
  bool isLoading = true;

  @override
  void initState() {
    super.initState();
    fetchHistory();
  }

  Future<void> fetchHistory() async {
    final uri = Uri.parse('$globalBaseUrl/history').replace(queryParameters: {
      'region': widget.region,
      'neighborhood': widget.neighborhood,
      'street': widget.street,
    });

    try {
      final response = await http.get(uri);
      if (response.statusCode == 200) {
        if (mounted) {
          setState(() {
            historyRecords = json.decode(response.body);
            isLoading = false;
          });
        }
      }
    } catch (e) {
      debugPrint("Geçmiş Çekme Hatası: $e");
      if (mounted) setState(() => isLoading = false);
    }
  }

  // Tarih formatını güzelleştirmek için yardımcı fonksiyon
  String formatTime(String? timeStr) {
    if (timeStr == null) return "Devam Ediyor";
    DateTime dt = DateTime.parse(timeStr);
    return "${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}";
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF5F5F5),
      appBar: AppBar(
        title: const Text("Geçmiş Kayıtlar", style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
        backgroundColor: const Color(0xFF3F51B5),
        iconTheme: const IconThemeData(color: Colors.white),
      ),
      body: isLoading
          ? const Center(child: CircularProgressIndicator())
          : historyRecords.isEmpty
          ? const Center(child: Text("Bu caddede henüz hiç araç çıkışı yapılmamış 🚗", style: TextStyle(color: Colors.grey, fontSize: 16)))
          : ListView.builder(
        padding: const EdgeInsets.all(15),
        itemCount: historyRecords.length,
        itemBuilder: (context, index) {
          final record = historyRecords[index];

          // Araç hala içeride mi (Çıkış yapmamış mı?)
          bool isInside = record["exitTime"] == null;
          double fee = record["fee"] != null ? (record["fee"] as num).toDouble() : 0.0;

          return Card(
            elevation: 3,
            margin: const EdgeInsets.only(bottom: 15),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(15)),
            child: ListTile(
              contentPadding: const EdgeInsets.all(15),
              leading: Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                    color: isInside ? Colors.orange.withOpacity(0.1) : Colors.green.withOpacity(0.1),
                    shape: BoxShape.circle
                ),
                child: Icon(
                    isInside ? Icons.timelapse : Icons.check_circle,
                    color: isInside ? Colors.orange : Colors.green,
                    size: 30
                ),
              ),
              title: Text(record["licensePlate"] ?? "Bilinmiyor", style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 18, letterSpacing: 1)),
              subtitle: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const SizedBox(height: 5),
                  Text("Giriş: ${formatTime(record["entryTime"])}  -  Çıkış: ${formatTime(record["exitTime"])}", style: TextStyle(color: Colors.grey[700])),
                  const SizedBox(height: 3),
                  Text(
                      isInside ? "Durum: İçeride" : "Durum: Ödendi",
                      style: TextStyle(
                          color: isInside ? Colors.orange : Colors.green,
                          fontWeight: FontWeight.bold,
                          fontSize: 12
                      )
                  ),
                ],
              ),
              trailing: Text(
                  "$fee TL",
                  style: TextStyle(
                      fontWeight: FontWeight.bold,
                      fontSize: 18,
                      color: isInside ? Colors.grey : const Color(0xFF3F51B5)
                  )
              ),
            ),
          );
        },
      ),
    );
  }
}