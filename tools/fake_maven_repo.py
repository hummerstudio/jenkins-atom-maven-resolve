#!/usr/bin/env python3
"""A tiny Maven repository over real HTTP, for the smoke test.

Serves a directory tree the way a Nexus/Artifactory repository does (static files under a Maven 2 layout), and appends
one JSON line per request so the test can assert the *path* the atom asked for and the headers it sent. Nothing here
is Maven-aware: the test builds the layout itself, so a wrong path in the atom shows up as a 404, not as a friendly
fallback.

    python3 tools/fake_maven_repo.py --root DIR --log FILE --port-file FILE \
        [--require-basic user:pass] [--delay SECONDS] [--status CODE] [--truncate PATH]

Prints nothing; the caller waits for --port-file to appear and reads the port from it.
"""
import argparse
import base64
import http.server
import json
import os
import sys
import threading
import time
import urllib.parse


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        cfg = self.server.cfg
        path = urllib.parse.urlsplit(self.path).path
        with cfg["lock"]:
            with open(cfg["log"], "a", encoding="utf-8") as log:
                log.write(json.dumps({
                    "method": self.command,
                    "path": self.path,
                    "authorization": self.headers.get("Authorization", ""),
                    "userAgent": self.headers.get("User-Agent", ""),
                }) + "\n")
        if cfg["delay"]:
            time.sleep(cfg["delay"])
        if cfg["basic"] and self.headers.get("Authorization") != cfg["basic"]:
            self.reply(401, b"credentials required\n")
            return
        forced = cfg["status"].get(path)
        if forced is not None:
            self.reply(forced, b"forced status\n")
            return
        file = os.path.join(cfg["root"], path.lstrip("/"))
        if not os.path.isfile(file):
            self.reply(404, b"not found\n")
            return
        with open(file, "rb") as handle:
            body = handle.read()
        if path in cfg["truncate"]:
            # a Content-Length larger than the bytes sent: the client must notice the short read
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(body) + 10))
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True
            return
        self.reply(200, body)

    def reply(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--log", required=True)
    parser.add_argument("--port-file", required=True)
    parser.add_argument("--require-basic", default="")
    parser.add_argument("--delay", type=float, default=0)
    parser.add_argument("--status", action="append", default=[], metavar="CODE:PATH")
    parser.add_argument("--truncate", action="append", default=[], metavar="PATH")
    args = parser.parse_args()

    basic = ""
    if args.require_basic:
        user, _, password = args.require_basic.partition(":")
        basic = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
    status = {}
    for entry in args.status:
        code, _, path = entry.partition(":")
        status[path] = int(code)

    with open(args.log, "w", encoding="utf-8"):
        pass          # a fresh log per server

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    server.cfg = {
        "root": args.root,
        "log": args.log,
        "basic": basic,
        "delay": args.delay,
        "status": status,
        "truncate": set(args.truncate),
        "lock": threading.Lock(),
    }
    with open(args.port_file + ".tmp", "w", encoding="utf-8") as handle:
        handle.write(str(server.server_address[1]))
    os.replace(args.port_file + ".tmp", args.port_file)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
