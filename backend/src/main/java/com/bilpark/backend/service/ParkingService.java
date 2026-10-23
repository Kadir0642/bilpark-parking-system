package com.bilpark.backend.service;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.common.PlateNormalizer;
import com.bilpark.backend.fee.DaySegment;
import com.bilpark.backend.fee.FeeBreakdown;
import com.bilpark.backend.fee.FeeEngine;
import com.bilpark.backend.fee.TariffRules;
import com.bilpark.backend.model.ParkSpot;
import com.bilpark.backend.model.ParkingRecord;
import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.model.PaymentMethod;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.model.Subscription;
import com.bilpark.backend.model.SyncEventType;
import com.bilpark.backend.model.Tariff;
import com.bilpark.backend.model.SubscriptionStatus;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.VehicleType;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.ParkSpotRepository;
import com.bilpark.backend.repository.ParkingRecordRepository;
import com.bilpark.backend.repository.SubscriptionRepository;
import com.bilpark.backend.repository.ZoneRepository;
import com.bilpark.backend.sync.ChangePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service // İş mantığını yöneten sınıf | Uygulama Beyni
public class ParkingService
{
    private static final Logger log = LoggerFactory.getLogger(ParkingService.class);

    private final ParkSpotRepository parkSpotRepository; // Aktif araçlar
    private final ParkingRecordRepository parkingRecordRepository; // Muhasebe fişleri
    private final ZoneRepository zoneRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TariffService tariffService; // geçerli tarife
    private final ChangePublisher changePublisher; // canlı olaylar
    private final Clock clock;

    public ParkingService(ParkSpotRepository parkSpotRepository,
                          ParkingRecordRepository parkingRecordRepository,
                          ZoneRepository zoneRepository,
                          SubscriptionRepository subscriptionRepository,
                          TariffService tariffService,
                          ChangePublisher changePublisher,
                          Clock clock)
    {
        this.parkSpotRepository=parkSpotRepository;
        this.parkingRecordRepository=parkingRecordRepository;
        this.zoneRepository = zoneRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tariffService = tariffService;
        this.changePublisher = changePublisher;
        this.clock = clock;
    }

    /** Result of a runaway report: the record plus how its debt was built. */
    public record RunawayResult(ParkingRecord record, List<DaySegment> segments) {}

    public List<ParkSpot> getAllSpots()
    {
        return parkSpotRepository.findAll();
    }

    // --- CHECK-IN ---
    // Not: self-invocation proxy'yi atladığı için her public aşırı yükleme ayrıca @Transactional.
    @Transactional
    public ParkSpot checkInVehicle(String licensePlate,String vehicleType,StreetLocation street,String side, Long zoneId)
    {
        return checkInVehicle(licensePlate, vehicleType, street, side, zoneId, null);
    }

    // actor != null ise görevli bölge kısıtı uygulanır (ADMIN serbest)
    @Transactional
    public ParkSpot checkInVehicle(String licensePlate,String vehicleType,StreetLocation street,String side, Long zoneId, User actor)
    {
        String plate = PlateNormalizer.normalize(licensePlate);

        if (isOfficer(actor)) {
            Zone assigned = requireAssignedZone(actor);
            if (zoneId == null) {
                zoneId = assigned.getId();
            } else if (!zoneId.equals(assigned.getId())) {
                throw denied();
            }
            if (street != assigned.getStreet()) {
                throw denied();
            }
        }

        // A. Çifte kayıt engeli: aktif plaka TÜM sistemde tekil
        Optional<ParkSpot> existingVehicle = parkSpotRepository.findByPlateForUpdate(plate);
        if (existingVehicle.isPresent()) {
            throw alreadyParked(plate, existingVehicle.get());
        }

        // B. Araç tipi
        VehicleType type = VehicleType.SMALL; // Default
        if (vehicleType != null && !vehicleType.isEmpty()) {
            try {
                type = VehicleType.valueOf(vehicleType.toUpperCase());
            } catch (IllegalArgumentException e) {
                type = VehicleType.SMALL; // Hatalı gelirse küçük say
            }
        }

        Zone zone = zoneId != null ? zoneRepository.findById(zoneId).orElse(null) : null;

        ParkSpot newSpot = new ParkSpot(plate, street, type, "Merkez", "Bilecik", side, zone);
        newSpot.setEntryTime(LocalDateTime.now(clock));

        // Kaçak borcu
        List<ParkingRecord> unpaidRecords = parkingRecordRepository.findByLicensePlateAndStatus(plate, ParkingStatus.RUNAWAY);
        double totalDebt = unpaidRecords.stream()
                .filter(r -> !r.isDebtPaid())
                .mapToDouble(r -> r.getFee() != null ? r.getFee() : 0.0)
                .sum();
        newSpot.setAccumulatedDebt(totalDebt);

        newSpot.setHasSubscription(hasActiveSubscription(plate));

        // Fiş şimdi kesilmez; sadece çıkarken kesilir.
        ParkSpot saved;
        try {
            saved = parkSpotRepository.saveAndFlush(newSpot);
        } catch (DataIntegrityViolationException e) {
            // Eşzamanlı ikinci giriş benzersizlik kısıtına takıldı
            log.info("Concurrent check-in rejected for plate {}", plate);
            throw ApiException.conflict(ErrorCodes.ALREADY_PARKED,
                    plate + " plakalı araç zaten sistemde aktif.");
        }
        ParkSpot result = saved != null ? saved : newSpot;
        publishEntered(result);
        return result;
    }

