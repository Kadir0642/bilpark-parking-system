import 'package:flutter/material.dart';
import 'screens/login_screen.dart';
import 'screens/dashboard_screen.dart';
import 'screens/parking_map_screen.dart';
import 'screens/auth_screen.dart';

// 🪄 YENİ: Tüm uygulamanın temasını dinleyen global haberci
final ValueNotifier<ThemeMode> themeNotifier = ValueNotifier(ThemeMode.light);

void main() {
  runApp(const BilParkApp());
}

class BilParkApp extends StatelessWidget {
  const BilParkApp({super.key});

  @override
  Widget build(BuildContext context) {
    // 🪄 YENİ: ValueListenableBuilder ile temadaki değişimi anlık dinliyoruz
    return ValueListenableBuilder<ThemeMode>(
        valueListenable: themeNotifier,
        builder: (context, currentMode, child) {
          return MaterialApp(
            debugShowCheckedModeBanner: false,
            title: 'BilPark Pro',

            // ☀️ AYDINLIK MOD (Gündüz Vardiyası - Mevcut Tasarımımız)
            theme: ThemeData(
              brightness: Brightness.light,
              primaryColor: const Color(0xFF3F51B5),
              scaffoldBackgroundColor: const Color(0xFFF5F5F5),
              useMaterial3: true,
              bottomSheetTheme: const BottomSheetThemeData(backgroundColor: Colors.transparent),
            ),

            // 🌙 KARANLIK MOD (Gece Vardiyası - Yeni Tasarım)
            darkTheme: ThemeData(
                brightness: Brightness.dark,
                primaryColor: Colors.indigo,
                scaffoldBackgroundColor: const Color(0xFF121212), // Koyu Gri/Siyah Arka Plan
                cardColor: const Color(0xFF1E1E1E), // Kartların koyu rengi
                useMaterial3: true,
                bottomSheetTheme: const BottomSheetThemeData(backgroundColor: Colors.transparent),
                appBarTheme: AppBarTheme(
                  backgroundColor: Colors.grey[900], // Gece modunda AppBar rengi
                  iconTheme: const IconThemeData(color: Colors.white),
                )
            ),

            // 🪄 Haberci hangi moddaysa onu uygula
            themeMode: currentMode,

            home: const AuthScreen(), // BAŞLANGIÇ DOĞRULAMA EKRANIYLA
          );
        }
    );
  }
}

class MainContainer extends StatefulWidget {
  final String region;
  final String neighborhood;
  final String street;
  final int? zoneId;
  final String? zoneName;

  const MainContainer({
    super.key,
    required this.region,
    required this.neighborhood,
    required this.street,
    this.zoneId,
    this.zoneName
  });

  @override
  State<MainContainer> createState() => _MainContainerState();
}

class _MainContainerState extends State<MainContainer> {
  final PageController _pageController = PageController(initialPage: 0);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: PageView(
        controller: _pageController,
        children: [
          DashboardScreen(
              region: widget.region,
              neighborhood: widget.neighborhood,
              street: widget.street,
              zoneId: widget.zoneId,
              zoneName: widget.zoneName
          ),
          // 👇 EKSİK OLAN KISIM BURASIYDI, EKLENDİ 👇
          ParkingMapScreen(
              region: widget.region,
              neighborhood: widget.neighborhood,
              street: widget.street,
              zoneId: widget.zoneId,
              zoneName: widget.zoneName
          ),
        ],
      ),
    );
  }
}