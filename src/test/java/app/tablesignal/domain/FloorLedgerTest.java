package app.tablesignal.domain;

import org.junit.jupiter.api.Test;

class FloorLedgerTest {
    @Test void onTime() { new DomainChecks().onTime(); }
    @Test void exactThreshold() { new DomainChecks().exactThreshold(); }
    @Test void lateDeliveryAndReplay() { new DomainChecks().lateAndReplay(); }
    @Test void olderVisitIsolated() { new DomainChecks().oldVisit(); }
    @Test void conflictsAreRejectedAtomically() { new DomainChecks().conflicts(); }
    @Test void firstOrderUsesEventTime() { new DomainChecks().firstByEventTime(); }
    @Test void concurrentReplay() throws Exception { new DomainChecks().concurrentReplay(); }
    @Test void floorSnapshots() { DomainChecks.snapshots(); }
}
