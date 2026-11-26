"""Disposable CI TLS proxy: discard the one exact completed POST response.

No raw request/response data, credentials, identifiers or paths in diagnostics.
All writes except the explicitly armed work submission are refused.
"""
import http.client
from email.message import Message
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
MODE = sys.argv[3] if len(sys.argv) > 3 else "lost-response"
assert MODE in {"lost-response", "interrupted-upload", "interrupted-upstream"}
assert re.fullmatch(r"[0-9a-f-]{36}", WORK)
BASE = "/v1/projects/81000000-0000-4000-8000-000000000001"
LOCK = threading.Lock()
counts = {"posts": 0, "forwarded": 0, "upstream201": 0, "dropped": 0}
if MODE == "interrupted-upload":
    counts.update(partial=0)
if MODE == "interrupted-upstream":
    counts.update(partial=0, upstream_rejected=0)


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
            if MODE == "interrupted-upload":
                # Consume a strict nonempty prefix, never forward the body.
                # This is an ingress interruption, not a server rollback test.
                if n < 2:
                    self.send_error(400)
                    return
                prefix = self.rfile.read(min(n - 1, 1024))
                if not 0 < len(prefix) < n:
                    self.send_error(400)
                    return
                counts["partial"] += 1
                counts["dropped"] += 1
                event()
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                return
            body = self.rfile.read(n)
            if len(body) != n:
                self.send_error(400)
                return
            if MODE == "interrupted-upstream":
                self.interrupt_upstream(body, n)
                return
            counts["forwarded"] += 1
            event()
            self.forward(body, drop=True)

    def interrupt_upstream(self, body, n):
        # Cut within the final file part, not after the closing delimiter.
        # Keep the original Content-Length so Tomcat sees a premature EOF.
        message = Message()
        message["Content-Type"] = self.headers.get("Content-Type", "")
        boundary = message.get_boundary()
        if not boundary or not re.fullmatch(r"[!-~]{1,70}", boundary):
            self.send_error(400)
            return
        marker = b"\r\n--" + boundary.encode("ascii")
        end = body.rfind(marker + b"--")
        start = body.rfind(marker + b"\r\n", 0, end)
        headers_end = body.find(b"\r\n\r\n", start, end)
        if start < 0 or headers_end < 0 or end - (headers_end + 4) < 2:
            self.send_error(400)
            return
        if b"filename=" not in body[start:headers_end].lower():
            self.send_error(400)
            return
        cut = headers_end + 4 + (end - headers_end - 4) // 2
        assert 0 < cut < end < n
        headers = {k: v for k, v in self.headers.items()
                   if k.lower() not in {"connection", "host", "transfer-encoding",
                                        "accept-encoding", "content-length", "expect"}}
        headers["X-Forwarded-Proto"] = "https"
        headers["Connection"] = "close"
        headers["Accept-Encoding"] = "identity"
        headers["Content-Length"] = str(n)
        conn = http.client.HTTPConnection("127.0.0.1", 8080, timeout=30)
        try:
            conn.request("POST", self.path, body=body[:cut], headers=headers)
            counts["forwarded"] += 1
            counts["partial"] += 1
            event()
            conn.sock.shutdown(socket.SHUT_WR)
            response = conn.getresponse()
            # Require a real upstream rejection, not just a local disconnect.
            # Never return that response to Android: its outcome is uncertain.
            data = response.read(1_048_577)
            if len(data) <= 1_048_576 and 400 <= response.status <= 599:
                counts["upstream_rejected"] += 1
            if response.status == 201:
                counts["upstream201"] += 1
        except (OSError, http.client.HTTPException):
            # Missing rejection evidence is NOT a pass; the CI counter gate fails.
            pass
        finally:
            conn.close()
            counts["dropped"] += 1
            event()
            self.close_connection = True
            self.connection.shutdown(socket.SHUT_RDWR)

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
