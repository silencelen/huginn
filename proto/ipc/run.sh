#!/usr/bin/env bash
# Prototype Q1 runner: build the Go server, start it on a private socket, run
# the JVM probe against it with the app's pinned OkHttp, then a peer-uid check
# as another user. Exit status = the probe's.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${1:-$(mktemp -d)}"
M2="$HOME/.gradle/caches/modules-2/files-2.1"
CP="$(ls "$M2"/com.squareup.okhttp3/okhttp/4.12.0/*/okhttp-4.12.0.jar):$(ls "$M2"/com.squareup.okio/okio-jvm/3.9.0/*/okio-jvm-3.9.0.jar):$(ls "$M2"/org.jetbrains.kotlin/kotlin-stdlib/1.9.24/*/kotlin-stdlib-1.9.24.jar)"
SOCK="$WORK/run/huginn/core.sock"

(cd "$HERE/server" && go build -o "$WORK/ipcserver" .)
javac -d "$WORK/classes" -cp "$CP" "$HERE/client/UnixSocketProbe.java"

"$WORK/ipcserver" "$SOCK" >"$WORK/server.log" 2>&1 &
SRV=$!
trap 'kill $SRV 2>/dev/null || true' EXIT
for _ in $(seq 50); do [ -S "$SOCK" ] && break; sleep 0.1; done
stat -c '%a %U %n' "$(dirname "$SOCK")" "$SOCK"

set +e
timeout 90 java -cp "$WORK/classes:$CP" UnixSocketProbe "$SOCK"
RC=$?
set -e

# Peer check, independent of the file modes: a second server in a directory
# another user can traverse, modes loosened, then connect as nobody and expect
# the server to drop the connection before answering a byte.
PD="$(mktemp -d /dev/shm/huginn-q1.XXXXXX)"
"$WORK/ipcserver" "$PD/run/core.sock" >"$WORK/peer.log" 2>&1 &
SRV2=$!
trap 'kill $SRV $SRV2 2>/dev/null || true; rm -rf "$PD"' EXIT
for _ in $(seq 50); do [ -S "$PD/run/core.sock" ] && break; sleep 0.1; done
chmod 0711 "$PD" "$PD/run"; chmod 0666 "$PD/run/core.sock"
OUT="$(runuser -u nobody -- python3 -I -c '
import socket,sys
s=socket.socket(socket.AF_UNIX); s.connect(sys.argv[1])
s.sendall(b"GET /core/status HTTP/1.1\r\nHost: x\r\nX-Huginn-Core-Proto: 1\r\n\r\n")
try:
    print("EOF" if s.recv(200) == b"" else "ANSWERED")
except ConnectionResetError:
    print("RST")' "$PD/run/core.sock" 2>&1 || true)"
# EOF or RST are both "dropped before a byte was answered" (RST when the
# server closes with the peer's request still unread in its buffer).
if [ "$OUT" = "EOF" ] || [ "$OUT" = "RST" ]; then echo "PASS  other-uid peer dropped            ($OUT, no byte answered)"; else echo "FAIL  other-uid peer dropped            got: $OUT"; RC=1; fi
grep -h -E 'refused|went away' "$WORK/server.log" "$WORK/peer.log" || true
exit $RC
