import 'package:flutter/material.dart';
import '../core/api_client.dart';
import '../core/constants.dart';
import '../core/theme.dart';
import '../main.dart'; // MainContainer
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
  bool _isLoading = false;
  String? _error;

  @override
  void dispose() {
    _usernameController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    final username = _usernameController.text.trim();
    final password = _passwordController.text.trim();

    if (username.isEmpty || password.isEmpty) {
      setState(() => _error = 'Lütfen kullanıcı adı ve şifrenizi girin.');
      return;
    }

    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      final data = await ApiClient.post('/auth/login', body: {'username': username, 'password': password}, auth: false);

      // Save auth state
      await ApiClient.saveToken(data['token'] as String);
      AppConstants.currentUser = data['username'];
      AppConstants.currentRole = data['role'];
      final zone = data['assignedZone'];
      AppConstants.assignedZone = zone is Map<String, dynamic> ? zone : null;

      if (!mounted) return;
      if (data['role'] == 'OFFICER' && AppConstants.assignedZone != null) {
        // Atanmış bölgesi olan görevli: seçim ekranını atla
        final z = AppConstants.assignedZone!;
        final street = AppConstants.streetToEnum.entries
            .firstWhere((e) => e.value == z['street'], orElse: () => AppConstants.streetToEnum.entries.first)
            .key;
        Navigator.pushReplacement(
            context,
            MaterialPageRoute(
                builder: (context) => MainContainer(
                      region: 'Bilecik',
                      neighborhood: 'Merkez',
                      street: street,
                      zoneId: z['id'],
                      zoneName: z['zoneName'],
                    )));
      } else {
        Navigator.pushReplacement(context, MaterialPageRoute(builder: (context) => const LoginScreen()));
      }
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _error = e.message);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = 'Giriş sırasında beklenmeyen bir hata oluştu.');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    return Scaffold(
      backgroundColor: c.bg,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(BpSize.screenPad),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 440),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 72,
                    height: 72,
                    decoration: BoxDecoration(color: c.primary, borderRadius: BorderRadius.circular(20)),
                    alignment: Alignment.center,
                    child: Text('P', style: TextStyle(color: c.onPrimary, fontSize: 40, fontWeight: FontWeight.w800)),
                  ),
                  const SizedBox(height: BpSize.s3),
                  const Text('BilPark', style: TextStyle(fontSize: 32, fontWeight: FontWeight.w800, letterSpacing: -0.5)),
                  Text('Saha uygulaması', style: TextStyle(fontSize: 16, color: c.text2)),
                  const SizedBox(height: BpSize.s8),
                  Container(
                    padding: const EdgeInsets.all(BpSize.s5),
                    decoration: BoxDecoration(
                      color: c.surface,
                      border: Border.all(color: c.border),
                      borderRadius: BorderRadius.circular(BpSize.rLg),
                    ),
                    child: AutofillGroup(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          const Text('Personel girişi', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
                          const SizedBox(height: BpSize.s4),
                          TextField(
                            controller: _usernameController,
                            autofillHints: const [AutofillHints.username],
                            textInputAction: TextInputAction.next,
                            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w500),
                            decoration: const InputDecoration(
                              labelText: 'Kullanıcı adı',
                              prefixIcon: Icon(Icons.person_outline),
                              constraints: BoxConstraints(minHeight: BpSize.tapLg),
                            ),
                          ),
                          const SizedBox(height: BpSize.s4),
                          TextField(
                            controller: _passwordController,
                            obscureText: _obscurePassword,
                            autofillHints: const [AutofillHints.password],
                            onSubmitted: (_) => _isLoading ? null : _login(),
                            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w500),
                            decoration: InputDecoration(
                              labelText: 'Şifre',
                              prefixIcon: const Icon(Icons.lock_outline),
                              constraints: const BoxConstraints(minHeight: BpSize.tapLg),
                              suffixIcon: IconButton(
                                tooltip: _obscurePassword ? 'Şifreyi göster' : 'Şifreyi gizle',
                                icon: Icon(_obscurePassword ? Icons.visibility_off_outlined : Icons.visibility_outlined),
                                onPressed: () => setState(() => _obscurePassword = !_obscurePassword),
                              ),
                            ),
                          ),
                          if (_error != null) ...[
                            const SizedBox(height: BpSize.s4),
                            Container(
                              padding: const EdgeInsets.all(BpSize.s3),
                              decoration: BoxDecoration(
                                color: c.dangerSoft,
                                border: Border.all(color: c.danger),
                                borderRadius: BorderRadius.circular(BpSize.rSm),
                              ),
                              child: Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Icon(Icons.error_outline, size: 20, color: c.onDangerSoft),
                                  const SizedBox(width: 10),
                                  Expanded(child: Text(_error!, style: TextStyle(fontSize: 14, color: c.onDangerSoft))),
                                ],
                              ),
                            ),
                          ],
                          const SizedBox(height: BpSize.s4),
                          FilledButton(
                            onPressed: _isLoading ? null : _login,
                            child: _isLoading
                                ? SizedBox(
                                    height: 24,
                                    width: 24,
                                    child: CircularProgressIndicator(color: c.primary, strokeWidth: 2.5))
                                : const Text('Giriş yap', style: TextStyle(fontSize: 18)),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: BpSize.s6),
                  Text('Sürüm ${AppConstants.appVersion} · BilPark', style: TextStyle(fontSize: 13, color: c.text2)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
