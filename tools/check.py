#!/usr/bin/env python3
"""Behaviour checks for the maven-resolve atom, against a fake Maven repository.

Every expectation is recomputed here from its own inputs: the server's bytes are hashed by this file, the expected
repository path is built from the coordinate, and the config the atom reports is compared with the files on disk.
The fake repository also records the request lines and headers, so a check can assert what the atom asked for, not
only what it ended up with.

    python3 tools/check.py                 # every check
    python3 tools/check.py checksum        # only names containing "checksum"
    python3 tools/check.py --jar X.jar     # mutated build (tools/mutations.py)
"""
import argparse
import base64
import hashlib
import http.server
import json
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import urllib.parse

HERE = pathlib.Path(__file__).resolve().parent.parent
CODE = "maven-resolve"
JAR = HERE / "dist" / CODE / (CODE + ".jar")
OVERRIDE_JAR = None

CHECKS = []


def check(name):
    def register(fn):
        CHECKS.append((name, fn))
        return fn
    return register


# ---------------------------------------------------------------------------- fake repository

class Repo:
    """Paths to bytes, plus per-path overrides: redirects, status codes, truncation."""

    def __init__(self, name):
        self.name = name
        self.files = {}
        self.redirects = {}
        self.status = {}
        self.truncate = set()
        self.requests = []
        self.auth = None          # expected "Basic ..." header, when set
        self.lock = threading.Lock()

    def put(self, path, body):
        self.files[path] = body
        return body

    def put_artifact(self, group, artifact, version, body, extension="jar", classifier="", checksums=("sha1",)):
        name = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + f".{extension}"
        path = f"/{group.replace('.', '/')}/{artifact}/{version}/{name}"
        self.put(path, body)
        algorithms = {"sha1": hashlib.sha1, "md5": hashlib.md5, "sha256": hashlib.sha256}
        for algorithm in checksums:
            self.put(path + "." + algorithm, (algorithms[algorithm](body).hexdigest() + "\n").encode())
        return path


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        repo = self.server.repo
        with repo.lock:
            repo.requests.append({"path": self.path, "headers": dict(self.headers)})
        if repo.auth is not None and self.headers.get("Authorization") != repo.auth:
            self.reply(401, b"credentials required\n")
            return
        path = urllib.parse.urlsplit(self.path).path
        if path in repo.redirects:
            self.send_response(302)
            self.send_header("Location", repo.redirects[path])
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path in repo.status:
            self.reply(repo.status[path], b"no\n")
            return
        if path in repo.files:
            body = repo.files[path]
            if path in repo.truncate:
                self.send_response(200)
                self.send_header("Content-Length", str(len(body) + 10))
                self.end_headers()
                self.wfile.write(body)
                self.close_connection = True
                return
            self.reply(200, body)
            return
        self.reply(404, b"not here\n")

    def reply(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)


def serve(repo):
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.repo = repo
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, f"http://127.0.0.1:{server.server_address[1]}"


def sha1(body):
    return hashlib.sha1(body).hexdigest()


def basic(user, password):
    return "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()


# ---------------------------------------------------------------------------- fixtures