    private void publishEntered(ParkSpot spot) {
        Map<String, Object> p = new HashMap<>();
        p.put("plate", spot.getCurrentPlate());
        p.put("vehicleType", spot.getCurrentType() != null ? spot.getCurrentType().name() : null);
        p.put("zoneId", zoneIdOf(spot));
        p.put("street", spot.getStreet() != null ? spot.getStreet().name() : null);
        p.put("side", spot.getSide());
        p.put("entryTime", spot.getEntryTime() != null ? spot.getEntryTime().toString() : null);
        p.put("hasSubscription", Boolean.TRUE.equals(spot.getHasSubscription()));
        p.put("spotId", spot.getId());
        changePublisher.zoneEvent(SyncEventType.VEHICLE_ENTERED, zoneIdOf(spot), spot.getStreet(), p);
    }

    private Long zoneIdOf(ParkSpot spot) {
        return spot.getZone() != null ? spot.getZone().getId() : null;
    }

    // --- CHECK-OUT ---
    @Transactional
    public ParkingRecord checkOutVehicle(String plate) {
        return checkOutVehicle(plate, PaymentMethod.CASH, null, null);
    }

    @Transactional
    public ParkingRecord checkOutVehicle(String plate, User actor) {
        return checkOutVehicle(plate, PaymentMethod.CASH, actor, null);
    }

    @Transactional
    public ParkingRecord checkOutVehicle(String plate, PaymentMethod method, User actor, String deviceId) {
        String p = PlateNormalizer.forLookup(plate);
        PaymentMethod requested = method == null ? PaymentMethod.CASH : method;
        if (requested == PaymentMethod.ONLINE) {
            throw ApiException.badRequest(ErrorCodes.INVALID_PAYMENT_METHOD, "Online ödeme henüz kullanılmıyor.");
        }

        ParkSpot spot = parkSpotRepository.findByPlateForUpdate(p)
                .orElseThrow(() -> notActive(p));
        checkSpotAccess(actor, spot);

        LocalDateTime now = LocalDateTime.now(clock);
        Tariff tariff = tariffService.current();
        FeeBreakdown breakdown = calculate(tariff, spot, now);

        PaymentMethod effective;
        double fee;
        if (hasActiveSubscription(p)) {
            effective = PaymentMethod.SUBSCRIPTION;
            fee = 0.0;
        } else if (requested == PaymentMethod.SUBSCRIPTION) {
            throw ApiException.badRequest(ErrorCodes.NO_SUBSCRIPTION,
                    p + " plakası için aktif abonelik yok; abonelik ile çıkış yapılamaz.");
        } else {
            effective = requested;
            fee = breakdown.fee();
        }

        ParkingRecord record = newRecord(spot, now);
        record.setTariffId(tariff.getId());
        record.setPaidMinutes((int) breakdown.paidMinutes());
        record.setFee(fee);
        record.setStatus(ParkingStatus.PAID);
        record.setPaymentMethod(effective);
        stampCollector(record, actor, deviceId);

        parkingRecordRepository.save(record);
        parkSpotRepository.delete(spot);
        parkSpotRepository.flush();

        Map<String, Object> ev = new HashMap<>();
        ev.put("plate", record.getLicensePlate());
        ev.put("recordId", record.getId());
        ev.put("zoneId", zoneIdOf(spot));
        ev.put("street", spot.getStreet() != null ? spot.getStreet().name() : null);
        ev.put("fee", fee);
        ev.put("paymentMethod", effective.name());
        ev.put("exitTime", now.toString());
        changePublisher.zoneEvent(SyncEventType.VEHICLE_EXITED, zoneIdOf(spot), spot.getStreet(), ev);
        return record;
    }

