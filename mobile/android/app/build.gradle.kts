import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Release imzası: önce BILPARK_KEY_PROPERTIES ortam değişkeni, yoksa mobile/android/key.properties.
// Ortam değişkeni verilmemişse ve dosya yoksa debug anahtarına düşer (CI ve anahtarsız makineler derleyebilsin diye).
// Ortam değişkeni verilmiş ama dosya yoksa derleme hata ile durur (yanlış yol sessizce debug imzaya dönmesin).
val keyPropertiesEnv: String? = System.getenv("BILPARK_KEY_PROPERTIES")?.takeIf { it.isNotBlank() }
val keyPropertiesFile: File = keyPropertiesEnv?.let { File(it) } ?: rootProject.file("key.properties")
if (keyPropertiesEnv != null && !keyPropertiesFile.exists()) {
    throw GradleException("BILPARK_KEY_PROPERTIES bulunamadı: $keyPropertiesEnv")
}
val keyProperties = Properties()
val hasReleaseKey = keyPropertiesFile.exists()
if (hasReleaseKey) {
    keyPropertiesFile.inputStream().use { keyProperties.load(it) }
}

android {
    namespace = "com.example.mobile"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.mobile"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.

        // 👇 BURAYI DEĞİŞTİRİYORUZ:
        minSdk = flutter.minSdkVersion  // Eskiden "flutter.minSdkVersion" yazıyordu, sildik 21 yaptık.

        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                // Göreli yol, properties dosyasının klasörüne göre çözülür; mutlak yol aynen kullanılır.
                val storePath = File(keyProperties.getProperty("storeFile"))
                storeFile = if (storePath.isAbsolute) storePath else File(keyPropertiesFile.parentFile, storePath.path)
                storePassword = keyProperties.getProperty("storePassword")
                keyAlias = keyProperties.getProperty("keyAlias")
                keyPassword = keyProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "UYARI: release anahtarı bulunamadı (BILPARK_KEY_PROPERTIES / android/key.properties). " +
                        "Release derlemesi DEBUG anahtarıyla imzalanıyor; dağıtım için uygun değildir."
                )
                signingConfigs.getByName("debug")
            }
            // R8 açık kalır; eksik ML Kit dil paketleri için -dontwarn kuralları burada.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

flutter {
    source = "../.."
}