def fixtures():
    a = Repo("a")
    b = Repo("b")
    c = Repo("c")

    body_100 = b"widget-1.0.0.jar bytes\n" * 40
    body_120 = b"widget-1.2.0.jar bytes\n" * 41
    body_sources = b"widget-1.0.0-sources.jar\n" * 7
    snapshot_body = b"widget-2.0.0 snapshot bytes\n" * 13

    a.put_artifact("com.acme", "widget", "1.0.0", body_100)
    a.put_artifact("com.acme", "widget", "1.0.0", body_sources, classifier="sources")
    a.put_artifact("com.acme", "widget", "1.2.0", body_120)

    a.put("/com/acme/widget/maven-metadata.xml", (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<metadata><groupId>com.acme</groupId><artifactId>widget</artifactId>\n'
        '<versioning><latest>2.0.0-SNAPSHOT</latest><release>1.2.0</release>\n'
        '<versions><version>1.0.0</version><version>1.2.0</version>'
        '<version>2.0.0-SNAPSHOT</version></versions></versioning></metadata>\n').encode())
    a.put("/com/acme/widget/2.0.0-SNAPSHOT/maven-metadata.xml", (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<metadata><groupId>com.acme</groupId><artifactId>widget</artifactId><version>2.0.0-SNAPSHOT</version>\n'
        '<versioning><snapshot><timestamp>20261031.120000</timestamp><buildNumber>7</buildNumber></snapshot>\n'
        '<snapshotVersions><snapshotVersion><extension>jar</extension>'
        '<value>2.0.0-20261031.120000-7</value></snapshotVersion></snapshotVersions>'
        '</versioning></metadata>\n').encode())
    a.put_artifact("com.acme", "widget", "2.0.0", snapshot_body,
                   checksums=("sha1",))
    # the timestamped remote name the metadata points at
    a.put("/com/acme/widget/2.0.0-SNAPSHOT/widget-2.0.0-20261031.120000-7.jar", snapshot_body)
    a.put("/com/acme/widget/2.0.0-SNAPSHOT/widget-2.0.0-20261031.120000-7.jar.sha1",
          (sha1(snapshot_body) + "\n").encode())

    bad = b"badsha-1.0.jar bytes\n" * 9
    a.put_artifact("com.acme", "badsha", "1.0", bad)
    a.put("/com/acme/badsha/1.0/badsha-1.0.jar.sha1",
          (hashlib.sha1(b"a different file").hexdigest() + "\n").encode())

    a.put_artifact("com.acme", "nosha", "1.0", b"nosha-1.0.jar\n" * 5, checksums=())
    a.put_artifact("com.acme", "md5only", "1.0", b"md5only-1.0.jar\n" * 3, checksums=("md5",))

    pointer_100 = b"pointer-1.0.0.jar\n" * 4
    pointer_120 = b"pointer-1.2.0.jar\n" * 5
    a.put_artifact("com.acme", "pointer", "1.0.0", pointer_100)
    a.put_artifact("com.acme", "pointer", "1.2.0", pointer_120)
    a.put("/com/acme/pointer/maven-metadata.xml", (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<metadata><groupId>com.acme</groupId><artifactId>pointer</artifactId>\n'
        '<versioning><latest>1.0.0</latest><release>1.2.0</release>\n'
        '<versions><version>1.0.0</version><version>1.2.0</version></versions>'
        '</versioning></metadata>\n').encode())

    local_copy = b"localcopy-1.0-SNAPSHOT.jar\n" * 3
    a.put("/com/acme/localcopy/1.0-SNAPSHOT/maven-metadata.xml", (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<metadata><groupId>com.acme</groupId><artifactId>localcopy</artifactId><version>1.0-SNAPSHOT</version>\n'
        '<versioning><snapshot><localCopy>true</localCopy></snapshot></versioning></metadata>\n').encode())
    a.put_artifact("com.acme", "localcopy", "1.0-SNAPSHOT", local_copy)

    moved = b"moved-1.0.jar bytes\n" * 6
    a.put_artifact("com.acme", "moved", "1.0", moved)
    a.redirects["/com/acme/moved/1.0/moved-1.0.jar"] = "/mirror/com/acme/moved/1.0/moved-1.0.jar"
    a.redirects["/com/acme/moved/1.0/moved-1.0.jar.sha1"] = "/mirror/com/acme/moved/1.0/moved-1.0.jar.sha1"
    a.put("/mirror/com/acme/moved/1.0/moved-1.0.jar", moved)
    a.put("/mirror/com/acme/moved/1.0/moved-1.0.jar.sha1", (sha1(moved) + "\n").encode())

    cross = b"cross-1.0.jar bytes\n" * 8
    b.put("/real/cross-1.0.jar", cross)
    b.put("/real/cross-1.0.jar.sha1", (sha1(cross) + "\n").encode())
    # filled with absolute URLs after the servers are listening
    a.put("/com/acme/loop/1.0/loop-1.0.jar", b"loop")
    a.redirects["/com/acme/loop/1.0/loop-1.0.jar"] = "/com/acme/loop/1.0/loop-1.0.jar"
    a.status["/com/acme/boom/1.0/boom-1.0.jar"] = 500
    a.put("/com/acme/truncated/1.0/truncated-1.0.jar", b"t" * 200)
    a.truncate.add("/com/acme/truncated/1.0/truncated-1.0.jar")

    secured = b"secured-1.0.jar bytes\n" * 4
    c.auth = basic("builder", "s3cret")
    c.put_artifact("com.acme", "secured", "1.0", secured)
    return a, b, c, {"cross": cross, "moved": moved, "secured": secured, "snapshot": snapshot_body,
                     "bad": bad, "body_100": body_100, "body_120": body_120, "body_sources": body_sources,
                     "local_copy": local_copy, "pointer_120": pointer_120}


