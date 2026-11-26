// Çevrimdışı giriş kuyruğu. Yalnızca ARAÇ GİRİŞİ bekletilir (ödeme/kaçak/iptal asla).
// Her giriş istemci tarafında bir UUID taşır (checkInRequestId): istek sunucuya ulaşıp yanıt kaybolsa bile
// aynı kayıt tekrar gönderildiğinde sunucu çift kayıt açmaz. Kuyruk SharedPreferences'ta kalıcıdır.
// Giriş saati, memurun girişi yaptığı an (createdAt, UTC) olarak saklanır ve kuyruk gönderiminde
// clientEntryTime ile iletilir; böylece bağlantı kesintisi süresi ücretten düşmez.

import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'api_client.dart';
import 'backoff.dart';
import 'constants.dart';
import 'plate.dart';

/// Rastgele UUID v4 (küçük harf, tireli).
String newRequestId([Random? rng]) {
  final r = rng ?? Random.secure();
  final b = List<int>.generate(16, (_) => r.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  final h = b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
  return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}';
}

/// Plakayı sunucuya gidecek biçime getirir: büyük harf, boşluk/tire yok.
String normalizePlate(String p) => p.toUpperCase().replaceAll(RegExp(r'[\s-]'), '');

class PendingEntry {
  final String requestId;
  final String plate;
  final String street;
  final String type;
  final String side;
  final int? zoneId;
  final String? username;

  /// Yabancı / özel plaka (TR biçimine uymayan): sunucuya foreignPlate=true gider (V10).
  final bool foreign;

  /// Yabancı plakanın isteğe bağlı ülke kodu (1-3 harf A-Z, V11); yalnızca [foreign] iken tutulur.
  final String? foreignCountryCode;

  /// Memurun girişi yaptığı an (UTC).
  final DateTime createdAt;

  PendingEntry({
    required this.requestId,
    required String plate,
    required this.street,
    required this.type,
    required this.side,
    this.zoneId,
    this.username,
    this.foreign = false,
    String? foreignCountryCode,
    DateTime? createdAt,
  })  : plate = normalizePlate(plate),
        foreignCountryCode = foreign ? normalizeCountryCode(foreignCountryCode) : null,
        createdAt = (createdAt ?? DateTime.now()).toUtc();

  Map<String, dynamic> toJson() => {
        'requestId': requestId,
        'plate': plate,
        'street': street,
        'type': type,
        'side': side,
        if (zoneId != null) 'zoneId': zoneId,
        if (username != null) 'username': username,
        if (foreign) 'foreignPlate': true,
        if (foreignCountryCode != null) 'foreignCountryCode': foreignCountryCode,
        'createdAt': createdAt.toIso8601String(), // UTC, 'Z' ekli
      };

  factory PendingEntry.fromJson(Map<String, dynamic> j) => PendingEntry(
        requestId: j['requestId'] as String,
        plate: j['plate'] as String,
        street: j['street'] as String,
        type: j['type'] as String,
        side: j['side'] as String,
        zoneId: (j['zoneId'] as num?)?.toInt(),
        username: j['username'] as String?,
        foreign: j['foreignPlate'] == true, // eski sürüm kayıtlarında alan yok: TR plakası
        foreignCountryCode: parseCountryCode(j['foreignCountryCode']), // eski kayıtlarda yok: kod yok
        createdAt: DateTime.tryParse((j['createdAt'] as String?) ?? ''),
      );

  /// POST /parking/check-in sorgu parametreleri.
  /// withEntryTime: kuyruktan gönderimde memurun giriş saati eklenir (sunucu ±aralık dışını yok sayar).
  Map<String, String> toQuery({bool withEntryTime = false}) => {
        'plate': plate,
        'street': street,
        'type': type,
        'side': side,
        if (zoneId != null) 'zoneId': zoneId.toString(),
        'checkInRequestId': requestId,
        if (foreign) 'foreignPlate': 'true',
        'foreignCountryCode': ?foreignCountryCode,
        if (withEntryTime) 'clientEntryTime': createdAt.toIso8601String(),
      };
}

enum EntryResultKind { sent, conflict, rejected }

class EntrySendResult {
  final EntryResultKind kind;
  final PendingEntry entry;
  final dynamic body; // başarıda sunucu yanıtı (accumulatedDebt için)
  final String? message;
  const EntrySendResult(this.kind, this.entry, {this.body, this.message});
}

