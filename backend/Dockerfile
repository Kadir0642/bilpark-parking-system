# 1. AŞAMA: Derleme (Multi-Stage Build) | Bu bir Multi-Stage (Çok Aşamalı) Dockerfile' | Portability: bilgisayarda Maven yüklü olmasa bile, sadece  Docker bulunması, bu projeyi derleyip çalıştırmam için yeterlidir.
# Projemizi Maven ile derleyip çalıştırılabilir bir JAR dosyası üretiyoruz | Amacım, uygulamayı derlemek için gereken ağır araçları (Maven, Kaynak Kodlar, JDK) nihai canlı sunucuya (Production) taşımamak.
# Böylece hem imaj boyutunu (Image Size) inanılmaz derecede küçültüyoruz hem de güvenlik açıklarını (Security Surface) en aza indiririz.
FROM maven:3.9.6-eclipse-temurin-21 AS build
#İçinde hazır bir Linux işletim sistemi, Java 21 (JDK) ve Maven yüklü olan devasa bir "Temel İmaj" aldık ve bu aşamaya build adını verdik.
WORKDIR /app
#Container (kapsayıcı) içinde kendimize /app diye dosya oluştururuz.
COPY pom.xml .
COPY src ./src
#pc deki ham malzemeleri (Java kaynak kodları ve kütüphane listesini içeren pom.xml) Docker içine kopyalıyoruz.
RUN mvn clean package -DskipTests
#Maven komutunu çalıştırarak kodumuzu derliyor ve bir .jar(Çalıştırılabilir Java Arşivi) dosyası üretiyoruz.
#-DskipTests ile testleri atlıyoruz ki imaj oluşturma süreci hızlı bitsin (Normalde testler CI/CD pipeline'ında ayrı koşulur.)

# 2. AŞAMA: Çalıştırma (Run)
# Sadece derlenmiş dosyayı alıp gereksiz ağırlıklardan kurtuluyoruz (Microservice mantığı)
FROM eclipse-temurin:21-jre-alpine
# ikinci aşamada artık JDK değil, JRE(Runtime Environment) kullanırız.Çünkü kod zaten derlendi, artık sadece çalıştırmamız gerekiyor.
#alpine sürümünü seçtik (kaynak kodunu görünmez, sadece derlenmiş makine kodu görülür.) sadece 5MB boyutu ve gereksiz her şeyden arındırılmış ultra hafif bir Linux dağıtımıdır.
# Bu sayede 500 MB'lık bir Docker imajı yerine 50-60MB'lık bir imaj elde ederiz ve sunucu maliyetlerini düşürmüş oluruz.
# Sadece çalıştırmak için gereken dosyaları barındırdığı için imaj boyutu GB'lardan MB'lara düşer. Uygulama saniyeler içinde ayağa kalkar.
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# " --from=build" kısmı 1. aşamaya gider ve .jar dosyasını alır ve adını "app.jar" yapar.
# 1.aşamadaki tüm kaynak kodlar ve Maven araçları çöpe gitti (final imajında yer kaplamayacak)
EXPOSE 8080
# 8080 portunda çalışacak.
ENTRYPOINT ["java", "-jar", "app.jar"]
# Konteynar(Run ettiğimiz canlı hali) ayağa kalktığında anda ilk ve tek çalışacak komut javayı ve bu JAR dosyasını çalıştır olucak.