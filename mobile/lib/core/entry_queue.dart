// Çevrimdışı giriş kuyruğu. Yalnızca ARAÇ GİRİŞİ bekletilir (ödeme/kaçak/iptal asla).
// Her giriş istemci tarafında bir UUID taşır (checkInRequestId): istek sunucuya ulaşıp yanıt kaybolsa bile
// aynı kayıt tekrar gönderildiğinde sunucu çift kayıt açmaz. Kuyruk SharedPreferences'ta kalıcıdır.

import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'api_client.dart';
import 'constants.dart';

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
  final DateTime createdAt;

  PendingEntry({
    required this.requestId,
    required String plate,
    required this.street,
    required this.type,
    required this.side,
    this.zoneId,
    this.username,
    DateTime? createdAt,
  })  : plate = normalizePlate(plate),
        createdAt = createdAt ?? DateTime.now();

  Map<String, dynamic> toJson() => {
        'requestId': requestId,
        'plate': plate,
        'street': street,
        'type': type,
        'side': side,
        if (zoneId != null) 'zoneId': zoneId,
        if (username != null) 'username': username,
        'createdAt': createdAt.toIso8601String(),
      };

  factory PendingEntry.fromJson(Map<String, dynamic> j) => PendingEntry(
        requestId: j['requestId'] as String,
        plate: j['plate'] as String,
        street: j['street'] as String,
        type: j['type'] as String,
        side: j['side'] as String,
        zoneId: (j['zoneId'] as num?)?.toInt(),
        username: j['username'] as String?,
        createdAt: DateTime.tryParse((j['createdAt'] as String?) ?? ''),
      );

  /// POST /parking/check-in sorgu parametreleri.
  Map<String, String> toQuery() => {
        'plate': plate,
        'street': street,
        'type': type,
        'side': side,
        if (zoneId != null) 'zoneId': zoneId.toString(),
        'checkInRequestId': requestId,
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

  final Future<dynamic> Function(Map<String, String> query) _send;

  EntryQueue({Future<dynamic> Function(Map<String, String> query)? send})
      : _send = send ?? ((q) => ApiClient.post('/parking/check-in', query: q));

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

  /// Sırayla gönderir. Eşzamanlı ikinci çağrı süren gönderimi bekler (hiçbir kayıt iki kez gitmez).
  Future<void> flush() => _flushing ??= _doFlush().whenComplete(() => _flushing = null);

  Future<void> _doFlush() async {
    for (final e in List<PendingEntry>.from(items.value)) {
      final user = AppConstants.currentUser;
      if (user != null && e.username != null && e.username != user) continue; // başka kullanıcının kaydı bekler
      try {
        final body = await _send(e.toQuery());
        await remove(e.requestId);
        _emit(EntrySendResult(EntryResultKind.sent, e, body: body));
      } on ApiException catch (ex) {
        final s = ex.statusCode;
        if (s == null || s >= 500 || s == 401) return; // ağ/sunucu/oturum: kayıtlar kalır, sonra denenir
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
