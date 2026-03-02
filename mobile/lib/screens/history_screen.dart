import 'package:flutter/material.dart';
import 'dart:convert';
import 'package:http/http.dart' as http;

final String globalBaseUrl = "https://bilpark-api-rtdl.onrender.com/api/parking";

class HistoryScreen extends StatefulWidget {
  final String region;
  final String neighborhood;
  final String street;
  final bool showOnlyRunaways; // DRY PRENSİBİ: Bu sayfa Kaçaklar için mi açıldı?

  const HistoryScreen({
    super.key,
    required this.region,
    required this.neighborhood,
    required this.street,
    required this.showOnlyRunaways // Yeni eklenen zorunlu parametre
  });

  @override
  State<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends State<HistoryScreen> {
  List<dynamic> historyRecords = [];
  bool isLoading = true;
  TextEditingController searchController = TextEditingController();

  // Türkçe caddeleri Backend Enum'una çeviren akıllı köprü
  String get _backendStreetEnum {
    if (widget.street.contains("Tevfik")) return "TEVFIK_BEY";
    if (widget.street.contains("Ali Rıza")) return "ALI_RIZA_OZKAY";
    if (widget.street.contains("Cumhuriyet")) return "CUMHURIYET";
    return "TEVFIK_BEY";
  }

  @override
  void initState() {
    super.initState();
    fetchHistory();
  }

// --- 1. SADELEŞTİRİLMİŞ GEÇMİŞİ GETİR ---
  Future<void> fetchHistory() async {
    setState(() => isLoading = true);

    final uri = Uri.parse('$globalBaseUrl/history').replace(queryParameters: {
      'street': _backendStreetEnum,
    });

    try {
      final response = await http.get(uri);

      if (response.statusCode == 200 && mounted) {
        List<dynamic> allRecords = json.decode(response.body);

        if (widget.showOnlyRunaways) {
          // Eğer Kaçaklar sayfasındaysak, SADECE KAÇANLARI al
          allRecords = allRecords.where((r) => r['status'] == 'RUNAWAY').toList();
        } else {
          // Eğer Normal Geçmiş sayfasındaysak, SADECE ÖDEYENLERİ (PAID) al
          allRecords = allRecords.where((r) => r['status'] == 'PAID').toList();
        }

        setState(() {
          historyRecords = allRecords;
          isLoading = false;
        });
      } else {
        setState(() => isLoading = false);
      }
    } catch (e) {
      debugPrint("Geçmiş Çekme Hatası: $e");
      if (mounted) setState(() => isLoading = false);
    }
  }

// --- 2. GÜNCELLENMİŞ ARAMA MOTORU ---
  Future<void> searchPlate(String plate) async {
    if (plate.isEmpty) {
      fetchHistory();
      return;
    }

    setState(() => isLoading = true);
    final uri = Uri.parse('$globalBaseUrl/history/search').replace(queryParameters: {
      'plate': plate.toUpperCase().replaceAll(' ', ''),
    });

    try {
      final response = await http.get(uri);

      if (response.statusCode == 200 && mounted) {
        List<dynamic> results = json.decode(response.body);

        // ARAMADA DA AYNI KESİN AYRIMI YAPIYORUZ
        if (widget.showOnlyRunaways) {
          results = results.where((r) => r['status'] == 'RUNAWAY').toList();
        } else {
          results = results.where((r) => r['status'] == 'PAID').toList();
        }

        setState(() {
          historyRecords = results;
          isLoading = false;
        });
      } else {
        setState(() { historyRecords = []; isLoading = false; });
      }
    } catch (e) {
      debugPrint("Arama Hatası: $e");
      if (mounted) setState(() => isLoading = false);
    }
  }

  String formatTime(String? timeStr) {
    if (timeStr == null) return "-";
    DateTime dt = DateTime.parse(timeStr).toLocal();
    return "${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}";
  }

  String formatDate(String? timeStr) {
    if (timeStr == null) return "";
    DateTime dt = DateTime.parse(timeStr).toLocal();
    return "${dt.day}/${dt.month}/${dt.year}";
  }

  @override
  Widget build(BuildContext context) {
    // Sayfanın teması nereden geldiğimize göre tamamen değişir!
    Color themeColor = widget.showOnlyRunaways ? Colors.red[700]! : const Color(0xFF3F51B5);
    String pageTitle = widget.showOnlyRunaways ? "Kara Liste (Kaçaklar) 🚨" : "Tüm Geçmiş Kayıtlar";

    return Scaffold(
      backgroundColor: const Color(0xFFF5F5F5),
      appBar: AppBar(
        title: Text(pageTitle, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 18)),
        backgroundColor: themeColor,
        iconTheme: const IconThemeData(color: Colors.white),
      ),
      body: Column(
        children: [
          // YENİ: ARAMA ÇUBUĞU (Senin Fikrin 💡)
          Container(
            padding: const EdgeInsets.all(15),
            decoration: const BoxDecoration(
                color: Colors.white,
                boxShadow: [BoxShadow(color: Colors.black12, blurRadius: 4, offset: Offset(0, 2))]
            ),
            child: TextField(
              controller: searchController,
              onSubmitted: (value) => searchPlate(value), // Klavyede 'Ara'ya basınca çalışır
              decoration: InputDecoration(
                hintText: "Plaka Ara (Örn: 34ABC123)",
                prefixIcon: const Icon(Icons.search),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.clear),
                  onPressed: () {
                    searchController.clear();
                    fetchHistory(); // Temizleyince eski listeyi getir
                  },
                ),
                filled: true,
                fillColor: Colors.grey[100],
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(15), borderSide: BorderSide.none),
              ),
            ),
          ),

          // LİSTE KISMI
          Expanded(
            child: isLoading
                ? Center(child: CircularProgressIndicator(color: themeColor))
                : historyRecords.isEmpty
                ? Center(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(widget.showOnlyRunaways ? Icons.shield : Icons.history, size: 80, color: Colors.grey[400]),
                    const SizedBox(height: 15),
                    Text(widget.showOnlyRunaways ? "Bu caddede kaçak araç kaydı yok. Harika!" : "Kayıt bulunamadı.", style: const TextStyle(color: Colors.grey, fontSize: 16)),
                  ],
                )
            )
                : ListView.builder(
              padding: const EdgeInsets.all(15),
              itemCount: historyRecords.length,
              itemBuilder: (context, index) {
                final record = historyRecords[index];

                // YENİ BİLPARK 2.0 STATUS MANTIĞI
                bool isRunaway = record['status'] == 'RUNAWAY';
                double fee = record["fee"] != null ? (record["fee"] as num).toDouble() : 0.0;

                return Card(
                  elevation: 2,
                  margin: const EdgeInsets.only(bottom: 15),
                  shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(15),
                      side: BorderSide(color: isRunaway ? Colors.redAccent : Colors.transparent, width: 1)
                  ),
                  child: ListTile(
                    contentPadding: const EdgeInsets.all(15),
                    leading: Container(
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                          color: isRunaway ? Colors.red.withOpacity(0.1) : Colors.green.withOpacity(0.1),
                          shape: BoxShape.circle
                      ),
                      child: Icon(
                          isRunaway ? Icons.warning_rounded : Icons.check_circle,
                          color: isRunaway ? Colors.red : Colors.green,
                          size: 30
                      ),
                    ),
                    title: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(record["licensePlate"] ?? "Bilinmiyor", style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 18, letterSpacing: 1)),
                        Text(formatDate(record["entryTime"]), style: const TextStyle(fontSize: 12, color: Colors.grey)),
                      ],
                    ),
                    subtitle: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const SizedBox(height: 8),
                        Text("Giriş: ${formatTime(record["entryTime"])}  -  Çıkış: ${formatTime(record["exitTime"])}", style: TextStyle(color: Colors.grey[800])),
                        const SizedBox(height: 5),
                        Text(
                            isRunaway ? "DURUM: ÖDEMEDEN KAÇTI" : "DURUM: ÖDEME ALINDI",
                            style: TextStyle(
                                color: isRunaway ? Colors.red : Colors.green,
                                fontWeight: FontWeight.bold,
                                fontSize: 12
                            )
                        ),
                      ],
                    ),
                    trailing: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Text(isRunaway ? "Kalan Borç" : "Tahsilat", style: const TextStyle(fontSize: 10, color: Colors.grey)),
                        Text(
                            "$fee TL",
                            style: TextStyle(
                                fontWeight: FontWeight.bold,
                                fontSize: 18,
                                color: isRunaway ? Colors.red : Colors.green
                            )
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}