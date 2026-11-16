package app.tablesignal.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Event-time accounting for one restaurant. Commands are atomic within one ledger. */
public final class FloorLedger {
    public enum Type { SEATED, ORDER_RECORDED, CLOSED }
    public enum Result { RECORDED, DUPLICATE }
    public record Event(String id, String visitId, int table, Type type, Instant occurredAt) {
        public Event {
            if (id == null || id.isBlank() || visitId == null || visitId.isBlank() || table <= 0) {
                throw new IllegalArgumentException("An event needs an ID, a visit ID and a positive table number");
            }
            Objects.requireNonNull(type);
            Objects.requireNonNull(occurredAt);
        }
    }
    public record Note(Instant receivedAt, String text, Instant occurredAt) {}
    public record Visit(String id, int table, Instant seatedAt, Instant firstOrder, Instant closedAt,
                        boolean alertOpen, List<Note> notes) {
        public Long waitMinutes(Instant now) {
            var end = firstOrder != null ? firstOrder : closedAt != null ? closedAt : now;
            return Duration.between(seatedAt, end).toMinutes();
        }
    }

    private static final class State {
        final Event seating;
        final List<Note> notes = new ArrayList<>();
        Instant firstOrder;
        Instant latestOrder;
        Instant closedAt;
        boolean alertOpen;

        State(Event seating, Instant receipt) {
            this.seating = seating;
            notes.add(new Note(receipt, "Guests seated", seating.occurredAt()));
        }

        Visit view() {
            return new Visit(seating.visitId(), seating.table(), seating.occurredAt(),
                    firstOrder, closedAt, alertOpen, List.copyOf(notes));
        }
    }

    private final Clock clock;
    private final Duration threshold;
    private final Map<String, Event> processed = new HashMap<>();
    private final Map<String, State> visits = new LinkedHashMap<>();
    private final Map<Integer, State> latestAtTable = new HashMap<>();

    public FloorLedger(Clock clock, Duration threshold) {
        this.clock = Objects.requireNonNull(clock);
        if (threshold.isZero() || threshold.isNegative()) throw new IllegalArgumentException("Invalid threshold");
        this.threshold = threshold;
    }

    public synchronized Result receive(Event event) {
        Event seen = processed.get(event.id());
        if (seen != null) {
            if (!seen.equals(event)) throw new IllegalArgumentException("Conflicting payload for " + event.id());
            return Result.DUPLICATE;
        }
        Instant received = clock.instant();
        if (event.occurredAt().isAfter(received)) throw new IllegalArgumentException("Event is in the future");
        if (event.type() == Type.SEATED) {
            State previous = latestAtTable.get(event.table());
            if (visits.containsKey(event.visitId()) || (previous != null &&
                    (previous.closedAt == null || event.occurredAt().isBefore(previous.closedAt)))) {
                throw new IllegalArgumentException("Visit ID or table already occupied for this time");
            }
            State seat = new State(event, received);
            visits.put(event.visitId(), seat);
            latestAtTable.put(event.table(), seat);
        } else {
            State seat = visits.get(event.visitId());
            if (seat == null || seat.seating.table() != event.table() ||
                    event.occurredAt().isBefore(seat.seating.occurredAt())) {
                throw new IllegalArgumentException("Event does not belong to this visit");
            }
            if (event.type() == Type.CLOSED) {
                if (seat.closedAt != null || (seat.latestOrder != null && event.occurredAt().isBefore(seat.latestOrder))) {
                    throw new IllegalArgumentException("Invalid visit close time");
                }
                seat.closedAt = event.occurredAt();
                seat.notes.add(new Note(received, "Visit closed", event.occurredAt()));
                resolveAlert(seat, received, "visit closed");
            } else {
                if (seat.closedAt != null && event.occurredAt().isAfter(seat.closedAt)) {
                    throw new IllegalArgumentException("Order occurred after visit closed");
                }
                if (seat.firstOrder == null || event.occurredAt().isBefore(seat.firstOrder)) {
                    seat.firstOrder = event.occurredAt();
                }
                if (seat.latestOrder == null || event.occurredAt().isAfter(seat.latestOrder)) {
                    seat.latestOrder = event.occurredAt();
                }
                seat.notes.add(new Note(received, "POS order received; first-order wait " +
                        Duration.between(seat.seating.occurredAt(), seat.firstOrder).toMinutes() + " min", event.occurredAt()));
                resolveAlert(seat, received, "POS order confirmed");
            }
        }
        processed.put(event.id(), event);
        return Result.RECORDED;
    }

    /** Call on the observation clock. Alerts preserve what the floor manager knew at this instant. */
    public synchronized void checkAlerts() {
        Instant now = clock.instant();
        for (State seat : latestAtTable.values()) {
            boolean waiting = seat.firstOrder == null && seat.closedAt == null && !seat.alertOpen;
            if (waiting && !now.isBefore(seat.seating.occurredAt().plus(threshold))) {
                seat.alertOpen = true;
                seat.notes.add(new Note(now, "Alert opened: POS has not confirmed an order", now));
            }
        }
    }

    private static void resolveAlert(State seat, Instant when, String reason) {
        if (seat.alertOpen) {
            seat.alertOpen = false;
            seat.notes.add(new Note(when, "Alert resolved: " + reason, when));
        }
    }

    public synchronized Optional<Visit> current(int table) {
        return Optional.ofNullable(latestAtTable.get(table)).map(State::view);
    }

    public synchronized List<Visit> visits() {
        return visits.values().stream().map(State::view).toList();
    }
}
