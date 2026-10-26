package com.bilpark.backend.report;

import com.bilpark.backend.model.ParkingStatus;
import com.bilpark.backend.model.StreetLocation;
import com.bilpark.backend.report.ReportSupport.Range;
import com.bilpark.backend.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Period (revenue) report: cash / POS / subscription split, pro-rated rent, per zone and per day. */
@Service
public class PeriodReportService {

    private final ReportQueryRepository repo;
    private final SettingsService settings;
    private final ReportSupport support;

    public PeriodReportService(ReportQueryRepository repo, SettingsService settings, ReportSupport support) {
        this.repo = repo;
        this.settings = settings;
        this.support = support;
    }

    public record MonthRent(YearMonth month, int overlapDays, double monthlyShare, double share, boolean entered) {}

    public record ZoneLine(String street, String zone, double cash, double pos, double other, int transactions, int runaways) {
        double total() { return cash + pos + other; }
    }

    public record DayLine(LocalDate day, double cash, double pos, double other) {
        double total() { return cash + pos + other; }
    }

    public record Report(Range range, double cash, double pos, double subscriptionSales, double other,
                         int subscriptionExits, List<MonthRent> rents, int cancelledCount, double cancelledFees,
                         int unpaidRunawayCount, double unpaidRunawayDebt,
                         List<ZoneLine> zones, List<DayLine> days) {
        public double total() { return cash + pos + subscriptionSales + other; }
        public double rent() { return rents.stream().mapToDouble(MonthRent::share).sum(); }
        public double net() { return total() - rent(); }
    }

    private static final class Acc {
        double cash, pos, other;
        int txn, runaways;
    }

    @Transactional(readOnly = true)
    public Report compute(Range range) {
        List<RecordRow> paid = repo.byStatusAndExit(ParkingStatus.PAID, range.start(), range.end());
        List<RecordRow> runaways = repo.byStatusAndExit(ParkingStatus.RUNAWAY, range.start(), range.end());
        List<RecordRow> cancelled = repo.cancelledBetween(range.start(), range.end());
        Map<String, String> streetNames = support.streetNames();

        double cash = 0, pos = 0, other = 0;
        int subExits = 0;
        Map<String, Acc> zoneAcc = new TreeMap<>();
        Map<String, String[]> zoneLabel = new HashMap<>();
        Map<LocalDate, double[]> days = new TreeMap<>();
        for (LocalDate d = range.from(); !d.isAfter(range.to()); d = d.plusDays(1)) days.put(d, new double[3]);

        for (RecordRow r : paid) {
            double f = r.feeOrZero();
            Acc z = zone(zoneAcc, zoneLabel, r, streetNames);
            double[] day = days.get(r.exitTime().toLocalDate());
            z.txn++;
            if (r.paymentMethod() == null) {
                other += f; z.other += f; day[2] += f;
            } else switch (r.paymentMethod()) {
                case CASH -> { cash += f; z.cash += f; day[0] += f; }
                case POS -> { pos += f; z.pos += f; day[1] += f; }
                case SUBSCRIPTION -> subExits++;
                default -> { other += f; z.other += f; day[2] += f; }
            }
        }
        int unpaidCount = 0;
        double unpaidDebt = 0;
        for (RecordRow r : runaways) {
            Acc z = zone(zoneAcc, zoneLabel, r, streetNames);
            z.txn++;
            z.runaways++;
            if (!r.debtPaid()) {
                unpaidCount++;
                unpaidDebt += r.feeOrZero();
            }
        }
        double cancelledFees = cancelled.stream().mapToDouble(RecordRow::feeOrZero).sum();
        double subSales = repo.subscriptionFeesStartedBetween(range.from(), range.to()).stream()
                .mapToDouble(v -> v == null ? 0.0 : v).sum();

        List<MonthRent> rents = new ArrayList<>();
        Map<Integer, Double> entered = settings.annualRents();
        for (YearMonth m = YearMonth.from(range.from()); !m.isAfter(YearMonth.from(range.to())); m = m.plusMonths(1)) {
            LocalDate a = range.from().isAfter(m.atDay(1)) ? range.from() : m.atDay(1);
            LocalDate b = range.to().isBefore(m.atEndOfMonth()) ? range.to() : m.atEndOfMonth();
            int overlap = (int) (b.toEpochDay() - a.toEpochDay() + 1);
            double annual = entered.getOrDefault(m.getYear(), 0.0);
            double monthly = annual / 12.0;
            rents.add(new MonthRent(m, overlap, monthly, monthly * overlap / m.lengthOfMonth(), entered.containsKey(m.getYear())));
        }

        List<ZoneLine> zones = new ArrayList<>();
        for (Map.Entry<String, Acc> e : zoneAcc.entrySet()) {
            String[] label = zoneLabel.get(e.getKey());
            Acc a = e.getValue();
            zones.add(new ZoneLine(label[0], label[1], a.cash, a.pos, a.other, a.txn, a.runaways));
        }
        List<DayLine> dayLines = new ArrayList<>();
        days.forEach((d, v) -> dayLines.add(new DayLine(d, v[0], v[1], v[2])));
        return new Report(range, cash, pos, subSales, other, subExits, rents, cancelled.size(), cancelledFees,
                unpaidCount, unpaidDebt, zones, dayLines);
    }

