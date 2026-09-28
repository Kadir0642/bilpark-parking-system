import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import '../core/constants.dart';
import '../main.dart'; // YENİ: main.dart içindeki themeNotifier'ı içeri aktardık

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  Future<void> endShift(BuildContext context) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final token = prefs.getString('auth_token');
      
      if (token != null) {
        await http.post(
          Uri.parse('${AppConstants.apiBase}/auth/logout'),
          headers: {'Authorization': 'Bearer $token'},
        );
      }
      await prefs.remove('auth_token');
      await prefs.remove('user_role');
      await prefs.remove('username');
      
      if (mounted) {
        Navigator.of(context).pushNamedAndRemoveUntil('/', (Route<dynamic> route) => false);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Çıkış yapılırken hata oluştu: $e')));
        Navigator.of(context).pushNamedAndRemoveUntil('/', (Route<dynamic> route) => false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    // Düğmenin durumunu haberciden anlık okuyoruz
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

          // KARANLIK MOD DÜĞMESİ
          SwitchListTile(
            title: const Text("Karanlık Mod (Gece Vardiyası)"),
            subtitle: const Text("Göz yorgunluğunu azaltır"),
            secondary: Icon(isDarkMode ? Icons.dark_mode : Icons.light_mode),
            value: isDarkMode,
            activeColor: Colors.indigoAccent,
            onChanged: (bool value) async {
              themeNotifier.value = value ? ThemeMode.dark : ThemeMode.light;
              final prefs = await SharedPreferences.getInstance();
              await prefs.setBool('isDarkMode', value);
            },
          ),

          const Divider(),
          const SizedBox(height: 20),
          const Text("Operasyon", style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.indigo)),
          const SizedBox(height: 10),
          ListTile(
            leading: const Icon(Icons.exit_to_app, color: Colors.red),
            title: const Text("Vardiyayı Sonlandır", style: TextStyle(color: Colors.red, fontWeight: FontWeight.bold)),
            subtitle: const Text("Oturumu kapatır ve ana ekrana döner"),
            onTap: () {
              showDialog(
                  context: context,
                  builder: (context) => AlertDialog(
                    title: const Text("Vardiya Bitsin mi?"),
                    content: const Text("Bu işlem oturumunuzu kapatacak ve sizi sistemden çıkış yapmış olarak işaretleyecektir.\nOnaylıyor musunuz?"),
                    actions: [
                      TextButton(onPressed: () => Navigator.pop(context), child: const Text("İptal")),
                      ElevatedButton(
                          style: ElevatedButton.styleFrom(backgroundColor: Colors.red, foregroundColor: Colors.white),
                          onPressed: () {
                            Navigator.pop(context);
                            endShift(context);
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