# ---------------------------------------------------------------------------- running the atom

def java_bin():
    return str(pathlib.Path(os.environ["JAVA_HOME"]) / "bin" / "java") if os.environ.get("JAVA_HOME") else "java"


def run(workspace, params, jar=None, secrets=None):
    jar = jar or OVERRIDE_JAR or JAR
    workspace = pathlib.Path(workspace)
    data = {key: value for key, value in os.environ.items() if key.isupper()}
    data.update(params)
    data["atom_workspace"] = str(workspace)
    (workspace / "input.json").write_text(json.dumps(data), encoding="utf-8")
    if secrets:
        (workspace / ".secrets.json").write_text(json.dumps(secrets), encoding="utf-8")
    env = dict(os.environ)
    env.update({"atom_data_dir": str(workspace), "atom_data_input": "input.json",
                "atom_data_output": "output.json", "atom_workspace": str(workspace)})
    proc = subprocess.run([java_bin(), "-cp", str(jar), "com.jenkinschina.atom.sdk.AtomRunner"],
                          cwd=workspace, env=env, capture_output=True, text=True)
    output = json.loads((workspace / "output.json").read_text(encoding="utf-8"))
    return output, proc.stdout + proc.stderr


def value(output, key):
    node = output.get("data", {}).get(key)
    if isinstance(node, dict):
        return node.get("value", "")
    return "" if node is None else str(node)


def expect(condition, message):
    if not condition:
        raise AssertionError(message)


def expect_status(output, code):
    expect(output.get("status") == "error", f"expected error {code}, got {output.get('status')}: {output.get('message')}")
    expect(output.get("errorCode") == code,
           f"expected errorCode {code}, got {output.get('errorCode')}: {output.get('message')}")


def lines(output, key):
    text = value(output, key)
    return [line for line in text.splitlines() if line]


def fresh():
    return tempfile.mkdtemp(prefix="mvn-resolve-")


def tmpdir():
    return pathlib.Path(fresh())


# ---------------------------------------------------------------------------- checks

def repo_root(workspace):
    return pathlib.Path(workspace) / ".m2" / "repository"


def start():
    a, b, c, bodies = fixtures()
    servers = []
    for repo in (a, b, c):
        server, url = serve(repo)
        servers.append(server)
        if repo is a:
            url_a = url
        elif repo is b:
            url_b = url
        else:
            url_c = url
    a.redirects["/com/acme/cross/1.0/cross-1.0.jar"] = url_b + "/real/cross-1.0.jar"
    a.redirects["/com/acme/cross/1.0/cross-1.0.jar.sha1"] = url_b + "/real/cross-1.0.jar.sha1"
    return servers, {"a": url_a, "b": url_b, "c": url_c}, bodies


SERVERS, URLS, BODIES = None, None, None


