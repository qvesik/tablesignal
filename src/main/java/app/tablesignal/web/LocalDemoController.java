package app.tablesignal.web;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
class LocalDemoController {
  private final DemoService demo;

  LocalDemoController(DemoService demo) {
    this.demo = demo;
  }

  record PosInput(int tableId, String visitId, String eventId, java.time.Instant occurredAt) {}

  @PostMapping("/api/demo/events")
  Map<String, Object> event(@RequestBody LedgerService.Input input) {
    return demo.receiveManual(input);
  }

  @GetMapping("/api/demo/pos/queue")
  java.util.List<java.util.Map<String, Object>> queue() {
    return demo.posQueue();
  }

  @PostMapping("/api/demo/showcase")
  Map<String, Object> showcase() {
    return demo.showcase();
  }

  @PostMapping("/api/demo/pos/order")
  Map<String, Object> order(@org.springframework.web.bind.annotation.RequestBody PosInput input) {
    return demo.posOrder(input.tableId(), input.visitId(), input.eventId(), input.occurredAt());
  }

  @PostMapping("/api/demo/pos/{action}")
  Map<String, Object> pos(@PathVariable String action) {
    return demo.posConnection(action);
  }

  record ClockInput(java.time.Instant instant) {}

  @PostMapping("/api/demo/clock")
  Map<String, Object> clock(@org.springframework.web.bind.annotation.RequestBody ClockInput input) {
    return demo.advanceClock(input.instant());
  }

  @PostMapping("/api/demo/{action}")
  Map<String, Object> demo(@PathVariable String action) {
    return demo.demo(action);
  }
}