    // --- KAÇAK ARAÇ BİLDİRİMİ (Runaway) ---
    @Transactional
    public ParkingRecord markAsRunaway(String plate){
        return markAsRunaway(plate, null, null);
    }

    @Transactional
    public ParkingRecord markAsRunaway(String plate, User actor){
        return markAsRunaway(plate, actor, null);
    }

    @Transactional
    public ParkingRecord markAsRunaway(String plate, User actor, String deviceId){
        return markAsRunawayDetailed(plate, actor, deviceId).record();
    }

    @Transactional
    public RunawayResult markAsRunawayDetailed(String plate, User actor, String deviceId){
        String p = PlateNormalizer.forLookup(plate);
        ParkSpot spot = parkSpotRepository.findByPlateForUpdate(p)
                .orElseThrow(() -> notActive(p));
        checkSpotAccess(actor, spot);

        LocalDateTime now = LocalDateTime.now(clock);
        Tariff tariff = tariffService.current();
        FeeBreakdown breakdown = calculate(tariff, spot, now);
        double debt = hasActiveSubscription(p) ? 0.0 : breakdown.fee();

        ParkingRecord record = newRecord(spot, now);
        record.setTariffId(tariff.getId());
        record.setPaidMinutes((int) breakdown.paidMinutes());
        record.setFee(debt); // Ödenmemiş borç
        record.setStatus(ParkingStatus.RUNAWAY);
        record.setDebtPaid(false);
        record.setPaymentMethod(null);
        stampCollector(record, actor, deviceId);

        parkingRecordRepository.save(record);
        parkSpotRepository.delete(spot);
        parkSpotRepository.flush();

        Map<String, Object> ev = new HashMap<>();
        ev.put("plate", record.getLicensePlate());
        ev.put("recordId", record.getId());
        ev.put("zoneId", zoneIdOf(spot));
        ev.put("street", spot.getStreet() != null ? spot.getStreet().name() : null);
        ev.put("debt", debt);
        ev.put("exitTime", now.toString());
        changePublisher.zoneEvent(SyncEventType.VEHICLE_RUNAWAY, zoneIdOf(spot), spot.getStreet(), ev);
        return new RunawayResult(record, breakdown.segments());
    }

    private FeeBreakdown calculate(Tariff tariff, ParkSpot spot, LocalDateTime now) {
        return FeeEngine.calculate(TariffRules.from(tariff), spot.getCurrentType(), spot.getEntryTime(), now);
    }

    private ParkingRecord newRecord(ParkSpot spot, LocalDateTime now) {
        ParkingRecord record = new ParkingRecord(
                spot.getCurrentPlate(), spot.getStreet(), spot.getZone(), spot.getRegion(), spot.getNeighborhood(), spot.getEntryTime()
        );
        record.setExitTime(now);
        record.setVehicleType(spot.getCurrentType());
        return record;
    }

    private void stampCollector(ParkingRecord record, User actor, String deviceId) {
        if (actor != null) {
            record.setCollectedBy(actor);
            record.setCollectedByUsername(actor.getUsername());
        }
        record.setDeviceId(deviceId);
    }

    private boolean hasActiveSubscription(String plate) {
        return subscriptionRepository
                .findFirstByLicensePlateAndStatusOrderByEndDateDesc(plate, SubscriptionStatus.ACTIVE)
                .filter(Subscription::isCurrentlyActive)
                .isPresent();
    }

