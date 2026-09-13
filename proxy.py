#!/usr/bin/env python3
"""
Tiny local proxy for yama-remo (stdlib only, no dependencies).

Why this exists: the Yamaha MusicCast (YXC) HTTP API sends no
Access-Control-Allow-Origin header on any response. Because of that,
opening musiccast-remote.html directly lets every button (power, volume,
source, playback) work — commands still reach and execute on the speaker —
but the browser refuses to let the page's JavaScript read ANY response
body cross-origin. That means live status and now-playing (song/artist)
readback can never populate, no matter what the page's own code does;
it's a browser security boundary, not a bug in the page.

This script serves the app's own files AND relays YXC requests
server-side, where there's no browser involved and so no CORS
enforcement at all. It then hands the JSON back to the page same-origin
(same host:port as the page itself), so the browser reads it normally.

Usage:
    python3 proxy.py [port]        # default port 8080

Then open http://<this-machine's-LAN-IP>:<port>/musiccast-remote.html —
from this machine, or from your phone on the same Wi-Fi (same idea as the
NAS/http.server options in README.md, but with full status/song readback
instead of blind commands). The page auto-detects this proxy is present
and only then reroutes through it; opening the file directly (file://) or
serving it from a plain static server (no proxy) behaves exactly as
before.
"""
import functools
import http.server
import json
import os
import re
import socketserver
import sys
import urllib.error
import urllib.request

DEFAULT_PORT = 8080
PROXY_PREFIX = "/yxc/"
HEALTH_PATH = "/yxc-health"
REQUEST_TIMEOUT_SECONDS = 5
# LAN-only, dotted-quad IPv4 — keeps this from being usable as an open
# relay to arbitrary hosts (there's no auth here, same as the speaker's
# own API, so this stays deliberately narrow: YXC paths, IPv4 targets only).
IPV4_RE = re.compile(r"^(\d{1,3}\.){3}\d{1,3}$")


class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == HEALTH_PATH:
            self._respond(200, b"yama-remo-proxy", "text/plain")
        elif self.path.startswith(PROXY_PREFIX):
            self._proxy_yxc_request()
        else:
            super().do_GET()

    def _proxy_yxc_request(self):
        rest = self.path[len(PROXY_PREFIX):]
        ip, _, path_and_query = rest.partition("/")
        if not IPV4_RE.match(ip) or not path_and_query:
            self._respond_json(400, {"response_code": -1, "error": "expected /yxc/<speaker-ip>/<yxc-path>"})
            return
        target = "http://{}/YamahaExtendedControl/v1/{}".format(ip, path_and_query)
        try:
            with urllib.request.urlopen(target, timeout=REQUEST_TIMEOUT_SECONDS) as resp:
                self._respond(resp.status, resp.read(), resp.headers.get("Content-Type", "application/json"))
        except Exception as e:
            # Valid JSON with a non-zero response_code, not an HTTP error —
            # the page already knows how to show that as a device-rejected
            # command rather than misreading it as a CORS-blind success.
            self._respond_json(200, {"response_code": -1, "error": str(e)})

    def _respond_json(self, status, obj):
        self._respond(status, json.dumps(obj).encode("utf-8"), "application/json")

    def _respond(self, status, body, content_type):
        self.send_response(status)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass  # the app's own Settings -> Show log is the useful log here


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    handler = functools.partial(Handler, directory=os.path.dirname(os.path.abspath(__file__)))
    with socketserver.TCPServer(("0.0.0.0", port), handler) as httpd:
        print("yama-remo + YXC proxy running on http://0.0.0.0:{}/".format(port))
        print("Open http://<this-machine's-LAN-IP>:{}/musiccast-remote.html".format(port))
        httpd.serve_forever()
