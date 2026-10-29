package com.bilpark.backend.service;

import com.bilpark.backend.audit.AuditService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.common.PlateNormalizer;
import com.bilpark.backend.fee.DaySegment;
import com.bilpark.backend.fee.FeeBreakdown;
import com.bilpark.backend.fee.FeeEngine;
import com.bilpark.backend.fee.TariffRules;
import com.bilpark.backend.model.AuditAction;
import com.bilpark.backend.model.CheckInRequest;
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
import com.bilpark.backend.model.VehicleTypes;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.repository.CheckInRequestRepository;
import com.bilpark.backend.repository.ParkSpotRepository;
import com.bilpark.backend.repository.ParkingRecordRepository;
import com.bilpark.backend.repository.SubscriptionRepository;
import com.bilpark.backend.repository.ZoneRepository;
import com.bilpark.backend.sync.ChangePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service // İş mantığını yöneten sınıf | Uygulama Beyni
public class ParkingService
{
    private static final Logger log = LoggerFactory.getLogger(ParkingService.class);

    private final ParkSpotRepository parkSpotRepository; // Aktif araçlar
    private final CheckInRequestRepository checkInRequestRepository;
    private final ParkingRecordRepository parkingRecordRepository; // Muhasebe fişleri
    private final ZoneRepository zoneRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TariffService tariffService; // geçerli tarife
    private final ChangePublisher changePublisher; // canlı olaylar
    private final AuditService auditService;
    private final Clock clock;
    private final VehicleTypeService vehicleTypeService;

