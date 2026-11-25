# ML Kit metin tanıma eklentisi (google_mlkit_text_recognition) Çince, Devanagari,
# Japonca ve Korece tanıyıcı sınıflarına referans veriyor. Biz yalnızca Latin
# alfabesini kullanıyoruz ve bu paketleri bağımlılık olarak eklemedik; R8'in
# "Missing class" hatasıyla derlemeyi düşürmemesi için uyarıları susturuyoruz.
# Latin tanıyıcı (com.google.mlkit.vision.text.latin) etkilenmez.

# Çince tanıyıcı (kullanılmıyor)
-dontwarn com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions$Builder
-dontwarn com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

# Devanagari tanıyıcı (kullanılmıyor)
-dontwarn com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions$Builder
-dontwarn com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions

# Japonca tanıyıcı (kullanılmıyor)
-dontwarn com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions$Builder
-dontwarn com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

# Korece tanıyıcı (kullanılmıyor)
-dontwarn com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions$Builder
-dontwarn com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

# --- ML Kit bileşen kayıtçıları (R8 tam mod) ---
# Manifest'teki MlKitComponentDiscoveryService, meta-data'da adı yazan
# ComponentRegistrar sınıflarını (TextRegistrar, VisionCommonRegistrar,
# CommonComponentRegistrar) yansıma ile örnekler:
# Class.forName(..).getDeclaredConstructor().newInstance().
# firebase-components'in kendi kuralı yalnızca sınıfı korur
# ("-keep class * implements ComponentRegistrar"); R8 tam modda (AGP 8+ varsayılanı)
# kullanılmayan varsayılan yapıcı yine silinir. Sonuç: kayıtçılar örneklenemez, ML Kit
# bileşenleri kaydolmaz ve TextRecognizer hiçbir şey okuyamaz (yalnızca release'te;
# debug'da R8 yok). Yapıcıyı açıkça koruyoruz.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}
