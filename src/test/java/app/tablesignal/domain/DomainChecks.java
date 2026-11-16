package app.tablesignal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static app.tablesignal.domain.FloorLedger.Type.*;

public class DomainChecks {
    static final Instant T = Instant.parse("2026-11-16T18:00:00Z");
    final DemoScenario.ScenarioClock clock = new DemoScenario.ScenarioClock(T);
    final FloorLedger ledger = new FloorLedger(clock, Duration.ofMinutes(8));

    DomainChecks() { ledger.receive(event("s", "one", SEATED, 0)); }
    static FloorLedger.Event event(String id, String visit, FloorLedger.Type type, int minute) {
        return new FloorLedger.Event(id, visit, 12, type, T.plusSeconds(minute * 60L));
    }
    FloorLedger.Visit current() { return ledger.current(12).orElseThrow(); }
    static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException wanted) { return; }
        throw new AssertionError("Conflicting or invalid event accepted");
    }
    void minute(int n) { clock.at(T.plusSeconds(60L * n)); }

    void onTime() {
        minute(4);
        ledger.receive(event("p", "one", ORDER_RECORDED, 4));
        minute(15);
        ledger.checkAlerts();
        check(!current().alertOpen() && current().waitMinutes(clock.instant()) == 4, "An on-time order holds wait at 4");
        check(current().notes().size() == 2, "No historical alert");
    }
    void exactThreshold() {
        clock.at(T.plusSeconds(479)); ledger.checkAlerts();
        check(!current().alertOpen(), "7:59 is below threshold");
        minute(8); ledger.checkAlerts(); ledger.checkAlerts();
        check(current().alertOpen() && current().notes().size() == 2, "Open exactly one alert at 8:00");
    }
    void lateAndReplay() {
        minute(8); ledger.checkAlerts();
        minute(10);
        var p = event("p", "one", ORDER_RECORDED, 4);
        ledger.receive(p);
        var before = current();
        check(before.waitMinutes(clock.instant()) == 4, "Use occurred time instead of arrival time");
        check(!before.alertOpen() && before.notes().size() == 4, "Retain opening and resolution");
        check(before.notes().get(3).text().contains("POS order confirmed"), "Resolve with reason");
        minute(11);
        check(ledger.receive(p) == FloorLedger.Result.DUPLICATE && current().equals(before), "Replay changes nothing");
    }
    void oldVisit() {
        minute(9); ledger.receive(event("cl", "one", CLOSED, 9));
        minute(10); ledger.receive(event("s2", "two", SEATED, 10));
        minute(19); ledger.checkAlerts();
        ledger.receive(event("old", "one", ORDER_RECORDED, 4));
        check(current().id().equals("two") && current().alertOpen() && current().firstOrder() == null, "Old delivery never resolves the new visit");
        check(ledger.visits().get(0).firstOrder().equals(T.plusSeconds(240)), "Visit history corrected");
        rejects(() -> ledger.receive(event("late", "one", ORDER_RECORDED, 11)));
    }
    void conflicts() {
        minute(2);
        rejects(() -> ledger.receive(event("s", "one", ORDER_RECORDED, 2)));
        rejects(() -> ledger.receive(event("s2", "two", SEATED, 2)));
        rejects(() -> ledger.receive(event("p", "one", ORDER_RECORDED, 20)));
        ledger.receive(event("p", "one", ORDER_RECORDED, 1));
        check(current().notes().size() == 2, "Rejected event ID remains available for valid retry");
    }
    void firstByEventTime() {
        minute(7);
        ledger.receive(event("p5", "one", ORDER_RECORDED, 5));
        ledger.receive(event("p2", "one", ORDER_RECORDED, 2));
        check(current().waitMinutes(clock.instant()) == 2, "Use earliest order even with reversed delivery");
        rejects(() -> ledger.receive(event("cl", "one", CLOSED, 3)));
    }
    void concurrentReplay() throws Exception {
        minute(2);
        var event = event("p", "one", ORDER_RECORDED, 2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return ledger.receive(event); });
            var second = pool.submit(() -> { start.await(); return ledger.receive(event); });
            start.countDown();
            check(first.get() != second.get() && current().notes().size() == 2, "Exactly one concurrent replay accepted");
        }
    }
    static void snapshots() {
        var alerted = DemoScenario.snapshot(1);
        var corrected = DemoScenario.snapshot(2);
        var replayed = DemoScenario.snapshot(3);
        var done = DemoScenario.snapshot(4);
        check(alerted.metrics().alerts() == 1 && corrected.metrics().alerts() == 0, "Only Table 12 alerts");
        check(corrected.tables().get(11).waitMinutes() == 4 && corrected.clock().equals("19:10"), "Corrected floor projection");
        check(corrected.tables().equals(replayed.tables()) && corrected.metrics().equals(replayed.metrics()), "Shift stats stable on duplicate");
        check(done.tables().get(11).status().equals("Closed") && done.metrics().occupied() == 4, "Close updates floor");
    }
    public static void main(String[] args) throws Exception {
        new DomainChecks().onTime();
        new DomainChecks().exactThreshold();
        new DomainChecks().lateAndReplay();
        new DomainChecks().oldVisit();
        new DomainChecks().conflicts();
        new DomainChecks().firstByEventTime();
        new DomainChecks().concurrentReplay();
        snapshots();
        System.out.println("8 domain scenarios passed");
    }
}