class EntryQueue {
  static const String prefsKey = 'pending_check_ins_v1';

  /// Kuyruk isteğinin zaman aşımı. 15 sn'de vazgeçilen istek sunucuda yine de işlenip sonra yeniden gönderilirdi.
  static const Duration sendTimeout = Duration(seconds: 30);

  final Future<dynamic> Function(Map<String, String> query) _send;

  /// Art arda iki kayıt gönderimi arasındaki bekleme (jitterli: bu değerin 0,5-1,5 katı). Bir kesintiden sonra
  /// bütün telefonlar kuyruklarını aynı anda boşaltınca sunucu çökmesin diye gönderim seyrektir.
  final Duration pacing;

  /// Bağlantı dönünce ilk gönderimden önceki en büyük rastgele gecikme (0 ile bu değer arası).
  final Duration startJitter;

  /// Hata sonrası yeniden deneme beklemesi: denemeye göre üstel + jitter (taban/tavan).
  final Duration retryBase;
  final Duration retryCap;

  final Random _rng;

  EntryQueue({
    Future<dynamic> Function(Map<String, String> query)? send,
    this.pacing = const Duration(milliseconds: 800),
    this.startJitter = const Duration(seconds: 20),
    this.retryBase = const Duration(seconds: 2),
    this.retryCap = const Duration(seconds: 60),
    Random? rng,
  })  : _send = send ?? ((q) => ApiClient.post('/parking/check-in', query: q, timeout: sendTimeout)),
        _rng = rng ?? Random();

  Timer? _startTimer; // bağlantı dönünce rastgele gecikmeli başlangıç
  Timer? _retryTimer; // hata sonrası üstel bekleme
  int _failStreak = 0;

  /// Başarısız turların üst üste sayısı (başarıda sıfırlanır).
  int get failStreak => _failStreak;

  /// Zamanlanmış başlangıç ya da yeniden deneme var mı?
  bool get hasScheduledFlush => _startTimer != null || _retryTimer != null;

  /// Zamanlanmış başlangıcı/yeniden denemeyi iptal eder (kuyruğun içeriği korunur).
  void cancelScheduled() {
    _startTimer?.cancel();
    _startTimer = null;
    _retryTimer?.cancel();
    _retryTimer = null;
  }

  /// Bağlantı döndü / uygulama öne geldi: kuyruk 0-[startJitter] rastgele gecikmeden sonra boşaltılır.
  /// Zaten zamanlanmış bir başlangıç ya da hata beklemesi varsa onu bozmaz. Kuyruk boşsa hiçbir şey yapmaz.
  void flushLater({Duration? maxJitter}) {
    if (items.value.isEmpty || hasScheduledFlush || _flushing != null) return;
    final delay = randomJitter(maxJitter ?? startJitter, _rng);
    _startTimer = Timer(delay, () {
      _startTimer = null;
      unawaited(flush());
    });
  }

  /// Doğrudan bir gönderim az önce başarısız oldu (ör. 503): kuyruk anında yeniden denemez, üstel bekleme +
  /// [retryAfter] ile zamanlar. Zaten zamanlanmış bir başlangıç/yeniden deneme ya da süren gönderim varsa onu bozmaz.
  void retryLater({Duration? retryAfter}) {
    if (items.value.isEmpty || hasScheduledFlush || _flushing != null) return;
    _scheduleRetry(retryAfter);
  }

  void _scheduleRetry(Duration? retryAfter) {
    _failStreak++;
    _retryTimer?.cancel();
    final wait = backoffDelay(_failStreak, base: retryBase, cap: retryCap, rng: _rng);
    final delay = retryAfter != null && retryAfter > wait ? retryAfter : wait;
    lastRetryDelay = delay;
    _retryTimer = Timer(delay, () {
      _retryTimer = null;
      unawaited(flush());
    });
  }

  /// En son zamanlanan otomatik yeniden deneme beklemesi (test için).
  Duration? lastRetryDelay;

  final ValueNotifier<List<PendingEntry>> items = ValueNotifier(const []);
  final StreamController<EntrySendResult> _results = StreamController<EntrySendResult>.broadcast();
  Stream<EntrySendResult> get results => _results.stream;

  Future<void>? _flushing;

