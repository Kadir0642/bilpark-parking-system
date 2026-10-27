import 'package:flutter/material.dart';
import '../core/api_client.dart';
import '../core/theme.dart';

const List<String> cancelReasonChips = ['Plaka yanlış okundu', 'Yanlış sahaya girildi', 'Mükerrer giriş'];

/// Gerekçe 5-500 karakter olmalı; geçerliyse null, değilse hata metni döner.
String? validateCancelReason(String? text) {
  final t = (text ?? '').trim();
  if (t.length < 5) return 'Gerekçe en az 5 karakter olmalı.';
  if (t.length > 500) return 'Gerekçe en fazla 500 karakter olabilir.';
  return null;
}

/// "Yanlış giriş, iptal et" diyaloğu. [submit] gerekçeyle sunucuya gider; başarıda diyalog true ile kapanır.
/// Beklenen hatalar (doğrulama, okunur mod) diyalog içinde gösterilir, diğerleri çağırana atılır.
class CancelEntryDialog extends StatefulWidget {
  final Future<void> Function(String reason) submit;

  const CancelEntryDialog({super.key, required this.submit});

  @override
  State<CancelEntryDialog> createState() => _CancelEntryDialogState();
}

class _CancelEntryDialogState extends State<CancelEntryDialog> {
  final _controller = TextEditingController();
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final err = validateCancelReason(_controller.text);
    if (err != null) {
      setState(() => _error = err);
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.submit(_controller.text.trim());
      if (mounted) Navigator.pop(context, true);
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'VALIDATION_ERROR' || e.isReadOnly) {
        setState(() {
          _busy = false;
          _error = e.message;
        });
      } else {
        Navigator.pop(context, e);
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = 'İptal edilemedi, bağlantıyı kontrol edin.';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.bp;
    return AlertDialog(
      title: const Text('Yanlış giriş, iptal et'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Araç sahadan kaldırılır, kayıt silinmez; ücret alınmaz. Yönetici bu işlemi görür.',
                style: TextStyle(fontSize: 14, color: c.text2)),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: [
                for (final r in cancelReasonChips)
                  ActionChip(
                    label: Text(r),
                    onPressed: _busy
                        ? null
                        : () => setState(() {
                              _controller.text = r;
                              _error = null;
                            }),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _controller,
              enabled: !_busy,
              maxLength: 500,
              minLines: 2,
              maxLines: 4,
              decoration: InputDecoration(labelText: 'Gerekçe', errorText: _error, errorMaxLines: 3),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.pop(context), child: const Text('Vazgeç')),
        FilledButton(
          style: FilledButton.styleFrom(
              backgroundColor: c.danger, foregroundColor: c.onDanger, minimumSize: const Size(BpSize.tap, BpSize.tap)),
          onPressed: _busy ? null : _send,
          child: const Text('İptal et'),
        ),
      ],
    );
  }
}
