import 'package:flutter/material.dart';
import '../main.dart'; // 🪄 YENİ: main.dart içindeki themeNotifier'ı içeri aktardık

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  @override
  Widget build(BuildContext context) {
    // 🪄 Düğmenin durumunu haberciden anlık okuyoruz
    bool isDarkMode = themeNotifier.value == ThemeMode.dark;

    return Scaffold(
      appBar: AppBar(
        title: const Text("Ayarlar", style: TextStyle(color: Colors.white)),
        // Gece modundaysa AppBar koyu gri, gündüzse lacivert olsun
        backgroundColor: isDarkMode ? Colors.grey[900] : const Color(0xFF3F51B5),
        iconTheme: const IconThemeData(color: Colors.white),
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text("Görünüm", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.indigo)),
          const SizedBox(height: 10),

          // 🌙 KARANLIK MOD DÜĞMESİ
          SwitchListTile(
            title: const Text("Karanlık Mod (Gece Vardiyası)"),
            subtitle: const Text("Göz yorgunluğunu azaltır"),
            secondary: Icon(isDarkMode ? Icons.dark_mode : Icons.light_mode),
            value: isDarkMode,
            activeColor: Colors.indigoAccent,
            onChanged: (bool value) {
              // 🪄 YENİ: Düğmeye basıldığında tüm uygulamaya haberi sal!
              themeNotifier.value = value ? ThemeMode.dark : ThemeMode.light;
            },
          ),

          const Divider(),
          const SizedBox(height: 20),
          const Text("Operasyon", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.indigo)),
          const SizedBox(height: 10),
          ListTile(
            leading: const Icon(Icons.security, color: Colors.green),
            title: const Text("Güvenli Çıkış (PIN İste)"),
            trailing: const Icon(Icons.chevron_right),
            onTap: () {},
          ),
          ListTile(
            leading: const Icon(Icons.exit_to_app, color: Colors.red),
            title: const Text("Vardiyayı Sonlandır", style: TextStyle(color: Colors.red, fontWeight: FontWeight.bold)),
            subtitle: const Text("Oturumu kapatır ve ana ekrana döner"),
            onTap: () {
              showDialog(
                  context: context,
                  builder: (context) => AlertDialog(
                    title: const Text("Vardiya Bitsin mi?"),
                    content: const Text("Bugünkü toplam çalışma süreniz: 08:30 Saat.\nOnaylıyor musunuz?"),
                    actions: [
                      TextButton(onPressed: () => Navigator.pop(context), child: const Text("İptal")),
                      ElevatedButton(
                          style: ElevatedButton.styleFrom(backgroundColor: Colors.red, foregroundColor: Colors.white),
                          onPressed: () {
                            Navigator.of(context).pushNamedAndRemoveUntil('/', (Route<dynamic> route) => false);
                          },
                          child: const Text("Sonlandır")
                      )
                    ],
                  )
              );
            },
          ),
        ],
      ),
    );
  }
}