    private static Acc zone(Map<String, Acc> acc, Map<String, String[]> labels, RecordRow r, Map<String, String> names) {
        StreetLocation st = r.street();
        String street = st == null ? "-" : ReportSupport.street(names, st);
        String zone = r.zoneName() == null ? "-" : r.zoneName();
        String key = street + "|" + zone + "|" + r.zoneId();
        labels.putIfAbsent(key, new String[]{street, zone});
        return acc.computeIfAbsent(key, k -> new Acc());
    }

    private static double r2(double v) { return Math.round(v * 100.0) / 100.0; }

    public static Map<String, Object> toMap(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", r.range().from().toString());
        m.put("to", r.range().to().toString());
        m.put("cashRevenue", r2(r.cash()));
        m.put("posRevenue", r2(r.pos()));
        m.put("subscriptionSales", r2(r.subscriptionSales()));
        m.put("otherRevenue", r2(r.other()));
        m.put("totalRevenue", r2(r.total()));
        m.put("subscriptionExitCount", r.subscriptionExits());
        m.put("rentShare", r2(r.rent()));
        List<Map<String, Object>> months = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        for (MonthRent mr : r.rents()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("month", mr.month().toString());
            mm.put("overlapDays", mr.overlapDays());
            mm.put("monthlyShare", r2(mr.monthlyShare()));
            mm.put("share", r2(mr.share()));
            months.add(mm);
            if (!mr.entered() && !missing.contains(mr.month().getYear())) missing.add(mr.month().getYear());
        }
        m.put("rentMonths", months);
        m.put("rentMissingYears", missing);
        m.put("netRemaining", r2(r.net()));
        m.put("cancelledCount", r.cancelledCount());
        m.put("cancelledFees", r2(r.cancelledFees()));
        m.put("unpaidRunawayCount", r.unpaidRunawayCount());
        m.put("unpaidRunawayDebt", r2(r.unpaidRunawayDebt()));
        return m;
    }

    public static List<ReportSheet> toSheets(Report r) {
        ReportSheet summary = new ReportSheet("summary", "Özet", List.of("Kalem", "Değer"));
        summary.add("Dönem", r.range().display());
        summary.add("Nakit ciro", Money.of(r2(r.cash())));
        summary.add("POS ciro", Money.of(r2(r.pos())));
        summary.add("Abonelik satışı", Money.of(r2(r.subscriptionSales())));
        summary.add("Diğer/eski kayıt", Money.of(r2(r.other())));
        summary.add("Toplam ciro", Money.of(r2(r.total())));
        summary.add("Abonelikle çıkış sayısı", r.subscriptionExits());
        summary.add("Kira payı", Money.of(r2(r.rent())));
        for (MonthRent mr : r.rents()) {
            summary.add("Aylık kira payı " + mr.month(), Money.of(r2(mr.monthlyShare())));
        }
        Set<Integer> missing = new TreeSet<>();
        for (MonthRent mr : r.rents()) if (!mr.entered()) missing.add(mr.month().getYear());
        for (int y : missing) summary.add("Not", y + " için kira bedeli girilmemiş");
        summary.add("Net kalan", Money.of(r2(r.net())));
        summary.add("İptal edilen kayıt sayısı", r.cancelledCount());
        summary.add("İptal edilen kayıt tutarı", Money.of(r2(r.cancelledFees())));
        summary.add("Ödenmemiş kaçak borç", Money.of(r2(r.unpaidRunawayDebt())));

        ReportSheet zones = new ReportSheet("zones", "Saha bazında", List.of("Cadde", "Saha", "Nakit", "POS", "Diğer",
                "Toplam", "İşlem sayısı", "Kaçak sayısı"));
        for (ZoneLine z : r.zones()) {
            zones.add(z.street(), z.zone(), Money.of(r2(z.cash())), Money.of(r2(z.pos())), Money.of(r2(z.other())),
                    Money.of(r2(z.total())), z.transactions(), z.runaways());
        }
        ReportSheet daily = new ReportSheet("daily", "Günlük", List.of("Tarih", "Nakit", "POS", "Diğer", "Toplam"));
        for (DayLine d : r.days()) {
            daily.add(d.day(), Money.of(r2(d.cash())), Money.of(r2(d.pos())), Money.of(r2(d.other())), Money.of(r2(d.total())));
        }
        return List.of(summary, zones, daily);
    }
}
