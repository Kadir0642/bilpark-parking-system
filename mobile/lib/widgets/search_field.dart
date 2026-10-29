import 'package:flutter/material.dart';
import '../core/theme.dart';

/// 48 px yüksekliğinde plaka arama alanı; doluyken temizleme düğmesi çıkar.
class BpSearchField extends StatelessWidget {
  final TextEditingController controller;
  final String hint;
  final ValueChanged<String>? onChanged;
  final ValueChanged<String>? onSubmitted;
  final VoidCallback? onCleared;

  const BpSearchField({
    super.key,
    required this.controller,
    required this.hint,
    this.onChanged,
    this.onSubmitted,
    this.onCleared,
  });

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<TextEditingValue>(
      valueListenable: controller,
      builder: (context, value, _) => TextField(
        controller: controller,
        onChanged: onChanged,
        onSubmitted: onSubmitted,
        textInputAction: TextInputAction.search,
        textCapitalization: TextCapitalization.characters,
        style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
        decoration: InputDecoration(
          hintText: hint,
          isDense: true,
          constraints: const BoxConstraints(minHeight: BpSize.tap),
          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          prefixIcon: const Icon(Icons.search, size: 22),
          suffixIcon: value.text.isEmpty
              ? null
              : IconButton(
                  tooltip: 'Temizle',
                  icon: const Icon(Icons.close, size: 22),
                  onPressed: () {
                    controller.clear();
                    onChanged?.call('');
                    onCleared?.call();
                  },
                ),
        ),
      ),
    );
  }
}
