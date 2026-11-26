"""Disposable CI TLS proxy: discard the one exact completed POST response.

No raw request/response data, credentials, identifiers or paths in diagnostics.
All writes except the explicitly armed work submission are refused.
"""
import http.client
import json
import pathlib
import re
import socket
import ssl
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

WORK = sys.argv[1]
EVENT = pathlib.Path(sys.argv[2])
assert re.fullmatch(r"[0-9a-f-]{36}", WORK)
BASE = "/v1/projects/81000000-0000-4000-8000-000000000001"
LOCK = threading.Lock()
counts = {"posts": 0, "forwarded": 0, "upstream201": 0, "dropped": 0}


def event():
    temp = EVENT.with_suffix(".tmp")
    temp.write_text(json.dumps(counts))
    temp.replace(EVENT)


class Proxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.forward()

    def do_HEAD(self):
        self.forward()

    def do_POST(self):
        with LOCK:
            counts["posts"] += 1
            event()
            if (counts["posts"] != 1 or self.path != BASE + "/submission"
                    or self.headers.get("X-DataCollector-Work-Item-Id") != WORK
                    or self.headers.get("Transfer-Encoding") is not None):
                self.send_error(409)
                return
            try:
                n = int(self.headers.get("Content-Length", "-1"))
            except ValueError:
                self.send_error(400)
                return
            if not 1 <= n <= 13 * 1024 * 1024:
                self.send_error(400)
                return
            body = self.rfile.read(n)
            if len(body) != n:
                self.send_error(400)
                return
            counts["forwarded"] += 1
            event()
            self.forward(body, drop=True)

    def forward(self, body=None, drop=False):
        if not (self.path == "/actuator/health" or self.path.startswith(BASE + "/")):
            self.send_error(404)
            return
        headers = {k: v for k, v in self.headers.items()
                   if k.lower() not in {"connection", "host", "transfer-encoding",
                                        "accept-encoding", "content-length", "expect"}}
        headers["X-Forwarded-Proto"] = "https"
        headers["Accept-Encoding"] = "identity"
        conn = http.client.HTTPConnection("127.0.0.1", 8080, timeout=30)
        try:
            conn.request(self.command, self.path, body=body, headers=headers)
            response = conn.getresponse()
            data = response.read(1_048_577)
            if len(data) > 1_048_576:
                self.send_error(502)
                return
            if drop and response.status == 201:
                counts["upstream201"] += 1
                # No HTTP status, response headers or body sent to Android.
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                counts["dropped"] += 1
                event()
                return
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in {"connection", "transfer-encoding", "content-length",
                                       "server", "date", "keep-alive"}:
                    self.send_header(key, value)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(data)
            self.close_connection = True
        except (OSError, http.client.HTTPException):
            self.close_connection = True
        finally:
            conn.close()


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # Never expose request data in a traceback.
        pass


event()
server = Server(("0.0.0.0", 8443), Proxy)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.minimum_version = ssl.TLSVersion.TLSv1_2
context.load_cert_chain("/tmp/u06-t04a-tls/server.crt", "/tmp/u06-t04a-tls/server.key")
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