@check("release.resolves")
def _release():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:widget:1.0.0"})
    expect(output["status"] == "success", f"failed: {output.get('message')}\n{log[-800:]}")
    local = repo_root(ws) / "com/acme/widget/1.0.0/widget-1.0.0.jar"
    expect(local.exists(), f"missing {local}")
    expect(local.read_bytes() == BODIES["body_100"], "bytes on disk differ from the served bytes")
    expect(sha1(local.read_bytes()) == sha1(BODIES["body_100"]), "sha1 differs")
    target = ws / "widget-1.0.0.jar"
    expect(target.exists() and target.read_bytes() == BODIES["body_100"], "workspace copy missing or wrong")
    expect(value(output, "resolved") == "1", f"resolved={value(output, 'resolved')}")
    expect(value(output, "bytes") == str(len(BODIES["body_100"])), f"bytes={value(output, 'bytes')}")
    expect("com.acme:widget=1.0.0" in value(output, "versions"), f"versions={value(output, 'versions')}")
    expect("sha1=ok" in value(output, "checksums"), f"checksums={value(output, 'checksums')}")
    expect(value(output, "urls").endswith("/com/acme/widget/1.0.0/widget-1.0.0.jar"), "wrong url reported")
    paths = [r["path"] for r in SERVERS[0].repo.requests]
    expect("/com/acme/widget/1.0.0/widget-1.0.0.jar" in paths, f"atom asked for {paths[-4:]}")
    expect(not (ws / "output.json").read_text().count("Authorization"), "no credentials were given")
    request = [r for r in SERVERS[0].repo.requests if r["path"].endswith(".jar")][-1]
    expect("Authorization" not in request["headers"], "authorization sent without credentials")


@check("classifier.resolves")
def _classifier():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:widget:1.0.0:jar:sources"})
    expect(output["status"] == "success", f"failed: {output.get('message')}\n{log[-800:]}")
    local = repo_root(ws) / "com/acme/widget/1.0.0/widget-1.0.0-sources.jar"
    expect(local.exists() and local.read_bytes() == BODIES["body_sources"], "sources jar missing")
    expect((ws / "widget-1.0.0-sources.jar").exists(), "sources jar not copied")


@check("checksum.mismatch.fails")
def _mismatch_fails():
    ws = tmpdir()
    (ws / "kept.jar").write_bytes(b"old target\n")
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:badsha:1.0",
                           "checksumPolicy": "fail", "targetFileName": "kept.jar"})
    expect_status(output, 40006)
    message = output.get("message", "")
    expected = hashlib.sha1(b"a different file").hexdigest()
    actual = sha1(BODIES["bad"])
    expect(expected in message and actual in message, f"message does not name both digests: {message}")
    expect(not (repo_root(ws) / "com/acme/badsha/1.0/badsha-1.0.jar").exists(), "artifact written despite mismatch")
    expect(not list(ws.rglob("*.part")), "a .part file was left behind")
    expect((ws / "kept.jar").read_bytes() == b"old target\n", "existing target was overwritten")


@check("checksum.mismatch.warned")
def _mismatch_warned():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:badsha:1.0", "checksumPolicy": "warn"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    local = repo_root(ws) / "com/acme/badsha/1.0/badsha-1.0.jar"
    expect(local.read_bytes() == BODIES["bad"], "warn policy should still store the bytes")
    expect("mismatch" in value(output, "checksums"), f"checksums={value(output, 'checksums')}")
    expect("mismatch" in log, "the warning is not in the log")


@check("checksum.missing.fails")
def _missing_fails():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:nosha:1.0", "checksumPolicy": "fail"})
    expect_status(output, 40005)
    expect("no checksum" in output.get("message", ""), output.get("message"))


@check("checksum.missing.ignored")
def _missing_ignored():
    ws = tmpdir()
    before = len(SERVERS[0].repo.requests)
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:nosha:1.0", "checksumPolicy": "ignore"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    asked = [r["path"] for r in SERVERS[0].repo.requests[before:]]
    expect(not any(p.endswith((".sha1", ".md5", ".sha256")) for p in asked), f"ignore still fetched {asked}")
    expect("ignored" in value(output, "checksums"), f"checksums={value(output, 'checksums')}")


@check("not.found")
def _missing_artifact():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:absent:9.9"})
    expect_status(output, 40003)
    expect("/com/acme/absent/9.9/absent-9.9.jar" in output.get("message", ""), output.get("message"))
    expect(not (repo_root(ws) / "com/acme/absent/9.9/absent-9.9.jar").exists(), "file created for a 404")


@check("auth.sent")
def _auth_sent():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["c"], "coordinates": "com.acme:secured:1.0",
                           "username": "builder", "password": "s3cret"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    request = [r for r in SERVERS[2].repo.requests if r["path"].endswith(".jar")][-1]
    expect(request["headers"].get("Authorization") == basic("builder", "s3cret"),
           f"Authorization={request['headers'].get('Authorization')!r}")
    expect((ws / "secured-1.0.jar").exists(), "no workspace copy")