    private ApiException alreadyParked(String plate, ParkSpot spot) {
        String where = spot.getZone() != null ? spot.getZone().getZoneName()
                : (spot.getStreet() != null ? spot.getStreet().getDisplayName() : "bilinmeyen konum");
        return ApiException.conflict(ErrorCodes.ALREADY_PARKED,
                plate + " plakalı araç zaten sistemde aktif (" + where + ").");
    }

    // Araç aktif değil: az önce başka bir istek kapattıysa 409, hiç yoksa 404
    private ApiException notActive(String plate) {
        Optional<ParkingRecord> last = parkingRecordRepository.findTopByLicensePlateOrderByExitTimeDesc(plate);
        if (last.isPresent() && last.get().getExitTime() != null
                && last.get().getExitTime().isAfter(LocalDateTime.now(clock).minusMinutes(10))) {
            ParkingRecord r = last.get();
            String what = r.getStatus() == ParkingStatus.RUNAWAY ? "Kaçak" : "Ödendi";
            String time = r.getExitTime().format(DateTimeFormatter.ofPattern("HH:mm"));
            return ApiException.conflict(ErrorCodes.ALREADY_PROCESSED,
                    plate + " az önce başka bir işlemle kapatıldı (" + what + ", " + time + ").");
        }
        return ApiException.notFound(ErrorCodes.VEHICLE_NOT_FOUND,
                plate + " plakalı araç aktif otoparkta bulunamadı.");
    }

    // --- Görevli bölge kısıtı (actor null veya ADMIN ise serbest) ---
    private boolean isOfficer(User actor) {
        return actor != null && actor.getRole() == Role.OFFICER;
    }

    private Zone requireAssignedZone(User actor) {
        if (actor.getAssignedZone() == null) {
            throw denied();
        }
        return actor.getAssignedZone();
    }

    private AccessDeniedException denied() {
        return new AccessDeniedException("Bu bölgede işlem yetkiniz yok.");
    }

    private void checkSpotAccess(User actor, ParkSpot spot) {
        if (!isOfficer(actor)) return;
        Zone assigned = requireAssignedZone(actor);
        if (spot.getZone() != null) {
            if (!spot.getZone().getId().equals(assigned.getId())) throw denied();
        } else if (spot.getStreet() != assigned.getStreet()) { // eski kayıtlar: zone yok
            throw denied();
        }
    }

    // --- CİRO HESAPLAMA (yalnızca PAID kayıtlar) ---
    public double getTotalIncome()
    {
        Double total=parkingRecordRepository.getTotalIncome();
        return (total != null) ? total : 0.0;
    }

    public Double getDailyIncome(){
        LocalDateTime startOfDay=LocalDate.now(clock).atStartOfDay();
        LocalDateTime endOfDay=startOfDay.plusDays(1);
        return getIncomeByDateRange(startOfDay, endOfDay);
    }

    public Double getWeeklyIncome(){
        LocalDateTime end=LocalDateTime.now(clock);
        return getIncomeByDateRange(end.minusDays(7), end);
    }

    // Ayın 1'inden itibaren
    public Double getMonthlyIncome(){
        return getIncomeByDateRange(LocalDate.now(clock).withDayOfMonth(1).atStartOfDay(), LocalDateTime.now(clock));
    }

    // Yılın başından itibaren
    public Double getYearlyIncome(){
        return getIncomeByDateRange(LocalDate.now(clock).withDayOfYear(1).atStartOfDay(), LocalDateTime.now(clock));
    }

    public Double getIncomeByDateRange(LocalDateTime start, LocalDateTime end) {
        Double total = parkingRecordRepository.getIncomeByDateRange(start, end);
        return (total != null) ? total : 0.0;
    }

    // --- FİLTRELEME SERVİSLERİ ---
    public List<ParkSpot> getSpotsByStreet(StreetLocation street){
        return parkSpotRepository.findByStreet(street);
    }

    public List<ParkingRecord> getHistoryByLocation(StreetLocation street){
        return parkingRecordRepository.findTop50ByStreetOrderByEntryTimeDesc(street);
    }

    // Plaka arama: doğrulama yok, parça arama
    public List<ParkingRecord> searchHistoryByPlate(String plate){
        return parkingRecordRepository.findByLicensePlateContainingOrderByEntryTimeDesc(PlateNormalizer.clean(plate));
    }