    public ParkingService(ParkSpotRepository parkSpotRepository,
                          ParkingRecordRepository parkingRecordRepository,
                          ZoneRepository zoneRepository,
                          SubscriptionRepository subscriptionRepository,
                          TariffService tariffService,
                          ChangePublisher changePublisher,
                          AuditService auditService,
                          Clock clock,
                          VehicleTypeService vehicleTypeService,
                          CheckInRequestRepository checkInRequestRepository)
    {
        this.checkInRequestRepository = checkInRequestRepository;
        this.vehicleTypeService = vehicleTypeService;
        this.parkSpotRepository=parkSpotRepository;
        this.parkingRecordRepository=parkingRecordRepository;
        this.zoneRepository = zoneRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tariffService = tariffService;
        this.changePublisher = changePublisher;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Result of a runaway report: the record plus how its debt was built. */
    public record RunawayResult(ParkingRecord record, List<DaySegment> segments) {}

    public List<ParkSpot> getAllSpots()
    {
        return withSubscriptionAndDebt(parkSpotRepository.findAll());
    }

    // Listelerde @Transient alanları doldurur: abonelik ve kaçak borç, tüm liste için iki sorgu (N+1 yok)
    private List<ParkSpot> withSubscriptionAndDebt(List<ParkSpot> spots) {
        if (spots.isEmpty()) return spots;
        List<String> plates = spots.stream().map(ParkSpot::getCurrentPlate).distinct().toList();
        Set<String> subscribed = new HashSet<>(
                subscriptionRepository.findCurrentlyActivePlates(plates, LocalDate.now(clock)));
        Map<String, Double> debts = new HashMap<>();
        for (Object[] row : parkingRecordRepository.sumUnpaidRunawayDebtByPlate(plates)) {
            debts.put((String) row[0], row[1] == null ? 0.0 : ((Number) row[1]).doubleValue());
        }
        for (ParkSpot s : spots) {
            s.setHasSubscription(subscribed.contains(s.getCurrentPlate()));
            s.setAccumulatedDebt(debts.getOrDefault(s.getCurrentPlate(), 0.0));
        }
        return spots;
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
        return checkInVehicle(licensePlate, vehicleType, street, side, zoneId, actor, null);
    }

    private static final java.util.regex.Pattern REQUEST_ID = java.util.regex.Pattern.compile("[A-Za-z0-9-]{8,64}");

    // checkInRequestId != null: idempotent giriş (çevrimdışı kuyruk tekrarı)
    @Transactional
    public ParkSpot checkInVehicle(String licensePlate,String vehicleType,StreetLocation street,String side, Long zoneId, User actor,
                                   String checkInRequestId)
    {
        return checkInVehicle(licensePlate, vehicleType, street, side, zoneId, actor, checkInRequestId, null);
    }

    // İstemci saatine en fazla bu kadar ileri kayma tanınır (sunucu saatine kırpılır)
    static final Duration CLIENT_TIME_FUTURE_TOLERANCE = Duration.ofMinutes(5);
    // Bundan eski istemci saati kabul edilmez (sunucu saati kullanılır)
    static final Duration CLIENT_TIME_MAX_AGE = Duration.ofHours(24);

    /** Kayda yazılan giriş saati ve kaynağı (CLIENT: memurun cihazı, SERVER: isteğin ulaştığı an). */
    record EntryTime(LocalDateTime time, String source) {}

    // clientEntryTime: ISO-8601, ofsetli (ör. 2026-10-29T08:15:00Z). Yalnız [şimdi-24s, şimdi+5dk] aralığında kabul edilir;
    // aralık dışı ya da okunamayan değer girişi reddetmez, sunucu saati kullanılır (veri kaybı yerine bugünkü davranış).
    EntryTime resolveEntryTime(String clientEntryTime, LocalDateTime now) {
        if (clientEntryTime == null || clientEntryTime.isBlank()) return new EntryTime(now, "SERVER");
        LocalDateTime client;
        try {
            client = OffsetDateTime.parse(clientEntryTime.trim())
                    .atZoneSameInstant(clock.getZone()).toLocalDateTime();
        } catch (DateTimeParseException e) {
            log.warn("Unparseable clientEntryTime '{}', using server time", clientEntryTime);
            return new EntryTime(now, "SERVER");
        }
        if (client.isAfter(now.plus(CLIENT_TIME_FUTURE_TOLERANCE))) {
            log.warn("clientEntryTime {} is in the future (server {}), using server time", client, now);
            return new EntryTime(now, "SERVER");
        }
        if (client.isBefore(now.minus(CLIENT_TIME_MAX_AGE))) {
            log.warn("clientEntryTime {} is older than {} (server {}), using server time", client, CLIENT_TIME_MAX_AGE, now);
            return new EntryTime(now, "SERVER");
        }
        // Tolerans içindeki küçük ileri kayma: giriş gelecekte olamaz
        return new EntryTime(client.isAfter(now) ? now : client, "CLIENT");
    }

    // clientEntryTime != null: çevrimdışı kuyruktan gelen girişin memur cihazındaki saati
    @Transactional
    public ParkSpot checkInVehicle(String licensePlate,String vehicleType,StreetLocation street,String side, Long zoneId, User actor,
                                   String checkInRequestId, String clientEntryTime)
    {
        if (checkInRequestId != null && !REQUEST_ID.matcher(checkInRequestId).matches()) {
            throw ApiException.badRequest(ErrorCodes.INVALID_REQUEST_ID,
                    "Geçersiz istek kimliği (8-64 karakter, harf/rakam/tire).");
        }
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

        // Tekrar: aynı istek kimliği daha önce işlendiyse (bölge yetkisi yukarıda doğrulandı)
        if (checkInRequestId != null) {
            Optional<CheckInRequest> prior = checkInRequestRepository.findById(checkInRequestId);
            if (prior.isPresent()) {
                Long spotId = prior.get().getParkSpotId();
                Optional<ParkSpot> live = spotId == null ? Optional.empty() : parkSpotRepository.findById(spotId);
                if (live.isPresent() && live.get().getCurrentPlate().equals(prior.get().getPlate())) {
                    return withSubscriptionAndDebt(List.of(live.get())).get(0);
                }
                throw ApiException.conflict(ErrorCodes.DUPLICATE_REQUEST, "Bu giriş daha önce işlendi.");
            }
        }

        // A. Çifte kayıt engeli: aktif plaka TÜM sistemde tekil
        Optional<ParkSpot> existingVehicle = parkSpotRepository.findByPlateForUpdate(plate);
        if (existingVehicle.isPresent()) {
            throw alreadyParked(plate, existingVehicle.get());
        }

        // B. Araç tipi
        // Boş gelirse SMALL; bilinmeyen veya pasif tip 400
        String type = (vehicleType == null || vehicleType.isBlank())
                ? VehicleTypes.SMALL
                : vehicleType.trim().toUpperCase(Locale.ROOT);
        vehicleTypeService.requireActive(type);

        Zone zone = zoneId != null ? zoneRepository.findById(zoneId).orElse(null) : null;

        ParkSpot newSpot = new ParkSpot(plate, street, type, "Merkez", "Bilecik", side, zone);
        EntryTime entry = resolveEntryTime(clientEntryTime, LocalDateTime.now(clock));
        newSpot.setEntryTime(entry.time());

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
        if (checkInRequestId != null) {
            try {
                checkInRequestRepository.saveAndFlush(new CheckInRequest(
                        checkInRequestId, plate, result.getId(), LocalDateTime.now(clock)));
            } catch (DataIntegrityViolationException e) {
                // Aynı kimlikle eşzamanlı ikinci istek; bu işlem geri alınır
                log.info("Concurrent duplicate check-in request id {}", checkInRequestId);
                throw ApiException.conflict(ErrorCodes.ALREADY_PARKED,
                        plate + " plakalı araç zaten sistemde aktif.");
            }
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("plate", plate);
        after.put("zoneId", zoneIdOf(result));
        after.put("street", street != null ? street.name() : null);
        after.put("vehicleType", type);
        after.put("entryTime", entry.time().toString());
        after.put("entryTimeSource", entry.source());
        if (clientEntryTime != null) after.put("clientEntryTime", clientEntryTime);
        auditService.log(AuditAction.VEHICLE_CHECK_IN, "ParkSpot", result.getId(),
                plate + " giriş: " + (zone != null ? zone.getZoneName() : (street != null ? street.getDisplayName() : "-")),
                null, after);
        publishEntered(result);
        return result;
    }

    private void publishEntered(ParkSpot spot) {
        Map<String, Object> p = new HashMap<>();
        p.put("plate", spot.getCurrentPlate());
        p.put("vehicleType", spot.getCurrentType());
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

        Map<String, Object> aud = new LinkedHashMap<>();
        aud.put("plate", record.getLicensePlate());
        aud.put("fee", fee);
        aud.put("paymentMethod", effective.name());
        aud.put("zoneId", zoneIdOf(spot));
        auditService.log(AuditAction.VEHICLE_CHECK_OUT, "ParkingRecord", record.getId(),
                record.getLicensePlate() + " çıkış: " + money(fee) + " TL (" + effective.name() + ")", null, aud);

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

        Map<String, Object> aud = new LinkedHashMap<>();
        aud.put("plate", record.getLicensePlate());
        aud.put("debt", debt);
        auditService.log(AuditAction.VEHICLE_RUNAWAY, "ParkingRecord", record.getId(),
                record.getLicensePlate() + " kaçak: " + money(debt) + " TL borç", null, aud);

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
            String what = r.getStatus() == ParkingStatus.RUNAWAY ? "Kaçak"
                    : r.getStatus() == ParkingStatus.CANCELLED ? "İptal" : "Ödendi";
            String time = r.getExitTime().format(DateTimeFormatter.ofPattern("HH:mm"));
            return ApiException.conflict(ErrorCodes.ALREADY_PROCESSED,
                    plate + " az önce başka bir işlemle kapatıldı (" + what + ", " + time + ").");
        }
        return ApiException.notFound(ErrorCodes.VEHICLE_NOT_FOUND,
                plate + " plakalı araç aktif otoparkta bulunamadı.");
    }


    // --- CANCELLATION (soft delete: the row stays, status = CANCELLED) ---

    private static String money(double v) {
        return String.format(Locale.forLanguageTag("tr-TR"), "%.2f", v);
    }

    private static String cleanReason(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.length() < 5 || r.length() > 500) {
            throw ApiException.badRequest(ErrorCodes.VALIDATION_ERROR,
                    "İptal gerekçesi zorunludur (en az 5 karakter).");
        }
        return r;
    }

