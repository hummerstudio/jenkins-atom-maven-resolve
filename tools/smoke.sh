#!/usr/bin/env bash
# What this atom must get right is not "a file appeared": it is the *request* it makes (the Maven 2 layout path), the
# bytes it stores, and what it says when the repository misbehaves. So the repository here is a real HTTP server over a
# fixture tree, and every expectation is recomputed from the bytes on disk rather than read back from the atom.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

need_cmd python3 || fail "python3 is required to serve the fake repository"

sha_of() {   # sha_of FILE sha1|sha256
  if command -v "$2"sum >/dev/null 2>&1; then "$2"sum "$1" | cut -d' ' -f1
  elif [ "$2" = "sha1" ]; then shasum -a 1 "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1
  fi
}
basic() { python3 -c 'import base64,sys;print("Basic "+base64.b64encode(sys.argv[1].encode()).decode())' "$1"; }

tmp="$(mktemp -d)"
gate="$(mktemp -d)"
ws="$gate/ws"
repo="$tmp/repo"
mkdir -p "$repo" "$ws"
pids=()
cleanup() {
  local pid
  for pid in "${pids[@]:-}"; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null
  done
  wait 2>/dev/null
}
trap cleanup EXIT

put() {      # put PATH   (body on stdin)
  mkdir -p "$repo/$(dirname "$1")"
  cat >"$repo/$1"
}
publish() {  # publish PATH [ALGORITHM ...] — the checksum files a repository publishes next to the artifact
  local path="$1" algorithm
  shift
  local algorithms=("$@")
  [ ${#algorithms[@]} -eq 0 ] && algorithms=(sha1)
  for algorithm in "${algorithms[@]}"; do
    sha_of "$repo/$path" "$algorithm" >"$repo/$path.$algorithm"
  done
}
body() {     # body TEXT COUNT
  local i=0
  while [ "$i" -lt "$2" ]; do printf '%s\n' "$1"; i=$((i + 1)); done
}

# ---------------------------------------------------------------- the fixture repository (Maven 2 layout)
body 'widget-1.0.0 jar bytes' 40 | put com/acme/widget/1.0.0/widget-1.0.0.jar
publish com/acme/widget/1.0.0/widget-1.0.0.jar sha1
body 'widget sources bytes' 7 | put com/acme/widget/1.0.0/widget-1.0.0-sources.jar
publish com/acme/widget/1.0.0/widget-1.0.0-sources.jar sha1
body 'bundle zip bytes' 5 | put com/acme/bundle/2.0/bundle-2.0.zip
publish com/acme/bundle/2.0/bundle-2.0.zip sha1

body 'badsha bytes' 9 | put com/acme/badsha/1.0/badsha-1.0.jar
printf 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef\n' >"$repo/com/acme/badsha/1.0/badsha-1.0.jar.sha1"

body 'sha256 only bytes' 11 | put com/acme/sha256only/1.0/sha256only-1.0.jar
publish com/acme/sha256only/1.0/sha256only-1.0.jar sha256

body 'unchecked bytes' 3 | put com/acme/unchecked/1.0/unchecked-1.0.jar

body 'slow bytes' 4 | put com/acme/slow/1.0/slow-1.0.jar
publish com/acme/slow/1.0/slow-1.0.jar sha1

body 'secret bytes' 6 | put com/acme/secret/1.0/secret-1.0.jar
publish com/acme/secret/1.0/secret-1.0.jar sha1

body 'truncated bytes' 200 | put com/acme/truncated/1.0/truncated-1.0.jar
publish com/acme/truncated/1.0/truncated-1.0.jar sha1

start_repo() {  # start_repo NAME [server args ...]; sets SERVER_URL and SERVER_LOG
  local name="$1"
  local port_file="$tmp/$name.port"
  shift
  SERVER_LOG="$tmp/$name.log"
  python3 tools/fake_maven_repo.py --root "$repo" --log "$SERVER_LOG" --port-file "$port_file" "$@" &
  pids+=($!)
  local _
  for _ in $(seq 1 100); do [ -s "$port_file" ] && break; sleep 0.05; done
  [ -s "$port_file" ] || fail "fake repository '$name' did not start"
  SERVER_URL="http://127.0.0.1:$(cat "$port_file")"
}

start_repo plain
plain="$SERVER_URL"
plain_log="$SERVER_LOG"
start_repo auth --require-basic builder:s3cret
auth_url="$SERVER_URL"
auth_log="$SERVER_LOG"
start_repo slow --delay 3
slow_url="$SERVER_URL"
start_repo broken --truncate /com/acme/truncated/1.0/truncated-1.0.jar
broken_url="$SERVER_URL"

new_case() {    # a clean workspace and clean request logs, same servers
  rm -rf "$ws"
  mkdir -p "$ws"
  : >"$plain_log"
  : >"$auth_log"
}
input() { printf '%s' "$1" >"$ws/input.json"; }
requested() { grep -q "\"path\": \"$1\"," "$plain_log"; }

# --- 1. a release jar: the exact layout path, the exact bytes, and the sha1 checked against those bytes
new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"com.acme:widget:1.0.0\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "release: $(cat "$ws/output.json")"
[ "$(status "$ws")" = "success" ] || fail "release status: $(cat "$ws/output.json")"
[ "$(output "$ws" resolved)" = "1" ] || fail "resolved: $(cat "$ws/output.json")"
[ "$(output "$ws" bytes)" = "$(wc -c <"$repo/com/acme/widget/1.0.0/widget-1.0.0.jar" | tr -d ' ')" ] \
  || fail "byte count: $(cat "$ws/output.json")"
requested "/com/acme/widget/1.0.0/widget-1.0.0.jar" || fail "the atom did not GET the layout path: $(cat "$plain_log")"
grep -q '"userAgent": "jenkins-atom-maven-resolve"' "$plain_log" || fail "user agent: $(cat "$plain_log")"
local_repo="$ws/.m2/repository/com/acme/widget/1.0.0/widget-1.0.0.jar"
cmp -s "$local_repo" "$repo/com/acme/widget/1.0.0/widget-1.0.0.jar" || fail "local repository bytes differ from what was served"
cmp -s "$ws/widget-1.0.0.jar" "$repo/com/acme/widget/1.0.0/widget-1.0.0.jar" \
  || fail "workspace copy differs from what was served"
grep -q "sha1=ok" <<<"$(output "$ws" checksums)" || fail "checksum not verified: $(output "$ws" checksums)"
grep -q "com.acme:widget=1.0.0" <<<"$(output "$ws" versions)" || fail "versions: $(output "$ws" versions)"
# the .sha1 is a repository detail: it must not be copied into the workspace as an artifact
[ "$(output "$ws" targets | wc -l | tr -d ' ')" = "1" ] || fail "more than one workspace copy: $(output "$ws" targets)"

# --- 2. classifier and extension change the file name, not the directory
new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"com.acme:widget:1.0.0:jar:sources\\ncom.acme:bundle:2.0:zip\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "classifier/extension: $(cat "$ws/output.json")"
requested "/com/acme/widget/1.0.0/widget-1.0.0-sources.jar" || fail "classifier path: $(cat "$plain_log")"
requested "/com/acme/bundle/2.0/bundle-2.0.zip" || fail "extension path: $(cat "$plain_log")"
[ -f "$ws/widget-1.0.0-sources.jar" ] || fail "classifier artifact not copied"
[ -f "$ws/bundle-2.0.zip" ] || fail "extension artifact not copied"
[ "$(output "$ws" resolved)" = "2" ] || fail "two artifacts: $(cat "$ws/output.json")"

# --- 3. targetFileName renames the copy, and may create sub-directories
new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"com.acme:widget:1.0.0\", \"targetFileName\": \"libs/app.jar\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "targetFileName: $(cat "$ws/output.json")"
[ -f "$ws/libs/app.jar" ] || fail "targetFileName was not honoured: $(cat "$ws/output.json")"
cmp -s "$ws/libs/app.jar" "$repo/com/acme/widget/1.0.0/widget-1.0.0.jar" || fail "renamed copy has the wrong bytes"
grep -q "$ws/libs/app.jar" <<<"$(output "$ws" targets)" || fail "targets: $(output "$ws" targets)"

# --- 4. a checksum that disagrees with the bytes fails the step, and leaves no artifact behind
new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"com.acme:badsha:1.0\"}"
run "$ws" >"$ws/atom.log" 2>&1 && fail "a bad sha1 must fail the step"
grep -q '"errorCode":40006' "$ws/output.json" || fail "expected 40006: $(cat "$ws/output.json")"
grep -q "checksum mismatch" "$ws/output.json" || fail "the failure must say what disagreed: $(cat "$ws/output.json")"
[ ! -f "$ws/badsha-1.0.jar" ] || fail "a corrupt artifact reached the workspace"
if find "$ws/.m2" -name '*.part' 2>/dev/null | grep -q .; then fail "a .part file was left behind"; fi

# ...and checksumPolicy=warn keeps the bytes but still reports the disagreement
new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"com.acme:badsha:1.0\", \"checksumPolicy\": \"warn\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "checksumPolicy=warn: $(cat "$ws/output.json")"
grep -q "sha1=mismatch" <<<"$(output "$ws" checksums)" || fail "warn must report the mismatch: $(output "$ws" checksums)"
[ -f "$ws/badsha-1.0.jar" ] || fail "warn must keep the downloaded bytes"

# --- 5. sha256 when that is all the repository publishes; and no checksum at all
new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"com.acme:sha256only:1.0\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "sha256 only: $(cat "$ws/output.json")"
grep -q "sha256=ok" <<<"$(output "$ws" checksums)" || fail "sha256 was not used: $(output "$ws" checksums)"
[ -f "$ws/sha256only-1.0.jar" ] || fail "sha256-only artifact missing"

new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"com.acme:unchecked:1.0\"}"
run "$ws" >"$ws/atom.log" 2>&1 && fail "an unverifiable artifact must fail by default"
grep -q '"errorCode":40005' "$ws/output.json" || fail "expected 40005: $(cat "$ws/output.json")"
new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"com.acme:unchecked:1.0\", \"checksumPolicy\": \"ignore\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "checksumPolicy=ignore: $(cat "$ws/output.json")"
grep -q "ignored" <<<"$(output "$ws" checksums)" || fail "ignore must say so: $(output "$ws" checksums)"

# --- 6. a 404 names the URL it asked for, and is a repository problem, not a caller mistake
new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"com.acme:absent:9.9\"}"
run "$ws" >"$ws/atom.log" 2>&1 && fail "a missing artifact must fail the step"
grep -q '"errorCode":40003' "$ws/output.json" || fail "expected 40003: $(cat "$ws/output.json")"
grep -q "not found: .*/com/acme/absent/9.9/absent-9.9.jar" "$ws/output.json" || fail "404 message: $(cat "$ws/output.json")"
grep -q '"errorType":2' "$ws/output.json" || fail "404 is a repository problem (2): $(cat "$ws/output.json")"

# --- 7. 401 is a credentials problem: refused without a credential, working with a bound one, and the password
#        reaches the repository as a Basic header -- and never the outputs
new_case
input "{\"repoUrl\": \"$auth_url\", \"coordinates\": \"com.acme:secret:1.0\"}"
run "$ws" >"$ws/atom.log" 2>&1 && fail "401 without credentials must fail the step"
grep -q '"errorCode":40004' "$ws/output.json" || fail "expected 40004: $(cat "$ws/output.json")"
grep -q '"errorType":1' "$ws/output.json" || fail "401 is the caller's problem (1): $(cat "$ws/output.json")"
grep -q "HTTP 401" "$ws/output.json" || fail "401 not reported: $(cat "$ws/output.json")"

new_case
printf '{"username": "builder", "password": "s3cret"}' >"$ws/.secrets.json"
input "{\"repoUrl\": \"$auth_url\", \"coordinates\": \"com.acme:secret:1.0\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "bound credential: $(cat "$ws/output.json")"
grep -q "\"authorization\": \"$(basic builder:s3cret)\"" "$auth_log" || fail "basic auth header: $(cat "$auth_log")"
[ -f "$ws/secret-1.0.jar" ] || fail "authenticated artifact missing"
[ ! -f "$ws/.secrets.json" ] || fail "the runner must delete .secrets.json"
if grep -q "s3cret" "$ws/output.json"; then fail "the password leaked into output.json"; fi

# --- 8. failOnError=false resolves what it can and reports the rest
new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"com.acme:absent:9.9\\ncom.acme:widget:1.0.0\", \"failOnError\": \"false\"}"
run "$ws" >"$ws/atom.log" 2>&1 || fail "failOnError=false must not fail: $(cat "$ws/output.json")"
[ "$(output "$ws" resolved)" = "1" ] || fail "resolved: $(cat "$ws/output.json")"
[ "$(output "$ws" failed)" = "1" ] || fail "failed: $(cat "$ws/output.json")"
grep -q "not found" <<<"$(output "$ws" errors)" || fail "errors: $(output "$ws" errors)"
[ -f "$ws/widget-1.0.0.jar" ] || fail "the resolvable artifact was not copied"

# --- 9. a slow repository times out as a timeout, not as a stack trace or a hang
new_case
input "{\"repoUrl\": \"$slow_url\", \"coordinates\": \"com.acme:slow:1.0\", \"timeoutSeconds\": \"1\"}"
started=$(date +%s)
run "$ws" >"$ws/atom.log" 2>&1 && fail "a hanging repository must fail the step"
[ $(( $(date +%s) - started )) -lt 10 ] || fail "the timeout did not fire"
grep -q '"errorCode":40013' "$ws/output.json" || fail "expected 40013: $(cat "$ws/output.json")"
grep -q "timed out after 1s" "$ws/output.json" || fail "the failure must say it timed out: $(cat "$ws/output.json")"

# --- 10. a short read is not mistaken for an artifact
new_case
input "{\"repoUrl\": \"$broken_url\", \"coordinates\": \"com.acme:truncated:1.0\"}"
run "$ws" >"$ws/atom.log" 2>&1 && fail "a truncated download must fail the step"
grep -q "truncated download" "$ws/output.json" || fail "truncation not reported: $(cat "$ws/output.json")"
[ ! -f "$ws/truncated-1.0.jar" ] || fail "a truncated file reached the workspace"

# --- 11. bad input is named, and a target cannot leave the workspace
new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"\"}"
run "$ws" >"$ws/atom.log" 2>&1 && fail "empty coordinates must be refused"
grep -q '"errorCode":40001' "$ws/output.json" || fail "expected 40001: $(cat "$ws/output.json")"
new_case
input "{\"repoUrl\": \"\", \"coordinates\": \"com.acme:widget:1.0.0\"}"
run "$ws" >"$ws/atom.log" 2>&1 && fail "an empty repoUrl must be refused"
grep -q '"errorCode":40002' "$ws/output.json" || fail "expected 40002: $(cat "$ws/output.json")"
new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"com.acme:widget:1.0.0\\ncom.acme:bundle:2.0:zip\", \"targetFileName\": \"one.jar\"}"
run "$ws" >"$ws/atom.log" 2>&1 && fail "targetFileName with two coordinates must be refused"
grep -q '"errorCode":40009' "$ws/output.json" || fail "expected 40009: $(cat "$ws/output.json")"
new_case
input "{\"repoUrl\": \"$plain\", \"coordinates\": \"com.acme:widget:1.0.0\", \"targetFileName\": \"../evil.jar\"}"
run "$ws" >"$ws/atom.log" 2>&1 && fail "an escaping targetFileName must be refused"
grep -q '"errorCode":40010' "$ws/output.json" || fail "expected 40010: $(cat "$ws/output.json")"
[ ! -f "$gate/evil.jar" ] || fail "a file was written outside the workspace"

echo "smoke ok: layout path, bytes, sha1/sha256 verification, classifier/extension, targetFileName, checksum policies, 404, 401+basic auth, failOnError, timeout, truncated download, bad input"
