// Giriş ekranında gösterilecek sakin, anlaşılır mesajlar (yaşlı kullanıcılar da okuyacak).
// Sunucunun `error` metni olduğu gibi gösterilir; yoksa yerel, sakin bir Türkçe metin kullanılır.

import 'api_client.dart';

/// Giriş denemesinin sonucu: ana mesaj, varsa küçük ipucu ve varsa bekleme süresi.
class LoginFeedback {
  final String message;
  final String? hint;

  /// Dolu ise düğme bu süre kadar kapalı kalır ve geri sayım gösterilir.
  final Duration? wait;

  const LoginFeedback(this.message, {this.hint, this.wait});
}

/// 429'da sunucu bekleme süresi vermezse kullanılan varsayılan.
const Duration defaultLoginWait = Duration(seconds: 60);

const String loginWrongMessage = 'Kullanıcı adı veya şifre hatalı.';
const String loginForgotHint = 'Şifrenizi hatırlamıyorsanız yöneticinize başvurun.';
const String loginLimitedMessage = 'Çok fazla deneme yapıldı. Lütfen biraz bekleyip tekrar deneyin.';
const String loginBusyMessage = 'Sunucu şu an yoğun. Lütfen birazdan tekrar deneyin.';
const String loginUnexpectedMessage = 'Giriş yapılamadı. Lütfen birazdan tekrar deneyin.';

/// [ApiException]'ı giriş ekranı mesajına çevirir.
LoginFeedback loginFeedbackFor(ApiException e) {
  if (e.isLoginLimited) {
    // Süre bildirilmediyse de kilit arayüzü başlar (varsayılan 60 sn).
    final wait = e.retryAfter;
    return LoginFeedback(
      e.message.isEmpty ? loginLimitedMessage : e.message,
      wait: wait != null && wait > Duration.zero ? wait : defaultLoginWait,
    );
  }
  if (e.isUnauthorized) {
    // Girişte gelen 401: sunucu metni; gövde yoksa genel "oturum süresi doldu" metni yerine yerel metin.
    final msg = e.message.isEmpty || e.message == ApiClient.sessionExpiredMessage ? loginWrongMessage : e.message;
    return LoginFeedback(msg, hint: e.remainingAttempts != null ? loginForgotHint : null);
  }
  if (e.statusCode == 503 || e.statusCode == 429) return const LoginFeedback(loginBusyMessage);
  return LoginFeedback(e.message.isEmpty ? loginUnexpectedMessage : e.message);
}

/// 75 sn -> "1:15", 45 sn -> "0:45".
String formatCountdown(Duration d) {
  final total = d.inSeconds < 0 ? 0 : d.inSeconds;
  final m = total ~/ 60;
  final s = total % 60;
  return '$m:${s.toString().padLeft(2, '0')}';
}
