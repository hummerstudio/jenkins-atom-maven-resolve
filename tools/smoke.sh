#!/usr/bin/env bash
# Smoke test: build the atom, serve a real Maven layout with python's http.server, resolve one artifact and one 404.
#
#   ./tools/smoke.sh
#
# The deep suite is tools/check.py (28 checks, request-level assertions, checksum/redirect/snapshot rules).
set -uo pipefail
cd "$(dirname "$0")/.."
bash build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

root="$(mktemp -d)"
repo="$root/repo"
mkdir -p "$repo/com/acme/demo/1.0"
printf 'demo jar bytes\n%.0s' {1..20} >"$repo/com/acme/demo/1.0/demo-1.0.jar"
python3 -c 'import hashlib,sys; b=open(sys.argv[1],"rb").read(); open(sys.argv[1]+".sha1","w").write(hashlib.sha1(b).hexdigest()+"\n")' "$repo/com/acme/demo/1.0/demo-1.0.jar"
port="$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')"
( cd "$repo" && exec python3 -m http.server "$port" --bind 127.0.0.1 >/dev/null 2>&1 ) &
server=$!
trap 'kill $server 2>/dev/null; wait $server 2>/dev/null' EXIT
for _ in $(seq 1 50); do
  curl -fsS "http://127.0.0.1:$port/com/acme/demo/1.0/demo-1.0.jar.sha1" >/dev/null 2>&1 && break
  sleep 0.1
done

dir="$root/ws"; mkdir -p "$dir"
printf '{"repoUrl": "http://127.0.0.1:%s", "coordinates": "com.acme:demo:1.0"}' "$port" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 || fail "resolve failed: $(cat "$dir/output.json")"
[ "$(status "$dir")" = "success" ] || fail "status $(status "$dir")"
[ "$(output "$dir" resolved)" = "1" ] || fail "resolved: $(output "$dir" resolved)"
[ "$(output "$dir" snapshots)" = "0" ] || fail "snapshot count wrong"
local="$dir/.m2/repository/com/acme/demo/1.0/demo-1.0.jar"
[ -f "$local" ] || fail "no $local"
[ -f "$dir/demo-1.0.jar" ] || fail "not copied to the workspace"
python3 -c 'import hashlib,sys; a,b,c=(open(p,"rb").read() for p in sys.argv[1:]); assert a==c, "local bytes differ"; assert b==c, "copy differs"; assert hashlib.sha1(a).hexdigest()==hashlib.sha1(c).hexdigest()' "$local" "$dir/demo-1.0.jar" "$repo/com/acme/demo/1.0/demo-1.0.jar" || fail "bytes do not match the served file"
expect="$(printf '%s\tsha1=ok' "$local")"
[ "$(output "$dir" checksums)" = "$expect" ] || fail "checksums: $(output "$dir" checksums)"

# a 404 must fail with 40003, and must not leave a file in the local repository
printf '{"repoUrl": "http://127.0.0.1:%s", "coordinates": "com.acme:demo:9.9"}' "$port" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "a missing artifact was reported as success"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["status"]=="error", d; assert d["errorCode"]==40003, d; assert "/com/acme/demo/9.9/demo-9.9.jar" in d["message"], d' "$dir/output.json" || fail "404 did not report 40003 with the URL"
[ ! -e "$dir/.m2/repository/com/acme/demo/9.9/demo-9.9.jar" ] || fail "a 404 created a file"

echo "smoke ok: resolved demo-1.0.jar (sha1 verified, copied), 404 -> 40003"
