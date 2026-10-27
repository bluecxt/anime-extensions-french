#!/usr/bin/env python3
"""
Transparent Smart Forward Proxy for Cloudflare Turnstile bypass.
Forwards all normal HTTP/HTTPS traffic directly.
When a 403 / 503 Cloudflare challenge is encountered:
Calls FlareSolverr (default: http://127.0.0.1:8191/v1) to resolve the challenge,
caches the cf_clearance cookie, and transparently replays the request.
"""

import sys
import json
import urllib.request
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler
import socketserver
import threading
import time

FLARESOLVERR_URL = "http://127.0.0.1:8191/v1"
COOKIE_CACHE = {} # domain -> {"cookie": "cf_clearance=...", "ua": "...", "expires": timestamp}
CACHE_LOCK = threading.Lock()

def get_flaresolverr_solution(url):
    print(f"[SmartProxy] Invoking FlareSolverr for challenge at: {url}", flush=True)
    payload = json.dumps({"cmd": "request.get", "url": url, "maxTimeout": 60000}).encode("utf-8")
    req = urllib.request.Request(
        FLARESOLVERR_URL,
        data=payload,
        headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=70) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            if data.get("status") == "ok":
                solution = data.get("solution", {})
                cookies = solution.get("cookies", [])
                cf_cookie = "; ".join([f"{c['name']}={c['value']}" for c in cookies if 'cf_clearance' in c['name']])
                ua = solution.get("userAgent", "")
                parsed = urllib.parse.urlparse(url)
                domain = parsed.netloc.split(":")[0]
                with CACHE_LOCK:
                    COOKIE_CACHE[domain] = {
                        "cookie": cf_cookie,
                        "ua": ua,
                        "expires": time.time() + 1800 # 30 min cache
                    }
                print(f"[SmartProxy] FlareSolverr solved {domain}! cf_clearance cached.", flush=True)
                return COOKIE_CACHE[domain]
    except Exception as e:
        print(f"[SmartProxy] FlareSolverr error: {e}", file=sys.stderr, flush=True)
    return None

class SmartProxyHandler(BaseHTTPRequestHandler):
    def do_CONNECT(self):
        # Forward HTTPS CONNECT tunnel
        host, port = self.path.split(":")
        port = int(port)
        try:
            import socket
            sock = socket.create_connection((host, port), timeout=30)
            self.send_response(200, "Connection Established")
            self.end_headers()
            
            # Bi-directional stream
            conns = [self.connection, sock]
            import select
            while True:
                r, _, _ = select.select(conns, [], conns, 30)
                if not r:
                    break
                for s in r:
                    other = sock if s is self.connection else self.connection
                    data = s.recv(8192)
                    if not data:
                        return
                    other.sendall(data)
        except Exception:
            pass

    def do_GET(self):
        self.handle_http("GET")

    def do_POST(self):
        self.handle_http("POST")

    def handle_http(self, method):
        url = self.path
        if not url.startswith("http://") and not url.startswith("https://"):
            url = f"http://{self.headers.get('Host', '')}{url}"

        parsed = urllib.parse.urlparse(url)
        domain = parsed.netloc.split(":")[0]

        headers = dict(self.headers)
        headers.pop("Proxy-Connection", None)

        with CACHE_LOCK:
            cached = COOKIE_CACHE.get(domain)
            if cached and cached["expires"] > time.time():
                cookie_header = headers.get("Cookie", "")
                if "cf_clearance" not in cookie_header:
                    headers["Cookie"] = f"{cookie_header}; {cached['cookie']}".strip("; ")
                if cached.get("ua"):
                    headers["User-Agent"] = cached["ua"]

        data = None
        if method == "POST":
            content_length = int(self.headers.get("Content-Length", 0))
            if content_length > 0:
                data = self.rfile.read(content_length)

        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                self.send_response(resp.status)
                for k, v in resp.headers.items():
                    if k.lower() not in ["transfer-encoding", "content-encoding"]:
                        self.send_header(k, v)
                self.end_headers()
                self.wfile.write(resp.read())
        except urllib.error.HTTPError as e:
            if e.code in (403, 503) and ("cf-mitigated" in e.headers or "cloudflare" in e.headers.get("Server", "").lower()):
                sol = get_flaresolverr_solution(url)
                if sol:
                    headers["Cookie"] = sol["cookie"]
                    if sol.get("ua"):
                        headers["User-Agent"] = sol["ua"]
                    retry_req = urllib.request.Request(url, data=data, headers=headers, method=method)
                    try:
                        with urllib.request.urlopen(retry_req, timeout=30) as resp2:
                            self.send_response(resp2.status)
                            for k, v in resp2.headers.items():
                                if k.lower() not in ["transfer-encoding", "content-encoding"]:
                                    self.send_header(k, v)
                            self.end_headers()
                            self.wfile.write(resp2.read())
                            return
                    except Exception:
                        pass
            self.send_response(e.code)
            for k, v in e.headers.items():
                if k.lower() not in ["transfer-encoding", "content-encoding"]:
                    self.send_header(k, v)
            self.end_headers()
            self.wfile.write(e.read())
        except Exception as e:
            self.send_error(502, f"Proxy Error: {e}")

class ThreadingHTTPServer(socketserver.ThreadingMixIn, HTTPServer):
    daemon_threads = True

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    if len(sys.argv) > 2:
        FLARESOLVERR_URL = sys.argv[2]
    server = ThreadingHTTPServer(("0.0.0.0", port), SmartProxyHandler)
    print(f"[SmartProxy] Listening on 0.0.0.0:{port}, FlareSolverr upstream: {FLARESOLVERR_URL}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
