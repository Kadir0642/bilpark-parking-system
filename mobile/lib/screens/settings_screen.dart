import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../core/api_client.dart';
import '../core/constants.dart';
import '../main.dart'; // YENİ: main.dart içindeki themeNotifier'ı içeri aktardık

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  Future<void> endShift(BuildContext context) async {
    String? warning;
    try {
      // Sunucuda bu cihazın açık vardiyasını kapatır
      if (await ApiClient.getToken() != null) {
        await ApiClient.post('/auth/logout');
      }
    } on ApiException catch (e) {
      // 401 zaten girişe yönlendirir; diğer hatalarda yerel çıkış yine de yapılır ama kullanıcı bilgilendirilir
      if (!e.isUnauthorized) warning = 'Vardiya sunucuda kapatılamadı: ${e.message}';
    }
    await ApiClient.clearToken();
    AppConstants.clearAuth();
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('user_role');
    await prefs.remove('username');

    if (!context.mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    Navigator.of(context).pushNamedAndRemoveUntil('/', (Route<dynamic> route) => false);
    if (warning != null) {
      messenger.showSnackBar(SnackBar(content: Text(warning), backgroundColor: Colors.red));
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
            activeThumbColor: Colors.indigoAccent,
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