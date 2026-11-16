package app.tablesignal.web;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class FloorController {
  private final LedgerService ledger;
  private final EventRepository events;

  FloorController(LedgerService ledger, EventRepository events) {
    this.ledger = ledger;
    this.events = events;
  }

  @GetMapping("/activity")
  java.util.List<java.util.Map<String, Object>> activity(
      @RequestParam(defaultValue = "60") int limit) {
    return events.recent(limit);
  }

  @GetMapping("/floor")
  Map<String, Object> floor() {
    return ledger.floor();
  }

  @GetMapping("/visits/{id}")
  Map<String, Object> visit(@PathVariable String id) {
    return ledger.history(id);
  }

  @PostMapping("/events")
  Map<String, Object> event(@RequestBody LedgerService.Input event) {
    return ledger.receive(event);
  }
}
