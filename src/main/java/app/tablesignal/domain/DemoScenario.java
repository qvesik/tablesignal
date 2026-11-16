package app.tablesignal.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static app.tablesignal.domain.FloorLedger.Type.*;

/** Rebuild the floor from a fixed sequence so a demonstration can be replayed exactly. */
public final class DemoScenario {
    public record Entry(String time, String detail) {}
    public record Table(int number, String zone, String status, Long waitMinutes, List<Entry> timeline) {}
    public record Metrics(int occupied, int alerts, Double avgWaitMinutes) {}
    public record Floor(int step, String clock, List<Table> tables, Metrics metrics) {}

    private static final ZoneId LOCAL = ZoneId.of("Europe/Tirane");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(LOCAL);
    private static final Instant START = Instant.parse("2026-11-16T18:00:00Z");

    static final class ScenarioClock extends Clock {
        private Instant now;
        ScenarioClock(Instant now) { this.now = now; }
        void at(Instant value) { this.now = value; }
        public Instant instant() { return now; }
        public ZoneId getZone() { return LOCAL; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }

    public static Floor snapshot(int step) {
        if (step < 0 || step > 4) throw new IllegalArgumentException("Unknown scenario step");
        ScenarioClock clock = new ScenarioClock(START.minusSeconds(22 * 60));
        FloorLedger ledger = new FloorLedger(clock, Duration.ofMinutes(8));
        int[] occupied = {2, 4, 6, 9};
        for (int i = 0; i < occupied.length; i++) {
            int table = occupied[i];
            Instant seat = START.minusSeconds(60L * (22 - i * 5));
            clock.at(seat);
            String visit = "v-" + table;
            ledger.receive(new FloorLedger.Event("seat-" + table, visit, table, SEATED, seat));
            clock.at(seat.plusSeconds(60L * (i % 2 + 3)));
            ledger.receive(new FloorLedger.Event("order-" + table, visit, table, ORDER_RECORDED, clock.instant()));
        }
        clock.at(START);
        ledger.receive(new FloorLedger.Event("seat-12", "v-12", 12, SEATED, START));
        var order = new FloorLedger.Event("pos-12-001", "v-12", 12, ORDER_RECORDED, START.plusSeconds(4 * 60));
        if (step >= 1) {
            clock.at(START.plusSeconds(8 * 60));
            ledger.checkAlerts();
        }
        if (step >= 2) {
            clock.at(START.plusSeconds(10 * 60));
            ledger.receive(order);
        }
        if (step >= 3) {
            clock.at(START.plusSeconds(11 * 60));
            ledger.receive(order);
        }
        if (step >= 4) {
            clock.at(START.plusSeconds(15 * 60));
            ledger.receive(new FloorLedger.Event("close-12", "v-12", 12, CLOSED, clock.instant()));
        }
        List<Table> tables = new ArrayList<>();
        for (int number = 1; number <= 12; number++) {
            String zone = number <= 8 ? "Dining room" : "Terrace";
            var seat = ledger.current(number).orElse(null);
            if (seat == null) {
                tables.add(new Table(number, zone, "Free", null, List.of()));
                continue;
            }
            String status = seat.closedAt() != null ? "Closed" : seat.firstOrder() != null ? "Order confirmed"
                    : seat.alertOpen() ? "POS order pending" : "Waiting for order";
            var history = seat.notes().stream().map(n -> new Entry(TIME.format(n.receivedAt()),
                    n.receivedAt().equals(n.occurredAt()) ? n.text() : n.text() + " (occurred " + TIME.format(n.occurredAt()) + ")")).toList();
            tables.add(new Table(number, zone, status, seat.waitMinutes(clock.instant()), history));
        }
        List<FloorLedger.Visit> visits = ledger.visits();
        int active = (int) visits.stream().filter(v -> v.closedAt() == null).count();
        int alerts = (int) visits.stream().filter(FloorLedger.Visit::alertOpen).count();
        var avg = visits.stream().filter(v -> v.firstOrder() != null)
                .mapToLong(v -> Duration.between(v.seatedAt(), v.firstOrder()).getSeconds()).average();
        Double avgMinutes = avg.isPresent() ? Math.round(avg.getAsDouble() / 6.0) / 10.0 : null;
        return new Floor(step, TIME.format(clock.instant()), List.copyOf(tables), new Metrics(active, alerts, avgMinutes));
    }
    private DemoScenario() {}
}