    // --- BORÇ SORGULAMA ---
    public Map<String, Object> getDebtByPlate(String plate){
        String p = PlateNormalizer.normalize(plate);
        ParkSpot spot= parkSpotRepository.findByCurrentPlate(p)
                .orElseThrow(()->ApiException.notFound(ErrorCodes.VEHICLE_NOT_FOUND,
                        p + " plakalı araç aktif otoparkta bulunamadı."));
        return debtBody(spot);
    }

    // Görevli: kendi bölgesindeki aracın anlık ücret önizlemesi (/debt ile aynı gövde + vehicleType, zoneId)
    public Map<String, Object> getFeePreview(String plate, User actor){
        String p = PlateNormalizer.forLookup(plate);
        ParkSpot spot = parkSpotRepository.findByCurrentPlate(p)
                .orElseThrow(() -> notActive(p));
        checkSpotAccess(actor, spot);
        Map<String, Object> body = debtBody(spot);
        body.put("vehicleType", spot.getCurrentType() != null ? spot.getCurrentType().name() : null);
        body.put("zoneId", zoneIdOf(spot));
        return body;
    }

    private Map<String, Object> debtBody(ParkSpot spot) {
        String p = spot.getCurrentPlate();
        LocalDateTime now = LocalDateTime.now(clock);
        Tariff tariff = tariffService.current();
        FeeBreakdown b = FeeEngine.calculate(TariffRules.from(tariff), spot.getCurrentType(), spot.getEntryTime(), now);
        boolean isSubscribed = hasActiveSubscription(p);
        double fee = isSubscribed ? 0.0 : b.fee();

        Map<String, Object> response = new HashMap<>();
        response.put("streetName",spot.getStreet().getDisplayName());
        response.put("plate",spot.getCurrentPlate());
        response.put("entryTime",spot.getEntryTime());
        response.put("durationMinutes",b.totalMinutes());
        response.put("fee",fee);
        response.put("hasSubscription", isSubscribed);
        response.put("paidMinutes", b.paidMinutes());
        response.put("freeMinutes", b.freeMinutes());
        response.put("tariffId", tariff.getId());
        response.put("segments", DaySegment.toMaps(b.segments()));
        return response;
    }

    // --- ADMIN: ödeme yap ve çıkışı onayla (/pay) ---
    @Transactional
    public ParkingRecord processPaymentAndCheckout(String plate, User actor, String deviceId){
        return checkOutVehicle(plate, PaymentMethod.CASH, actor, deviceId);
    }

    // --- ADMIN PANELİ GRAFİKLERİ VE ANALİZ ---

    // Ciro dağılımı (SQL toplama; tip null = eski kayıt = Otomobil)
    public Map<String, Double> calculateIncomeByVehicleType(){
        double small = 0, large = 0;
        for (Object[] row : parkingRecordRepository.sumPaidIncomeByVehicleType()) {
            double v = row[1] == null ? 0.0 : ((Number) row[1]).doubleValue();
            if (row[0] == VehicleType.LARGE) large += v; else small += v;
        }
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("Otomobil", small);
        result.put("Kamyonet", large);
        return result;
    }

    // Operasyonel başarı (Kaçan/Ödenen)
    public Map<String, Long> getParkingStatusCounts(){
        long paid = 0, runaway = 0;
        for (Object[] row : parkingRecordRepository.countByStatus()) {
            long c = row[1] == null ? 0L : ((Number) row[1]).longValue();
            if (row[0] == ParkingStatus.PAID) paid += c;
            else if (row[0] == ParkingStatus.RUNAWAY) runaway += c;
        }
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("Ödenen", paid);
        result.put("Kaçan", runaway);
        return result;
    }

    // Tüm caddelerdeki son 100 işlem
    public List<ParkingRecord> getLast100Records(){
        return parkingRecordRepository.findTop100ByOrderByEntryTimeDesc();
    }

    // Tarih aralığına göre arşiv kayıtları (start <= entryTime < end)
    public List<ParkingRecord> getRecordsByDateRange(LocalDateTime start, LocalDateTime end) {
        return parkingRecordRepository.findByEntryTimeRange(start, end);
    }
}