    /** Admin: cancels a closed (PAID / RUNAWAY) record. Leaves revenue / unpaid debt, the row is kept. */
    @Transactional
    public Map<String, Object> cancelRecord(Long id, String reason, User admin) {
        String why = cleanReason(reason);
        ParkingRecord r = parkingRecordRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Kayıt bulunamadı: " + id));
        if (r.getStatus() == ParkingStatus.CANCELLED) {
            throw ApiException.conflict(ErrorCodes.ALREADY_CANCELLED, "Bu kayıt zaten iptal edilmiş.");
        }
        if (r.getStatus() != ParkingStatus.PAID && r.getStatus() != ParkingStatus.RUNAWAY) {
            throw ApiException.badRequest(ErrorCodes.BAD_REQUEST, "Yalnızca ödenmiş veya kaçak kayıtlar iptal edilebilir.");
        }
        ParkingStatus previous = r.getStatus();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", previous.name());
        before.put("fee", r.getFee());

        r.setCancelledFromStatus(previous);
        r.setStatus(ParkingStatus.CANCELLED);
        r.setCancelledAt(LocalDateTime.now(clock));
        r.setCancelledByUsername(admin != null ? admin.getUsername() : null);
        r.setCancelReason(why);
        parkingRecordRepository.saveAndFlush(r);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "CANCELLED");
        after.put("reason", why);
        auditService.log(AuditAction.RECORD_CANCELLED, "ParkingRecord", r.getId(),
                r.getLicensePlate() + " kaydı iptal edildi (" + previous.name() + ", "
                        + money(r.getFee() != null ? r.getFee() : 0.0) + " TL): " + why, before, after);

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("recordId", r.getId());
        ev.put("plate", r.getLicensePlate());
        ev.put("previousStatus", previous.name());
        ev.put("fee", r.getFee());
        changePublisher.zoneEvent(SyncEventType.RECORD_CANCELLED, zoneIdOf(r), r.getStreet(), ev);
        return recordMap(r);
    }

    /** Officer (own zone) or admin: cancels a wrong ACTIVE entry; a CANCELLED record is written, the spot removed. */
    @Transactional
    public Map<String, Object> cancelActiveEntry(String plate, String reason, User actor, String deviceId) {
        String why = cleanReason(reason);
        String p = PlateNormalizer.forLookup(plate);
        ParkSpot spot = parkSpotRepository.findByPlateForUpdate(p)
                .orElseThrow(() -> notActive(p));
        checkSpotAccess(actor, spot);

        LocalDateTime now = LocalDateTime.now(clock);
        ParkingRecord record = newRecord(spot, now);
        record.setFee(0.0);
        record.setStatus(ParkingStatus.CANCELLED);
        record.setCancelledFromStatus(ParkingStatus.ACTIVE);
        record.setCancelledAt(now);
        record.setCancelledByUsername(actor != null ? actor.getUsername() : null);
        record.setCancelReason(why);
        stampCollector(record, actor, deviceId);
        parkingRecordRepository.save(record);
        parkSpotRepository.delete(spot);
        parkSpotRepository.flush();

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", "ACTIVE");
        before.put("plate", record.getLicensePlate());
        before.put("zoneId", zoneIdOf(spot));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "CANCELLED");
        after.put("reason", why);
        auditService.log(AuditAction.SPOT_CANCELLED, "ParkingRecord", record.getId(),
                record.getLicensePlate() + " giriş kaydı iptal edildi: " + why, before, after);

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("plate", record.getLicensePlate());
        ev.put("recordId", record.getId());
        ev.put("zoneId", zoneIdOf(spot));
        ev.put("street", spot.getStreet() != null ? spot.getStreet().name() : null);
        changePublisher.zoneEvent(SyncEventType.VEHICLE_CANCELLED, zoneIdOf(spot), spot.getStreet(), ev);
        return recordMap(record);
    }

    /** Cancelled records by cancelledAt desc, as response maps (lazy zone is read inside the transaction). */
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> listCancelled(LocalDateTime from, LocalDateTime toExclusive, int page, int size) {
        return parkingRecordRepository.findCancelled(from, toExclusive, PageRequest.of(page, size))
                .map(this::recordMap);
    }

    private Long zoneIdOf(ParkingRecord r) {
        return r.getZone() != null ? r.getZone().getId() : null;
    }

    public Map<String, Object> recordMap(ParkingRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("plate", r.getLicensePlate());
        m.put("street", r.getStreet() != null ? r.getStreet().name() : null);
        m.put("zoneId", zoneIdOf(r));
        m.put("zoneName", r.getZone() != null ? r.getZone().getZoneName() : null);
        m.put("entryTime", r.getEntryTime());
        m.put("exitTime", r.getExitTime());
        m.put("fee", r.getFee());
        m.put("vehicleType", r.getVehicleType());
        m.put("paymentMethod", r.getPaymentMethod() != null ? r.getPaymentMethod().name() : null);
        m.put("previousStatus", r.getCancelledFromStatus() != null ? r.getCancelledFromStatus().name() : null);
        m.put("status", r.getStatus() != null ? r.getStatus().name() : null);
        m.put("cancelledAt", r.getCancelledAt());
        m.put("cancelledBy", r.getCancelledByUsername());
        m.put("cancelReason", r.getCancelReason());
        m.put("collectedBy", r.getCollectedByUsername());
        return m;
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
        return withSubscriptionAndDebt(parkSpotRepository.findByStreet(street));
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
        body.put("vehicleType", spot.getCurrentType());
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

    // Ciro dağılımı: görünen ad -> ciro (etkin tipler + cirosu olan pasif tipler, sırayla)
    public Map<String, Double> calculateIncomeByVehicleType(){
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map<String, Object> d : incomeByVehicleTypeDetails()) {
            result.put((String) d.get("displayName"), (Double) d.get("income"));
        }
        return result;
    }

    // Aynı dağılımın ayrıntısı: [{code, displayName, iconKey, active, income, records}]
    @Transactional(readOnly = true)
    public List<Map<String, Object>> incomeByVehicleTypeDetails(){
        Map<String, double[]> sums = new HashMap<>(); // code -> {income, records}
        for (Object[] row : parkingRecordRepository.sumPaidIncomeByVehicleType()) {
            String code = row[0] == null ? VehicleTypes.SMALL : (String) row[0]; // tip null = eski kayıt = SMALL
            double[] acc = sums.computeIfAbsent(code, k -> new double[2]);
            acc[0] += row[1] == null ? 0.0 : ((Number) row[1]).doubleValue();
            acc[1] += row[2] == null ? 0.0 : ((Number) row[2]).doubleValue();
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (VehicleType t : vehicleTypeService.all()) {
            double[] acc = sums.get(t.getCode());
            if (!t.isActive() && acc == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", t.getCode());
            m.put("displayName", t.getDisplayName());
            m.put("iconKey", t.getIconKey());
            m.put("active", t.isActive());
            m.put("income", acc != null ? acc[0] : 0.0);
            m.put("records", acc != null ? (long) acc[1] : 0L);
            out.add(m);
        }
        return out;
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
