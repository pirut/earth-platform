#!/usr/bin/env python3
"""Local receiver for opt-in performance reports, for testing on one computer.

Run it, then start the game with
  -Dearthplatform.perfReports.url=http://127.0.0.1:8787/report
and turn on "Send performance reports". Each report is appended to perf-reports.jsonl as one line.
The sender's address is never written, as a hosted receiver must not keep it either.
"""
import gzip, json, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

OUT = sys.argv[1] if len(sys.argv) > 1 else "perf-reports.jsonl"
MAX_BYTES = 512 << 10

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        size = int(self.headers.get("Content-Length", "0"))
        if self.path != "/report" or not 0 < size <= MAX_BYTES:
            self.send_response(400); self.end_headers(); return
        body = self.rfile.read(size)
        try:
            if self.headers.get("Content-Encoding") == "gzip": body = gzip.decompress(body)
            report = json.loads(body)
            if report.get("schema") != 1 or not isinstance(report.get("windows"), list): raise ValueError
        except (ValueError, OSError):
            self.send_response(400); self.end_headers(); return
        with open(OUT, "a", encoding="utf-8") as out: out.write(json.dumps(report, separators=(",", ":")) + "\n")
        print(f"report {report.get('session', '?')[:8]}: {len(report['windows'])} windows", flush=True)
        self.send_response(204); self.end_headers()
    def log_message(self, *args): pass  # the default log prints the sender's address

ThreadingHTTPServer(("127.0.0.1", 8787), Handler).serve_forever()
