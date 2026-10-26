import 'package:flutter/material.dart';
import '../core/api_client.dart';
import '../core/constants.dart';
import '../core/theme.dart';
import '../widgets/bp_header.dart';
import '../widgets/bp_snack.dart';

/// Şifre kuralları (sunucuyla aynı; en az 4 farklı karakter ve 72 bayt sınırını sunucu denetler).
class PasswordRules {
  final bool minLength;
  final bool letterAndDigit;
  final bool noUsername;
  final bool differsFromCurrent;

  const PasswordRules(this.minLength, this.letterAndDigit, this.noUsername, this.differsFromCurrent);

  bool get ok => minLength && letterAndDigit && noUsername && differsFromCurrent;

  static PasswordRules check(String password, {String? username, String current = ''}) {
    final user = (username ?? '').trim().toLowerCase();
    return PasswordRules(
      password.length >= 8,
      RegExp(r'\p{L}', unicode: true).hasMatch(password) && RegExp(r'\d').hasMatch(password),
      user.isEmpty || !password.toLowerCase().contains(user),
      password.isNotEmpty && password != current,
    );
  }
}

class ChangePasswordScreen extends StatefulWidget {
  const ChangePasswordScreen({super.key});

  @override
  State<ChangePasswordScreen> createState() => _ChangePasswordScreenState();
}

class _ChangePasswordScreenState extends State<ChangePasswordScreen> {
  final _current = TextEditingController();
  final _new = TextEditingController();
  final _repeat = TextEditingController();
  bool _show = false;
  bool _busy = false;
  String? _currentError;
  String? _newError;
  String? _repeatError;
  String? _formError;

  @override
  void initState() {
    super.initState();
    for (final ctrl in [_current, _new, _repeat]) {
      ctrl.addListener(() => setState(() {}));
    }
  }

  @override
  void dispose() {
    _current.dispose();
    _new.dispose();
    _repeat.dispose();
    super.dispose();
  }

  PasswordRules get _rules => PasswordRules.check(_new.text, username: AppConstants.currentUser, current: _current.text);

  Future<void> _submit() async {
    setState(() {
      _currentError = _current.text.isEmpty ? 'Mevcut şifreyi girin.' : null;
      _newError = _rules.ok ? null : 'Şifre kurallara uymuyor.';
      _repeatError = _repeat.text == _new.text ? null : 'Şifreler eşleşmiyor.';
      _formError = null;
    });
    if (_currentError != null || _newError != null || _repeatError != null) return;

    setState(() => _busy = true);
    try {
      final data = await ApiClient.post('/me/password', body: {'currentPassword': _current.text, 'newPassword': _new.text});
      // Eski token geçersiz olur: yenisi saklanır
      final token = data is Map ? data['token'] : null;
      if (token is String) await ApiClient.saveToken(token);
      if (!mounted) return;
      final messenger = ScaffoldMessenger.of(context);
      final snack = bpSnackBar(context, 'Şifre değiştirildi.');
      Navigator.pop(context);
      messenger.showSnackBar(snack);
    } on ApiException catch (e) {
      if (!mounted || e.isUnauthorized) return;
      setState(() {
        switch (e.code) {
          case 'PASSWORD_MISMATCH':
            _currentError = 'Mevcut şifre hatalı.';
          case 'WEAK_PASSWORD':
            _newError = e.message;
          default:
            _formError = e.message;
        }
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    final rules = _rules;
    final hasNew = _new.text.isNotEmpty;
    return Scaffold(
      backgroundColor: c.bg,
      body: Column(
        children: [
          BpHeader(
            padding: const EdgeInsets.fromLTRB(8, 4, 16, 8),
            child: Row(
              children: [
                IconButton(tooltip: 'Geri', icon: const Icon(Icons.arrow_back), onPressed: () => Navigator.maybePop(context)),
                const Text('Şifremi değiştir', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              ],
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.all(BpSize.screenPad),
              children: [
                _field(c, 'Mevcut şifre', _current, _currentError, (_) => setState(() => _currentError = null)),
                const SizedBox(height: BpSize.s4),
                _field(c, 'Yeni şifre', _new, _newError, (_) => setState(() => _newError = null)),
                const SizedBox(height: BpSize.s4),
                _field(c, 'Yeni şifre (tekrar)', _repeat, _repeatError, (_) => setState(() => _repeatError = null)),
                const SizedBox(height: BpSize.s4),
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: c.surface,
                    border: Border.all(color: c.border),
                    borderRadius: BorderRadius.circular(BpSize.rMd),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Şifre kuralları', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: c.text2)),
                      const SizedBox(height: 8),
                      _rule(c, 'En az 8 karakter', hasNew, rules.minLength),
                      _rule(c, 'En az bir harf ve bir rakam', hasNew, rules.letterAndDigit),
                      _rule(c, 'Kullanıcı adını içermez', hasNew, rules.noUsername),
                      _rule(c, 'Mevcut şifreyle aynı değil', hasNew, rules.differsFromCurrent),
                    ],
                  ),
                ),
                if (_formError != null) ...[
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
                        Expanded(child: Text(_formError!, style: TextStyle(fontSize: 14, color: c.onDangerSoft))),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ),
          Container(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
            child: SafeArea(
              top: false,
              child: FilledButton(
                onPressed: _busy ? null : _submit,
                child: _busy
                    ? SizedBox(height: 24, width: 24, child: CircularProgressIndicator(color: c.primary, strokeWidth: 2.5))
                    : const Text('Şifreyi değiştir', style: TextStyle(fontSize: 18)),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _field(BilParkColors c, String label, TextEditingController ctrl, String? error, ValueChanged<String> onChanged) {
    return TextField(
      controller: ctrl,
      obscureText: !_show,
      enabled: !_busy,
      onChanged: onChanged,
      style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w500),
      decoration: InputDecoration(
        labelText: label,
        errorText: error,
        prefixIcon: const Icon(Icons.lock_outline),
        constraints: const BoxConstraints(minHeight: BpSize.tapLg),
        suffixIcon: IconButton(
          tooltip: _show ? 'Şifreleri gizle' : 'Şifreleri göster',
          icon: Icon(_show ? Icons.visibility_outlined : Icons.visibility_off_outlined),
          onPressed: () => setState(() => _show = !_show),
        ),
      ),
    );
  }

  Widget _rule(BilParkColors c, String text, bool started, bool ok) {
    final Color col = !started ? c.text2 : (ok ? c.success : c.danger);
    final IconData icon = !started ? Icons.radio_button_unchecked : (ok ? Icons.check_circle : Icons.cancel);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        children: [
          Icon(icon, size: 18, color: col),
          const SizedBox(width: 8),
          Expanded(child: Text(text, style: TextStyle(fontSize: 14, color: col))),
        ],
      ),
    );
  }
}
