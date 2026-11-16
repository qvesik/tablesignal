#!/usr/bin/env python3
"""Real HTTP + app/database restart checks for the isolated Compose project."""
import argparse
import concurrent.futures
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import urllib.error
import urllib.request

BASE = os.environ.get("TABLESIGNAL_BASE_URL", "http://127.0.0.1:18080")
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
RESULTS = []


class HttpStatusError(AssertionError):
    def __init__(self, status, message):
        self.status = status
        super().__init__(message)


def request(path, body=None, expected=200):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Accept": "application/json", "Content-Type": "application/json"}
    req = urllib.request.Request(BASE + path, data=data, headers=headers)
    try:
        with HTTP.open(req, timeout=5) as response:
            status, content = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, content = error.code, error.read()
    payload = json.loads(content)
    if status != expected:
        raise HttpStatusError(status, f"{path}: expected {expected}, got {status}: {payload}")
    return payload


def ready():
    deadline = time.monotonic() + 120
    last_error = None
    while time.monotonic() < deadline:
        try:
            return request("/api/floor")
        except (OSError, ValueError, AssertionError) as error:
            last_error = error
            time.sleep(1)
    raise AssertionError(f"Server did not become ready: {last_error}")


def check(name, run):
    started = time.monotonic()
    try:
        run()
    except Exception as error:
        RESULTS.append({"name": name, "status": "failed", "error": str(error)})
        raise
    RESULTS.append({"name": name, "status": "passed", "seconds": round(time.monotonic() - started, 2)})
    print("PASS:", name, flush=True)


def instant(value):
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def post(path, body=None):
    return request(path, {} if body is None else body)


def history():
    return request("/api/visits/demo-visit")


def guided():
    post("/api/demo/reset")
    first = post("/api/demo/next")
    assert first["metrics"]["alerts"] == 1
    delivered = post("/api/demo/next")
    assert delivered["metrics"]["avgWaitMinutes"] == 4
    h = history()
    v = h["visit"]
    assert (instant(v["first_order"]) - instant(v["seated_at"])).total_seconds() == 240
    order = next(e for e in h["events"] if e["type"] == "ORDER_RECORDED")
    assert (instant(order["received_at"]) - instant(order["occurred_at"])).total_seconds() == 360
    assert v["alert_resolved"] and "Delayed POS delivery" in v["resolution"]
    post("/api/demo/next")
    assert history() == h


def restart(service):
    before = history()
    subprocess.run(["docker", "compose", "restart", service], check=True)
    ready()
    assert history() == before


def old_visit():
    for _ in range(3):
        post("/api/demo/next")
    table = request("/api/floor")["tables"][11]
    assert table["status"] == "Seated" and table["waitMinutes"] == 1
    assert table["visit"]["visit_id"] == "demo-visit-2"
    assert table["visit"]["first_order"] is None


def event(key, visit, table):
    return {"venueId": "durres-demo", "source": "compose-check", "eventId": key,
            "tableId": table, "visitId": visit, "type": "SEATED", "occurredAt": "2026-11-16T18:17:00Z"}


def concurrent_ingestion():
    gate = threading.Barrier(2)
    same = event("duplicate-seat", "duplicate-visit", 1)
    def deliver(body):
        gate.wait()
        try:
            return request("/api/events", body)["result"]
        except HttpStatusError as error:
            if error.status == 409:
                return "CONFLICT"
            raise
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        jobs = [pool.submit(deliver, same) for _ in range(2)]
        assert sorted(j.result() for j in jobs) == ["DUPLICATE", "RECORDED"]
        gate.reset()
        jobs = [pool.submit(deliver, event(f"competing-{i}", f"competing-visit-{i}", 2)) for i in range(2)]
        assert sorted(j.result() for j in jobs) == ["CONFLICT", "RECORDED"]
    changed = dict(same, partySize=1)
    request("/api/events", changed, expected=409)


def manual_mode():
    command = {"venueId": "durres-demo", "source": "compose-check", "eventId": "manual-close",
               "tableId": 12, "visitId": "demo-visit-2", "type": "CLOSED", "occurredAt": "2026-11-16T18:17:00Z"}
    post("/api/demo/events", command)
    assert request("/api/floor")["step"] == -2
    request("/api/demo/next", {}, expected=409)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--http-only", action="store_true", help="Use a disposable running app; skip Docker restart checks")
    args = parser.parse_args()
    print("This check replaces synthetic demo data at " + BASE, flush=True)
    try:
        check("service readiness", ready)
        check("delayed POS reconciliation and retry", guided)
        if not args.http_only:
            check("application container restart preserves history", lambda: restart("app"))
            check("PostgreSQL container restart preserves history", lambda: restart("db"))
        check("late old-visit isolation", old_visit)
        check("concurrent duplicates, key conflict and competing seating", concurrent_ingestion)
        check("manual fixture changes pause guided mode", manual_mode)
    finally:
        Path("target").mkdir(exist_ok=True)
        expected_checks = 5 if args.http_only else 7
        report_name = "http-acceptance.json" if args.http_only else "compose-acceptance.json"
        Path("target", report_name).write_text(json.dumps({"base": BASE, "mode": "http-only" if args.http_only else "compose", "status": "passed" if len(RESULTS) == expected_checks and all(r["status"] == "passed" for r in RESULTS) else "failed", "checks": RESULTS}, indent=2) + "\n")
    print("All requested acceptance checks passed.")


if __name__ == "__main__":
    main()