@check("auth.rejected")
def _auth_rejected():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["c"], "coordinates": "com.acme:secured:1.0",
                           "username": "builder", "password": "wrong"})
    expect_status(output, 40004)


@check("redirect.followed")
def _redirect():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:moved:1.0"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    paths = [r["path"] for r in SERVERS[0].repo.requests]
    expect("/com/acme/moved/1.0/moved-1.0.jar" in paths, "the original path was not requested")
    expect("/mirror/com/acme/moved/1.0/moved-1.0.jar" in paths, "the Location was not followed")
    expect((ws / "moved-1.0.jar").read_bytes() == BODIES["moved"], "wrong bytes after the redirect")


@check("redirect.cross.host.strips.auth")
def _redirect_cross():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:cross:1.0",
                           "username": "u", "password": "p"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    first = [r for r in SERVERS[0].repo.requests if r["path"].endswith("cross-1.0.jar")][-1]
    expect(first["headers"].get("Authorization") == basic("u", "p"), "credentials missing on the first hop")
    second = [r for r in SERVERS[1].repo.requests if r["path"].endswith("cross-1.0.jar")][-1]
    expect("Authorization" not in second["headers"],
           f"Authorization leaked to another origin: {second['headers'].get('Authorization')!r}")
    expect((ws / "cross-1.0.jar").read_bytes() == BODIES["cross"], "wrong bytes after the cross-host redirect")


@check("redirect.loop.fails")
def _redirect_loop():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:loop:1.0"})
    expect_status(output, 40008)
    expect("redirect" in output.get("message", "").lower(), output.get("message"))


@check("server.error")
def _server_error():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:boom:1.0"})
    expect_status(output, 40008)
    expect("500" in output.get("message", ""), output.get("message"))


@check("truncated.fails")
def _truncated():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:truncated:1.0"})
    expect_status(output, 40008)


@check("pseudo.release")
def _release_pseudo():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:widget:RELEASE"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    expect(value(output, "versions").strip() == "com.acme:widget=1.2.0", value(output, "versions"))
    local = repo_root(ws) / "com/acme/widget/1.2.0/widget-1.2.0.jar"
    expect(local.exists() and local.read_bytes() == BODIES["body_120"], "RELEASE did not resolve 1.2.0")


@check("pseudo.release.pointer")
def _release_pointer():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:pointer:RELEASE"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    expect(value(output, "versions").strip() == "com.acme:pointer=1.2.0",
           f"<release> is not authoritative: {value(output, 'versions')}")
    local = repo_root(ws) / "com/acme/pointer/1.2.0/pointer-1.2.0.jar"
    expect(local.read_bytes() == BODIES["pointer_120"], "wrong pointer bytes")


@check("pseudo.latest.snapshot")
def _latest_pseudo():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:widget:LATEST"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    expect(value(output, "versions").strip() == "com.acme:widget=2.0.0-SNAPSHOT", value(output, "versions"))
    expect(value(output, "snapshots") == "1", f"snapshots={value(output, 'snapshots')}")
    local = repo_root(ws) / "com/acme/widget/2.0.0-SNAPSHOT/widget-2.0.0-SNAPSHOT.jar"
    expect(local.exists(), f"local snapshot file is not named -SNAPSHOT: {list(repo_root(ws).rglob('*.jar'))}")
    expect(local.read_bytes() == BODIES["snapshot"], "snapshot bytes differ")
    urls = value(output, "urls")
    expect("widget-2.0.0-20261031.120000-7.jar" in urls, f"timestamped remote name not used: {urls}")


@check("snapshot.explicit")
def _snapshot_explicit():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:widget:2.0.0-SNAPSHOT"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    expect(value(output, "snapshots") == "1", "snapshot not counted")
    expect("20261031.120000-7" in value(output, "urls"), value(output, "urls"))
    expect((ws / "widget-2.0.0-SNAPSHOT.jar").exists(), "workspace copy not under the -SNAPSHOT name")


