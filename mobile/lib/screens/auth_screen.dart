import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../core/api_client.dart';
import '../core/constants.dart';
import '../core/login_feedback.dart';
import '../core/streets.dart';
import '../core/theme.dart';
import '../main.dart'; // MainContainer
import 'login_screen.dart'; // Vardiya/Lokasyon seçim ekranına gidecek

class AuthScreen extends StatefulWidget {
  /// Oturumun neden kapandığını açıklayan kalıcı uyarı (pasife alma, süre dolması).
  final String? message;

  /// Testlerde ağsız giriş çağrısı (varsayılan: POST /auth/login). Yanıt gövdesini (Map) döner ya da [ApiException] atar.
  final Future<dynamic> Function(String username, String password)? loginCall;

  const AuthScreen({super.key, this.message, this.loginCall});

  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  final TextEditingController _usernameController = TextEditingController();
  final TextEditingController _passwordController = TextEditingController();
  bool _obscurePassword = true;
  bool _isLoading = false;
  LoginFeedback? _feedback;

  /// Geçici kilitte düğmeyi kapalı tutan geri sayım (saniye). Süre dolunca düğme kendiliğinden açılır;
  /// giriş ASLA kendiliğinden yeniden denenmez.
  Timer? _lockTimer;
  int _lockLeft = 0;

  bool get _locked => _lockLeft > 0;

  void _startLock(Duration wait) {
    _lockTimer?.cancel();
    _lockLeft = wait.inSeconds < 1 ? 1 : wait.inSeconds;
    _lockTimer = Timer.periodic(const Duration(seconds: 1), (t) {
      if (!mounted) return;
      setState(() {
        _lockLeft--;
        if (_lockLeft <= 0) {
          _lockLeft = 0;
          t.cancel();
          _feedback = const LoginFeedback('Bekleme süresi doldu. Şimdi tekrar deneyebilirsiniz.');
        }
      });
    });
  }

  @override
  void dispose() {
    _lockTimer?.cancel();
    _usernameController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    final username = _usernameController.text.trim();
    final password = _passwordController.text.trim();

    if (_locked) return;
    if (username.isEmpty || password.isEmpty) {
      setState(() => _feedback = const LoginFeedback('Lütfen kullanıcı adı ve şifrenizi girin.'));
      return;
    }

    setState(() {
      _isLoading = true;
      _feedback = null;
    });

    try {
      final call =
          widget.loginCall ??
          (String u, String p) => ApiClient.post('/auth/login', body: {'username': u, 'password': p}, auth: false);
      final data = await call(username, password);

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
        await StreetStore.load();
        if (!mounted) return;
        final code = '${z['street']}';
        Navigator.pushReplacement(
          context,
          MaterialPageRoute(
            builder: (context) => MainContainer(
              streetCode: code,
              streetName: StreetStore.displayName(code),
              zoneId: z['id'],
              zoneName: z['zoneName'],
            ),
          ),
        );
      } else {
        Navigator.pushReplacement(context, MaterialPageRoute(builder: (context) => const LoginScreen()));
      }
    } on ApiException catch (e) {
      if (!mounted) return;
      final fb = loginFeedbackFor(e);
      setState(() => _feedback = fb);
      if (fb.wait != null) _startLock(fb.wait!);
    } catch (e) {
      if (!mounted) return;
      setState(() => _feedback = const LoginFeedback(loginUnexpectedMessage));
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  /// Sakin bilgi kutusu: kırmızı uyarı yerine yumuşak sarı zemin, büyük ve okunaklı yazı.
  Widget _feedbackBox(BilParkColors c, LoginFeedback fb) {
    return Semantics(
      liveRegion: true,
      child: Container(
        key: const ValueKey('loginFeedback'),
        padding: const EdgeInsets.all(BpSize.s4),
        decoration: BoxDecoration(
          color: c.warningSoft,
          border: Border.all(color: c.warning),
          borderRadius: BorderRadius.circular(BpSize.rSm),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.info_outline, size: 24, color: c.onWarningSoft),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(fb.message, style: TextStyle(fontSize: 18, height: 1.3, color: c.onWarningSoft)),
                  if (fb.hint != null) ...[
                    const SizedBox(height: 8),
                    Text(fb.hint!, style: TextStyle(fontSize: 16, height: 1.3, color: c.onWarningSoft)),
                  ],
                  if (_locked) ...[
                    const SizedBox(height: 8),
                    Text(
                      'Kalan süre: ${formatCountdown(Duration(seconds: _lockLeft))}',
                      key: const ValueKey('loginCountdown'),
                      style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: c.onWarningSoft),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
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
                    child: Text(
                      'P',
                      style: TextStyle(color: c.onPrimary, fontSize: 40, fontWeight: FontWeight.w800),
                    ),
                  ),
                  const SizedBox(height: BpSize.s3),
                  const Text(
                    'BilPark',
                    style: TextStyle(fontSize: 32, fontWeight: FontWeight.w800, letterSpacing: -0.5),
                  ),
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
                          if (widget.message != null) ...[
                            Container(
                              padding: const EdgeInsets.all(BpSize.s3),
                              decoration: BoxDecoration(
                                color: c.warningSoft,
                                border: Border.all(color: c.warning),
                                borderRadius: BorderRadius.circular(BpSize.rSm),
                              ),
                              child: Row(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Icon(Icons.info_outline, size: 20, color: c.onWarningSoft),
                                  const SizedBox(width: 10),
                                  Expanded(
                                    child: Text(
                                      widget.message!,
                                      style: TextStyle(fontSize: 14, color: c.onWarningSoft),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            const SizedBox(height: BpSize.s4),
                          ],
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
                            onSubmitted: (_) => (_isLoading || _locked) ? null : _login(),
                            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w500),
                            decoration: InputDecoration(
                              labelText: 'Şifre',
                              prefixIcon: const Icon(Icons.lock_outline),
                              constraints: const BoxConstraints(minHeight: BpSize.tapLg),
                              suffixIcon: IconButton(
                                tooltip: _obscurePassword ? 'Şifreyi göster' : 'Şifreyi gizle',
                                icon: Icon(
                                  _obscurePassword ? Icons.visibility_off_outlined : Icons.visibility_outlined,
                                ),
                                onPressed: () => setState(() => _obscurePassword = !_obscurePassword),
                              ),
                            ),
                          ),
                          if (_feedback != null) ...[const SizedBox(height: BpSize.s4), _feedbackBox(c, _feedback!)],
                          const SizedBox(height: BpSize.s4),
                          FilledButton(
                            onPressed: (_isLoading || _locked) ? null : _login,
                            child: _isLoading
                                ? SizedBox(
                                    height: 24,
                                    width: 24,
                                    child: CircularProgressIndicator(color: c.primary, strokeWidth: 2.5),
                                  )
                                : Text(
                                    _locked
                                        ? 'Lütfen bekleyin · ${formatCountdown(Duration(seconds: _lockLeft))}'
                                        : 'Giriş yap',
                                    style: const TextStyle(fontSize: 18),
                                  ),
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
