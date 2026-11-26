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

# Spring Boot "extract" düzeni (application/app.jar + application/lib/*.jar): sınıf veri paylaşımı (CDS) iç içe
# jar'larla çalışmaz, düz jar'lar ister. Çalıştırma aynı: java -jar application/app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination application && rm app.jar

# CDS eğitim koşusu (ücretsiz, Java 21 AppCDS): uygulama bağlamı bir kez açılıp kapanır (spring.context.exit=onRefresh),
# yüklenen sınıflar application/app.jsa arşivine yazılır. Canlıda sınıflar bu arşivden eşlenir: 0.1 CPU'da açılış kısalır.
# Veritabanına bağlanılmaz (Flyway kapalı, ddl-auto=none, JDBC meta verisi okunmaz); değerler yalnızca bu adım içindir.
# Arşiv oluşmazsa derleme yine geçer, JVM arşivsiz açılır (-Xshare:auto).
RUN DB_URL=jdbc:postgresql://127.0.0.1:1/cds DB_USERNAME=cds DB_PASSWORD=cds     JWT_SECRET=test-secret-cds-egitim-kosusu-gercek-degil-0123456789     java -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1 -XX:ArchiveClassesAtExit=application/app.jsa          -Dspring.context.exit=onRefresh -Dspring.flyway.enabled=false -Dspring.jpa.hibernate.ddl-auto=none          -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false          -Dlogging.level.root=WARN -jar application/app.jar ;     if [ -f application/app.jsa ]; then echo "CDS arşivi hazır"; else echo "UYARI: CDS arşivi oluşmadı"; fi

# JVM ayarları: Render Free 512 MB / ~0.1 CPU için (yük testi: docs/yuk-testi.md). Ortam değişkeniyle değiştirilebilir.
# - SerialGC: tek çekirdekte en az ek yük; G1'in arka plan iş parçacıkları 0.1 CPU'yu yer.
# - MaxRAMPercentage=45: 512 MB'ta ~230 MB yığın (varsayılan %25 = 128 MB yük altında tam GC'ye giriyordu);
#   kalan ~280 MB CDS paylaşımlı sınıf alanı (~110 MB), metaspace, kod önbelleği ve yerel bellek içindir.
# - Ölçüm (NMT, 30 görevli): metaspace ~30 MB, kod önbelleği ~25 MB, iş parçacığı yığınları ~2 MB.
#   MaxMetaspaceSize=192m bilerek geniş: CDS arşivi yüklenmezse bütün sınıflar metaspace'e düşer (~140 MB).
# - ReservedCodeCacheSize=48m (yalnızca C1, ~25 MB kullanılıyor) / Xss512k: yığın dışı belleği sınırlar.
# - TieredStopAtLevel=1: yalnızca C1 derleyici. 0.1 CPU'da C2 derlemesi açılıştan sonraki dakikalarda CPU'yu tüketiyordu.
# - SharedArchiveFile: yukarıdaki CDS arşivi.
# Saat dilimi değişmedi: BackendApplication.main Europe/Istanbul yapar.
ENV JDK_JAVA_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=45 -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:TieredStopAtLevel=1 -XX:SharedArchiveFile=/app/application/app.jsa"

EXPOSE 8080
# 8080 portunda çalışacak.
ENTRYPOINT ["java", "-Duser.language=en", "-Duser.country=US", "-jar", "application/app.jar"]
# Konteynar(Run ettiğimiz canlı hali) ayağa kalktığında anda ilk ve tek çalışacak komut javayı ve bu JAR dosyasını çalıştır olucak.
