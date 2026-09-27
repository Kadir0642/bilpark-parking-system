import 'package:flutter/material.dart';
import 'dart:convert';
import 'package:http/http.dart' as http;
import '../core/constants.dart';
import '../main.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  String? selectedRegion;
  String? selectedNeighborhood;
  String? selectedStreet;

  List<dynamic> availableZones = [];
  dynamic selectedZone;
  bool isLoadingZones = false;

  Future<void> fetchZones(String streetName) async {
    setState(() { isLoadingZones = true; selectedZone = null; availableZones = []; });
    String backendEnum = AppConstants.toBackendEnum(streetName);
    try {
      final response = await http.get(Uri.parse('${AppConstants.apiBase}/zones/by-street?street=$backendEnum'));
      if (response.statusCode == 200) {
        setState(() {
          availableZones = json.decode(response.body);
        });
      }
    } catch (e) {
      debugPrint('Zone fetch error: $e');
    }
    setState(() { isLoadingZones = false; });
  }

  // 🚀 BİLPARK 2.0 GERÇEK VERİTABANI HARİTASI
  final Map<String, Map<String, List<String>>> locationData = {
    'Bilecik': {
      'Merkez': [
        'Tevfik Bey Caddesi',
        'Ali Rıza Özkay Caddesi',
        'Cumhuriyet Caddesi'
      ]
    }
  };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF3F51B5),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(30.0),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
                  padding: const EdgeInsets.all(20),
                  decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle),
                  child: const Icon(Icons.local_parking, size: 80, color: Color(0xFF3F51B5)),
                ),
                const SizedBox(height: 20),
                const Text("BilPark", style: TextStyle(fontSize: 40, fontWeight: FontWeight.bold, color: Colors.white, letterSpacing: 2)),
                const Text("Saha Operasyon Sistemi", style: TextStyle(fontSize: 16, color: Colors.white70)),
                const SizedBox(height: 50),

                Container(
                  padding: const EdgeInsets.all(25),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(20),
                    boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 15, offset: Offset(0, 5))],
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text("Vardiya Bilgileri", style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Color(0xFF3F51B5))),
                      const SizedBox(height: 20),

                      _buildDropdown("İl Seçiniz", selectedRegion, locationData.keys.toList(), (val) {
                        setState(() { selectedRegion = val; selectedNeighborhood = null; selectedStreet = null; });
                      }),
                      const SizedBox(height: 15),

                      if (selectedRegion != null)
                        _buildDropdown("İlçe Seçiniz", selectedNeighborhood, locationData[selectedRegion]!.keys.toList(), (val) {
                          setState(() { selectedNeighborhood = val; selectedStreet = null; });
                        }),
                      if (selectedRegion != null) const SizedBox(height: 15),

                      if (selectedNeighborhood != null)
                        _buildDropdown("Cadde/Sokak Seçiniz", selectedStreet, locationData[selectedRegion]![selectedNeighborhood]!, (val) {
                          setState(() { selectedStreet = val; });
                          if (val != null) fetchZones(val);
                        }),
                      if (selectedStreet != null) const SizedBox(height: 15),

                      if (isLoadingZones)
                         const Center(child: CircularProgressIndicator())
                      else if (selectedStreet != null && availableZones.isNotEmpty)
                        _buildZoneDropdown("Bölüm Seçiniz (Opsiyonel)", selectedZone, availableZones, (val) {
                          setState(() => selectedZone = val);
                        }),
                      const SizedBox(height: 30),

                      SizedBox(
                        width: double.infinity,
                        height: 50,
                        child: ElevatedButton(
                          style: ElevatedButton.styleFrom(
                              backgroundColor: const Color(0xFF3F51B5), foregroundColor: Colors.white,
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))
                          ),
                          onPressed: (selectedStreet != null) ? () {
                            Navigator.pushReplacement(
                                context,
                                MaterialPageRoute(builder: (context) => MainContainer(
                                  region: selectedRegion!,
                                  neighborhood: selectedNeighborhood!,
                                  street: selectedStreet!,
                                  zoneId: selectedZone != null ? selectedZone['id'] : null,
                                  zoneName: selectedZone != null ? selectedZone['zoneName'] : null,
                                ))
                            );
                          } : null,
                          child: const Text("VARDİYAYI BAŞLAT", style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, letterSpacing: 1)),
                        ),
                      )
                    ],
                  ),
                )
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildDropdown(String hint, String? value, List<String> items, Function(String?) onChanged) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 5),
      decoration: BoxDecoration(
          color: Colors.grey[100], borderRadius: BorderRadius.circular(10), border: Border.all(color: Colors.grey[300]!)
      ),
      child: DropdownButtonHideUnderline(
        child: DropdownButton<String>(
          isExpanded: true,
          hint: Text(hint, style: TextStyle(color: Colors.grey[600])),
          value: value,
          icon: const Icon(Icons.keyboard_arrow_down, color: Color(0xFF3F51B5)),
          items: items.map((String val) => DropdownMenuItem(value: val, child: Text(val, style: const TextStyle(fontWeight: FontWeight.bold)))).toList(),
          onChanged: onChanged,
        ),
      ),
    );
  }

  Widget _buildZoneDropdown(String hint, dynamic value, List<dynamic> items, Function(dynamic) onChanged) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 5),
      decoration: BoxDecoration(
          color: Colors.grey[100], borderRadius: BorderRadius.circular(10), border: Border.all(color: Colors.grey[300]!)
      ),
      child: DropdownButtonHideUnderline(
        child: DropdownButton<dynamic>(
          isExpanded: true,
          hint: Text(hint, style: TextStyle(color: Colors.grey[600])),
          value: value,
          icon: const Icon(Icons.keyboard_arrow_down, color: Color(0xFF3F51B5)),
          items: items.map((dynamic val) => DropdownMenuItem(value: val, child: Text(val['zoneName'], style: const TextStyle(fontWeight: FontWeight.bold)))).toList(),
          onChanged: onChanged,
        ),
      ),
    );
  }
}