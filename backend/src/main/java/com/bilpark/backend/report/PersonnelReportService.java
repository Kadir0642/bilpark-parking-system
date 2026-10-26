package com.bilpark.backend.report;

import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.model.Role;
import com.bilpark.backend.model.Shift;
import com.bilpark.backend.model.User;
import com.bilpark.backend.model.Zone;
import com.bilpark.backend.report.PerformanceScorer.Inputs;
import com.bilpark.backend.report.PerformanceScorer.Reference;
import com.bilpark.backend.settings.SettingsService;
import com.bilpark.backend.settings.SettingsService.PerformanceWeights;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/** Monthly personnel report: hours, punctuality, throughput, collection, runaways, cancellations, score. */
@Service
public class PersonnelReportService {

    private final ReportQueryRepository repo;
    private final SettingsService settings;
    private final Clock clock;

    public PersonnelReportService(ReportQueryRepository repo, SettingsService settings, Clock clock) {
        this.repo = repo;
        this.settings = settings;
        this.clock = clock;
    }

    /** Rates are fractions 0..1 here; the sheet and JSON show percent. */
    public record Row(String username, String fullName, Long zoneId, String zoneName, int workDays, int shiftCount,
                      double hours, double onTimeRate, int transactions, double throughput, double collectionTotal,
                      double collectionPerHour, double cash, double pos, int subscriptionCount, double otherMethod,
                      int runawayCount, double runawayRate, int cancelCount, double cancelRate,
                      double score, String rating) {}

    public record ShiftLine(String username, String zoneName, String deviceId, LocalDateTime start,
                            LocalDateTime end, long minutes, String onTime, boolean autoClosed) {}

    public record Report(YearMonth month, PerformanceWeights weights, List<Row> rows, List<ShiftLine> shifts) {}

    /** Running totals of the records of one officer or one zone. */
    static final class Counts {
        int txn, paid, runaway, cancel, subs;
        double paidFee, cash, pos, other;

        void add(RecordRow r, LocalDateTime from, LocalDateTime to) {
            LocalDateTime exit = r.exitTime();
            boolean exitIn = exit != null && !exit.isBefore(from) && exit.isBefore(to);
            ParkingStatus st = r.status();
            if (exitIn) {
                if (st == ParkingStatus.PAID) {
                    paid++;
                    txn++;
                    double f = r.feeOrZero();
                    paidFee += f;
                    if (r.paymentMethod() == null) {
                        other += f;
                    } else {
                        switch (r.paymentMethod()) {
                            case CASH -> cash += f;
                            case POS -> pos += f;
                            case SUBSCRIPTION -> subs++;
                            default -> other += f;
                        }
                    }
                } else if (st == ParkingStatus.RUNAWAY) {
                    runaway++;
                    txn++;
                } else if (st == ParkingStatus.CANCELLED && (r.cancelledFrom() == ParkingStatus.PAID
                        || r.cancelledFrom() == ParkingStatus.RUNAWAY)) {
                    txn++;
                }
            }
            LocalDateTime ca = r.cancelledAt();
            if (st == ParkingStatus.CANCELLED && ca != null && !ca.isBefore(from) && ca.isBefore(to)) cancel++;
        }

        double runawayRate() {
            return paid + runaway > 0 ? (double) runaway / (paid + runaway) : 0;
        }

        double cancelRate() {
            return txn + cancel > 0 ? (double) cancel / (txn + cancel) : 0;
        }
    }

    private static final class ZoneAgg {
        double hours;
        final Set<String> pairs = new HashSet<>();
        final Counts counts = new Counts();

        Reference reference() {
            return new Reference(
                    pairs.isEmpty() ? 0 : hours / pairs.size(),
                    hours > 0 ? counts.txn / hours : 0,
                    hours > 0 ? counts.paidFee / hours : 0,
                    counts.runawayRate(), counts.cancelRate());
        }
    }