@check("target.rename")
def _target_rename():
    ws = tmpdir()
    (ws / "lib" / "deps").mkdir(parents=True)
    (ws / "lib" / "deps" / "widget.jar").write_bytes(b"stale")
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:widget:1.0.0",
                           "targetFileName": "lib/deps/widget.jar"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    target = ws / "lib" / "deps" / "widget.jar"
    expect(target.read_bytes() == BODIES["body_100"], "target was not replaced")


@check("target.multi.refused")
def _target_multi():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"],
                           "coordinates": "com.acme:widget:1.0.0\ncom.acme:widget:1.2.0",
                           "targetFileName": "one.jar"})
    expect_status(output, 40009)


@check("coordinate.bad")
def _coord_bad():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:widget"})
    expect_status(output, 40001)


@check("target.escape.refused")
def _target_escape():
    home = tmpdir()
    ws = home / "ws"
    ws.mkdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:widget:1.0.0",
                           "targetFileName": "../evil.jar"})
    expect_status(output, 40010)
    expect(not (home / "evil.jar").exists(), "a file was written outside the workspace")


@check("multiple.resolved")
def _multiple():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"],
                           "coordinates": "com.acme:widget:1.0.0\ncom.acme:widget:1.2.0"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    expect(value(output, "resolved") == "2", value(output, "resolved"))
    expect(len(lines(output, "files")) == 2, value(output, "files"))
    total = len(BODIES["body_100"]) + len(BODIES["body_120"])
    expect(value(output, "bytes") == str(total), f"bytes={value(output, 'bytes')} expected {total}")
    expect(len(lines(output, "checksums")) == 2, value(output, "checksums"))


@check("local.repository.absolute")
def _local_absolute():
    ws = tmpdir()
    outside = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:widget:1.0.0",
                           "localRepository": str(outside)})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    expect((outside / "com/acme/widget/1.0.0/widget-1.0.0.jar").exists(), "absolute local repository ignored")


@check("fail.on.error.false")
def _fail_soft():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"],
                           "coordinates": "com.acme:absent:9.9\ncom.acme:widget:1.0.0",
                           "failOnError": "false"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    expect(value(output, "resolved") == "1", value(output, "resolved"))
    expect(value(output, "failed") == "1", value(output, "failed"))
    expect("404" in value(output, "errors") or "not found" in value(output, "errors"), value(output, "errors"))



@check("checksum.md5.fallback")
def _md5_only():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:md5only:1.0"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    expect("md5=ok" in value(output, "checksums"), f"checksums={value(output, 'checksums')}")


@check("snapshot.localcopy")
def _snapshot_localcopy():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:localcopy:1.0-SNAPSHOT"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    expect(value(output, "urls").endswith("/localcopy-1.0-SNAPSHOT.jar"), f"urls={value(output, 'urls')}")
    expect("localcopy-1.0-SNAPSHOT.jar" in value(output, "files"), value(output, "files"))


@check("copy.disabled")
def _copy_off():
    ws = tmpdir()
    output, log = run(ws, {"repoUrl": URLS["a"], "coordinates": "com.acme:widget:1.0.0",
                           "copyToWorkspace": "false"})
    expect(output["status"] == "success", f"failed: {output.get('message')}")
    expect(not (ws / "widget-1.0.0.jar").exists(), "copied although copyToWorkspace=false")
    expect((repo_root(ws) / "com/acme/widget/1.0.0/widget-1.0.0.jar").exists(), "local repository file missing")
    expect(value(output, "targets") == "", f"targets={value(output, 'targets')}")


# ---------------------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("filter", nargs="?", default="")
    parser.add_argument("--jar", default=str(JAR))
    args = parser.parse_args()

    global SERVERS, URLS, BODIES, OVERRIDE_JAR
    OVERRIDE_JAR = args.jar
    SERVERS, URLS, BODIES = start()
    cases = [(name, fn) for name, fn in CHECKS if args.filter in name]
    passed = 0
    for name, fn in cases:
        try:
            fn()
            print(f"ok   {name}")
            passed += 1
        except AssertionError as error:
            print(f"FAIL {name}: {error}")
    for server in SERVERS:
        server.shutdown()
    print(f"\n{passed}/{len(cases)} checks")
    return 0 if passed == len(cases) and cases else 1


if __name__ == "__main__":
    sys.exit(main())