  bool contains(String plate) {
    final p = normalizePlate(plate);
    return items.value.any((e) => e.plate == p);
  }

  Future<void> load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(prefsKey);
      if (raw == null || raw.isEmpty) return;
      final list = jsonDecode(raw);
      if (list is! List) return;
      final loaded = <PendingEntry>[];
      for (final e in list) {
        try {
          loaded.add(PendingEntry.fromJson(Map<String, dynamic>.from(e as Map)));
        } catch (_) {} // bozuk kayıt atlanır
      }
      // load sırasında eklenmiş olanlar korunur
      final cur = items.value;
      items.value = [...loaded, ...cur.where((c) => !loaded.any((l) => l.requestId == c.requestId))];
    } catch (_) {}
  }

  Future<void> _persist() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(prefsKey, jsonEncode(items.value.map((e) => e.toJson()).toList()));
    } catch (_) {}
  }

  /// false: aynı plaka zaten beklemede.
  Future<bool> enqueue(PendingEntry e) async {
    if (contains(e.plate)) return false;
    items.value = [...items.value, e];
    await _persist();
    return true;
  }

  Future<void> remove(String requestId) async {
    items.value = items.value.where((e) => e.requestId != requestId).toList();
    await _persist();
  }

  /// Sırayla (her seferinde bir kayıt, aralarında [pacing] beklemeyle) gönderir. Eşzamanlı ikinci çağrı süren
  /// gönderimi bekler (hiçbir kayıt iki kez gitmez). Ağ/5xx/429 hatasında kayıtlar KALIR (hiçbiri düşürülmez);
  /// tur durur ve üstel bekleme + jitter (503/429'da Retry-After'dan kısa olmamak üzere) sonra kendiliğinden yeniden denenir.
  Future<void> flush() {
    _startTimer?.cancel();
    _startTimer = null;
    _retryTimer?.cancel();
    _retryTimer = null;
    return _flushing ??= _doFlush().whenComplete(() => _flushing = null);
  }

  /// Tek kaydın sonucu geçici bir hata mı (kayıt kalır, yeniden denenir)?
  static bool _isTransient(int? s) => s == null || s >= 500 || s == 429 || s == 408;

  Future<void> _doFlush() async {
    var sentAny = false;
    for (final e in List<PendingEntry>.from(items.value)) {
      final user = AppConstants.currentUser;
      if (user != null && e.username != null && e.username != user) continue; // başka kullanıcının kaydı bekler
      if (!items.value.any((x) => x.requestId == e.requestId)) continue; // bu arada kaldırıldı
      if (sentAny && pacing > Duration.zero) {
        // Jitter: 0,5-1,5 x pacing. Bekleme sırasında kayıt kaldırılmış olabilir, o yüzden tekrar denetlenir.
        await Future<void>.delayed(Duration(milliseconds: (pacing.inMilliseconds * (0.5 + _rng.nextDouble())).round()));
        if (!items.value.any((x) => x.requestId == e.requestId)) continue;
      }
      try {
        final body = await _send(e.toQuery(withEntryTime: true));
        sentAny = true;
        _failStreak = 0;
        await remove(e.requestId);
        _emit(EntrySendResult(EntryResultKind.sent, e, body: body));
      } on ApiException catch (ex) {
        final s = ex.statusCode;
        if (s == 401) return; // oturum: kayıtlar kalır; giriş yapılınca / öne gelince denenir
        if (_isTransient(s)) {
          if (items.value.isNotEmpty) _scheduleRetry(ex.retryAfter);
          return; // ağ/sunucu yoğun: kayıtlar kalır, bekleyip tekrar denenir
        }
        sentAny = true; // sunucuya gitti (reddedildi): sonrakinden önce yine de bekle
        await remove(e.requestId);
        if (s == 409 && ex.code == 'DUPLICATE_REQUEST') {
          _emit(EntrySendResult(EntryResultKind.sent, e)); // zaten işlenmiş
        } else if (s == 409) {
          _emit(EntrySendResult(EntryResultKind.conflict, e, message: ex.message));
        } else {
          _emit(EntrySendResult(EntryResultKind.rejected, e, message: ex.message));
        }
      }
    }
  }

  void _emit(EntrySendResult r) {
    if (!_results.isClosed) _results.add(r);
  }
}

/// Uygulamanın tek giriş kuyruğu.
final EntryQueue entryQueue = EntryQueue();
