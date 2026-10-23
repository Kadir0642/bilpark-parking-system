# mobile

A new Flutter project.

## Getting Started

This project is a starting point for a Flutter application.

A few resources to get you started if this is your first Flutter project:

- [Lab: Write your first Flutter app](https://docs.flutter.dev/get-started/codelab)
- [Cookbook: Useful Flutter samples](https://docs.flutter.dev/cookbook)

For help getting started with Flutter development, view the
[online documentation](https://docs.flutter.dev/), which offers tutorials,
samples, guidance on mobile development, and a full API reference.

## Çalıştırma (API adresi)

API adresi `--dart-define=API_BASE=...` ile verilir.

- Yerel geliştirme (Android emulator, backend `localhost:8080`):
  `flutter run --dart-define=API_BASE=http://10.0.2.2:8080/api`
  (http yalnızca debug build'de açıktır.)
- Release build'de `API_BASE` verilmezse varsayılan olarak Render HTTPS adresi
  (`https://bilpark-api-rtdl.onrender.com/api`) kullanılır.
