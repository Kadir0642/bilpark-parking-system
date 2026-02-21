import 'package:flutter/material.dart';
import 'login_screen.dart'; // Vardiya/Lokasyon seçim ekranına gidecek

class AuthScreen extends StatefulWidget {
  const AuthScreen({super.key});

  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  final TextEditingController _usernameController = TextEditingController();
  final TextEditingController _passwordController = TextEditingController();
  bool _obscurePassword = true;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF3F51B5), // Kurumsal Mavi
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 30.0),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // LOGO
                Container(
                  padding: const EdgeInsets.all(20),
                  decoration: const BoxDecoration(
                    color: Colors.white,
                    shape: BoxShape.circle,
                    boxShadow: [BoxShadow(color: Colors.black26, blurRadius: 10, offset: Offset(0, 5))],
                  ),
                  child: const Icon(Icons.local_parking, size: 80, color: Color(0xFF3F51B5)),
                ),
                const SizedBox(height: 20),
                const Text("BilPark", style: TextStyle(fontSize: 40, fontWeight: FontWeight.bold, color: Colors.white, letterSpacing: 2)),
                const Text("Personel Giriş Sistemi", style: TextStyle(fontSize: 16, color: Colors.white70)),
                const SizedBox(height: 50),

                // GİRİŞ FORMU KARTI
                Container(
                  padding: const EdgeInsets.all(25),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(20),
                    boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 15, offset: Offset(0, 5))],
                  ),
                  child: Column(
                    children: [
                      const Text("Hoş Geldiniz", style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: Color(0xFF3F51B5))),
                      const SizedBox(height: 25),

                      // KULLANICI ADI
                      TextField(
                        controller: _usernameController,
                        decoration: InputDecoration(
                          labelText: "Personel Sicil No / TC",
                          prefixIcon: const Icon(Icons.person, color: Color(0xFF3F51B5)),
                          border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                          filled: true,
                          fillColor: Colors.grey[100],
                        ),
                      ),
                      const SizedBox(height: 15),

                      // ŞİFRE
                      TextField(
                        controller: _passwordController,
                        obscureText: _obscurePassword,
                        decoration: InputDecoration(
                          labelText: "Şifre",
                          prefixIcon: const Icon(Icons.lock, color: Color(0xFF3F51B5)),
                          suffixIcon: IconButton(
                            icon: Icon(_obscurePassword ? Icons.visibility_off : Icons.visibility, color: Colors.grey),
                            onPressed: () => setState(() => _obscurePassword = !_obscurePassword),
                          ),
                          border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                          filled: true,
                          fillColor: Colors.grey[100],
                        ),
                      ),
                      const SizedBox(height: 30),

                      // GİRİŞ BUTONU
                      SizedBox(
                        width: double.infinity,
                        height: 50,
                        child: ElevatedButton(
                          style: ElevatedButton.styleFrom(
                              backgroundColor: const Color(0xFF3F51B5),
                              foregroundColor: Colors.white,
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12))
                          ),
                          onPressed: () {
                            // ŞİMDİLİK DİREKT GEÇİŞ YAPIYORUZ (PROTOTİP)
                            // İleride buraya Backend şifre kontrolü eklenecek
                            Navigator.pushReplacement(
                                context,
                                MaterialPageRoute(builder: (context) => const LoginScreen())
                            );
                          },
                          child: const Text("GİRİŞ YAP", style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, letterSpacing: 1)),
                        ),
                      )
                    ],
                  ),
                ),

                const SizedBox(height: 30),
                const Text("v1.0.0 - BilPark Bilişim A.Ş.", style: TextStyle(color: Colors.white54, fontSize: 12)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}