    @Transactional(readOnly = true)
    public Report compute(YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        LocalDateTime winFrom = month.minusMonths(2).atDay(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now(clock);
        PerformanceWeights weights = settings.getPerformanceWeights();

        List<Shift> shifts = repo.shiftsOverlapping(winFrom, to);
        List<RecordRow> records = repo.personnelRecords(winFrom, to);
        Map<String, User> officers = new HashMap<>();
        for (User u : repo.usersByRole(Role.OFFICER)) officers.put(u.getUsername(), u);

        // ---- hours: zone reference over the 3-month window, officer hours for the report month ----
        Map<Long, ZoneAgg> zones = new HashMap<>();
        Map<Long, Zone> zoneById = new HashMap<>();
        Map<String, Double> hoursByUser = new HashMap<>();
        Map<String, Map<Long, Double>> hoursByUserZone = new HashMap<>();
        Map<String, List<Shift>> shiftsByUser = new HashMap<>();
        for (Shift s : shifts) {
            Zone z = s.getZone();
            Long zid = z == null ? null : z.getId();
            if (z != null) zoneById.put(z.getId(), z);
            for (int i = 0; i < 3; i++) {
                YearMonth m = month.minusMonths(i);
                double h = hoursIn(s, m.atDay(1).atStartOfDay(), m.plusMonths(1).atDay(1).atStartOfDay(), now);
                if (h <= 0) continue;
                ZoneAgg agg = zones.computeIfAbsent(zid, k -> new ZoneAgg());
                agg.hours += h;
                agg.pairs.add(s.getUsername() + "|" + m);
                if (i == 0) {
                    hoursByUser.merge(s.getUsername(), h, Double::sum);
                    hoursByUserZone.computeIfAbsent(s.getUsername(), k -> new HashMap<>()).merge(zid, h, Double::sum);
                }
            }
            shiftsByUser.computeIfAbsent(s.getUsername(), k -> new ArrayList<>()).add(s);
        }
        Map<String, Counts> byUser = new HashMap<>();
        for (RecordRow r : records) {
            zones.computeIfAbsent(r.zoneId(), k -> new ZoneAgg()).counts.add(r, winFrom, to);
            if (r.collectedBy() != null) byUser.computeIfAbsent(r.collectedBy(), k -> new Counts()).add(r, from, to);
        }

        // ---- officers of the month ----
        TreeSet<String> names = new TreeSet<>();
        for (Shift s : shifts) if (s.getStartTime().isBefore(to) && s.getStartTime().compareTo(from) >= 0) names.add(s.getUsername());
        for (Map.Entry<String, Counts> e : byUser.entrySet()) {
            Counts c = e.getValue();
            if (c.txn > 0 || c.cancel > 0) names.add(e.getKey());
        }
        names.retainAll(officers.keySet());

        List<Row> rows = new ArrayList<>();
        List<ShiftLine> lines = new ArrayList<>();
        for (String name : names) {
            User u = officers.get(name);
            List<Shift> mine = shiftsByUser.getOrDefault(name, List.of()).stream()
                    .filter(s -> s.getStartTime().compareTo(from) >= 0 && s.getStartTime().isBefore(to))
                    .sorted(Comparator.comparing(Shift::getStartTime).thenComparing(Shift::getId)).toList();

            // punctuality: first shift of each day vs. startTime + grace
            Map<LocalDate, Shift> firstOfDay = new TreeMap<>();
            for (Shift s : mine) firstOfDay.putIfAbsent(s.getStartTime().toLocalDate(), s);
            int onTimeDays = 0;
            for (Map.Entry<LocalDate, Shift> e : firstOfDay.entrySet()) {
                if (onTime(e.getKey(), e.getValue(), weights)) onTimeDays++;
            }
            int workDays = firstOfDay.size();
            double onTimeRate = workDays > 0 ? (double) onTimeDays / workDays : 0;

            for (Shift s : mine) {
                LocalDateTime end = s.getEndTime() != null ? s.getEndTime() : now;
                String ot = firstOfDay.get(s.getStartTime().toLocalDate()) == s
                        ? (onTime(s.getStartTime().toLocalDate(), s, weights) ? "Evet" : "Hayır") : "—";
                lines.add(new ShiftLine(name, s.getZone() != null ? s.getZone().getZoneName() : null, s.getDeviceId(),
                        s.getStartTime(), s.getEndTime(), Math.max(0, Duration.between(s.getStartTime(), end).toMinutes()),
                        ot, s.isAutoClosed()));
            }

            double hours = hoursByUser.getOrDefault(name, 0.0);
            Counts c = byUser.getOrDefault(name, new Counts());

            Long primary = null;
            double best = -1;
            for (Map.Entry<Long, Double> e : hoursByUserZone.getOrDefault(name, Map.of()).entrySet()) {
                if (e.getKey() != null && (e.getValue() > best
                        || (e.getValue() == best && primary != null && e.getKey() < primary))) {
                    best = e.getValue();
                    primary = e.getKey();
                }
            }
            Zone zone = primary != null ? zoneById.get(primary) : u.getAssignedZone();
            Long zid = zone != null ? zone.getId() : null;
            ZoneAgg agg = zid != null ? zones.get(zid) : null;
            Reference ref = agg != null ? agg.reference() : Reference.NONE;

            double throughput = hours > 0 ? c.txn / hours : 0;
            double perHour = hours > 0 ? c.paidFee / hours : 0;
            double score = PerformanceScorer.score(
                    new Inputs(hours, throughput, perHour, onTimeRate, c.runawayRate(), c.cancelRate()), ref, weights);
            rows.add(new Row(name, u.getFullName(), zid, zone != null ? zone.getZoneName() : null, workDays,
                    mine.size(), hours, onTimeRate, c.txn, throughput, c.paidFee, perHour, c.cash, c.pos, c.subs,
                    c.other, c.runaway, c.runawayRate(), c.cancel, c.cancelRate(), score,
                    PerformanceScorer.label(score)));
        }
        return new Report(month, weights, rows, lines);
    }

    private static boolean onTime(LocalDate day, Shift s, PerformanceWeights w) {
        return !s.getStartTime().isAfter(day.atTime(w.startLocalTime()).plusMinutes(w.graceMinutes()));
    }

    /** Hours of the shift inside [from, to); an open shift runs until now. */
    static double hoursIn(Shift s, LocalDateTime from, LocalDateTime to, LocalDateTime now) {
        LocalDateTime end = s.getEndTime() != null ? s.getEndTime() : now;
        LocalDateTime a = s.getStartTime().isAfter(from) ? s.getStartTime() : from;
        LocalDateTime b = end.isBefore(to) ? end : to;
        return b.isAfter(a) ? Duration.between(a, b).toSeconds() / 3600.0 : 0;
    }

    // ---- output ----------------------------------------------------------------------------------

    private static double r1(double v) { return Math.round(v * 10.0) / 10.0; }
    private static double r2(double v) { return Math.round(v * 100.0) / 100.0; }

    /** English camelCase map for the panel; rates are percent (0-100). */
    public static Map<String, Object> toMap(Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", r.username());
        m.put("fullName", r.fullName());
        m.put("zoneId", r.zoneId());
        m.put("zoneName", r.zoneName());
        m.put("workDays", r.workDays());
        m.put("shiftCount", r.shiftCount());
        m.put("hours", r1(r.hours()));
        m.put("onTimeRate", r1(r.onTimeRate() * 100));
        m.put("transactions", r.transactions());
        m.put("throughput", r2(r.throughput()));
        m.put("collectionTotal", r2(r.collectionTotal()));
        m.put("collectionPerHour", r2(r.collectionPerHour()));
        m.put("cash", r2(r.cash()));
        m.put("pos", r2(r.pos()));
        m.put("subscriptionCount", r.subscriptionCount());
        m.put("otherMethod", r2(r.otherMethod()));
        m.put("runawayCount", r.runawayCount());
        m.put("runawayRate", r1(r.runawayRate() * 100));
        m.put("cancelCount", r.cancelCount());
        m.put("cancelRate", r1(r.cancelRate() * 100));
        m.put("score", r.score());
        m.put("rating", r.rating());
        return m;
    }

    public List<ReportSheet> toSheets(Report rep) {
        ReportSheet summary = new ReportSheet("summary", "Özet", List.of("Personel", "Ad Soyad", "Saha",
                "Mesai günü", "Vardiya sayısı", "Çalışılan saat", "Zamanında başlama %", "İşlem sayısı",
                "Saat başına işlem", "Tahsilat toplam TL", "Nakit TL", "POS TL", "Abonelik çıkışı (adet)",
                "Kaçak sayısı", "Kaçak oranı %", "İptal sayısı", "Performans puanı", "Değerlendirme"));
        int days = 0, shifts = 0, txn = 0, subs = 0, runaways = 0, cancels = 0;
        double hours = 0, total = 0, cash = 0, pos = 0;
        for (Row r : rep.rows()) {
            summary.add(r.username(), r.fullName(), r.zoneName(), r.workDays(), r.shiftCount(), r1(r.hours()),
                    r1(r.onTimeRate() * 100), r.transactions(), r2(r.throughput()), Money.of(r2(r.collectionTotal())),
                    Money.of(r2(r.cash())), Money.of(r2(r.pos())), r.subscriptionCount(), r.runawayCount(),
                    r1(r.runawayRate() * 100), r.cancelCount(), r.score(), r.rating());
            days += r.workDays();
            shifts += r.shiftCount();
            hours += r.hours();
            txn += r.transactions();
            total += r.collectionTotal();
            cash += r.cash();
            pos += r.pos();
            subs += r.subscriptionCount();
            runaways += r.runawayCount();
            cancels += r.cancelCount();
        }
        summary.addTotal("Toplam", null, null, days, shifts, r1(hours), null, txn,
                hours > 0 ? r2(txn / hours) : 0.0, Money.of(r2(total)), Money.of(r2(cash)), Money.of(r2(pos)),
                subs, runaways, null, cancels, null, null);

        ReportSheet shiftSheet = new ReportSheet("shifts", "Mesai", List.of("Personel", "Saha", "Cihaz",
                "Başlangıç", "Bitiş", "Süre (dk)", "Zamanında mı", "Otomatik kapandı"));
        for (ShiftLine l : rep.shifts()) {
            shiftSheet.add(l.username(), l.zoneName(), l.deviceId(), l.start(), l.end(), l.minutes(), l.onTime(),
                    l.autoClosed() ? "Evet" : "Hayır");
        }

        PerformanceWeights w = rep.weights();
        ReportSheet method = new ReportSheet("method", "Puan Yöntemi", List.of("Başlık", "Değer"));
        method.add("Çalışılan saat ağırlığı (%)", w.hours());
        method.add("Zamanında başlama ağırlığı (%)", w.punctuality());
        method.add("Saat başına işlem ağırlığı (%)", w.throughput());
        method.add("Saat başına tahsilat ağırlığı (%)", w.collection());
        method.add("Kaçak oranı ağırlığı (%)", w.runaway());
        method.add("İptal oranı ağırlığı (%)", w.cancellation());
        method.add("Mesai başlangıç saati", w.startTime());
        method.add("Gecikme toleransı (dk)", w.graceMinutes());
        method.add("Yöntem", "Her ölçüt 0-100 alt puana çevrilir; puan = ağırlıklı toplamdır.");
        method.add("Saha ortalaması", "Çalışılan saat, saat başına işlem ve tahsilat, kaçak ve iptal oranı personelin ana "
                + "sahasının son 3 aylık (rapor ayı ve önceki 2 ay) ortalamasıyla kıyaslanır.");
        method.add("Yüksek olan iyi", "Saha ortalamasında 75 puan; ortalamanın 1,33 katı ve üstü 100 puan.");
        method.add("Düşük olan iyi", "Kaçak ve iptal oranı: saha ortalamasında 75 puan; 0 ise 100; ortalamanın 2 katı 0 puan.");
        method.add("Zamanında başlama", "Mesai günlerinin ilk vardiyası başlangıç saati + tolerans içinde ise zamanındadır "
                + "(saha ortalamasıyla kıyaslanmaz).");
        method.add("Değerlendirme", "85 ve üstü Çok iyi; 65 ve üstü İyi; altı Geliştirilmeli.");
        method.add("Gizlilik", "Puanı yalnızca yönetici görür.");
        return List.of(summary, shiftSheet, method);
    }
}
