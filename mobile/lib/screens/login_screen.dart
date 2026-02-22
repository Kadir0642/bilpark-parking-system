import 'package:flutter/material.dart';
import '../main.dart'; // MainContainer'a geçiş yapmak için

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  // Seçilen Değerler
  String? selectedRegion;
  String? selectedNeighborhood;
  String? selectedStreet;

  // Sahte Veritabanı (İleride Backend'den çekilebilir)
  final Map<String, Map<String, List<String>>> locationData = {
    'Bilecik': {
      'Merkez': ['Atatürk Mah', 'Cumhuriyet Myd', 'İstasyon Cad'] // parametrelerin sırası düzeltildi
    },
    'Ankara': {
      'Çankaya': ['Kızılay', 'Bahçelievler'],
      'Keçiören': ['Sanatoryum Cad']
    },
    'İstanbul': {
      'Kadıköy': ['Moda', 'Bağdat Cad'],
      'Beşiktaş': ['Barbaros Blv']
    }
  };

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF3F51B5), // BilPark Kurumsal Mavisi
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(30.0),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // LOGO VE BAŞLIK
                Container(
                  padding: const EdgeInsets.all(20),
                  decoration: const BoxDecoration(
                    color: Colors.white,
                    shape: BoxShape.circle,
                  ),
                  child: const Icon(Icons.local_parking, size: 80, color: Color(0xFF3F51B5)),
                ),
                const SizedBox(height: 20),
                const Text("BilPark", style: TextStyle(fontSize: 40, fontWeight: FontWeight.bold, color: Colors.white, letterSpacing: 2)),
                const Text("Saha Operasyon Sistemi", style: TextStyle(fontSize: 16, color: Colors.white70)),
                const SizedBox(height: 50),

                // LOKASYON SEÇİM KARTI
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

                      // İL SEÇİMİ
                      _buildDropdown("İl Seçiniz", selectedRegion, locationData.keys.toList(), (val) {
                        setState(() {
                          selectedRegion = val;
                          selectedNeighborhood = null;
                          selectedStreet = null;
                        });
                      }),
                      const SizedBox(height: 15),

                      // İLÇE SEÇİMİ
                      if (selectedRegion != null)
                        _buildDropdown("İlçe Seçiniz", selectedNeighborhood, locationData[selectedRegion]!.keys.toList(), (val) {
                          setState(() {
                            selectedNeighborhood = val;
                            selectedStreet = null;
                          });
                        }),
                      if (selectedRegion != null) const SizedBox(height: 15),

                      // CADDE SEÇİMİ
                      if (selectedNeighborhood != null)
                        _buildDropdown("Cadde/Sokak Seçiniz", selectedStreet, locationData[selectedRegion]![selectedNeighborhood]!, (val) {
                          setState(() => selectedStreet = val);
                        }),

                      const SizedBox(height: 30),

                      // BAŞLAT BUTONU
                      SizedBox(
                        width: double.infinity,
                        height: 50,
                        child: ElevatedButton(
                          style: ElevatedButton.styleFrom(
                              backgroundColor: const Color(0xFF3F51B5),
                              foregroundColor: Colors.white,
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))
                          ),
                          onPressed: (selectedStreet != null) ? () {
                            // Ana Ekrana Yönlendir ve Seçilen Verileri Gönder
                            Navigator.pushReplacement(
                                context,
                                MaterialPageRoute(builder: (context) => MainContainer(
                                  region: selectedRegion!,
                                  neighborhood: selectedNeighborhood!,
                                  street: selectedStreet!,
                                ))
                            );
                          } : null, // Seçim bitmeden buton tıklanamaz (null)
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

  // Özel Dropdown Tasarımı
  Widget _buildDropdown(String hint, String? value, List<String> items, Function(String?) onChanged) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 5),
      decoration: BoxDecoration(
          color: Colors.grey[100],
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: Colors.grey[300]!)
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
}