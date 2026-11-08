#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# Tests for with-build-lock.sh. Uses a temporary lock path and short timeouts,
# and mock commands (sh, sleep) in place of real builds, so it never touches
# the real lock. Run: ./test-with-build-lock.sh

set -u

SCRIPT="$(cd "$(dirname "$0")" && pwd)/with-build-lock.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

export COMET_BUILD_LOCK="$TMP/lock"
export COMET_BUILD_LOCK_POLL_SECS=1
export COMET_BUILD_LOCK_MAX_WAIT_SECS=3
export COMET_BUILD_LOCK_OWNER_GRACE_SECS=60
export COMET_BUILD_LOCK_REAP_STALE_SECS=300
export COMET_BUILD_LOCK_KILL_GRACE_SECS=10
LOCK="$COMET_BUILD_LOCK"

# Fake commands, put first on PATH by individual tests.
mkdir "$TMP/bad-ps" "$TMP/slow-rm"
printf '#!/bin/sh\nexit 1\n' > "$TMP/bad-ps/ps"
printf '#!/bin/sh\nsleep 2\nexec /bin/rm "$@"\n' > "$TMP/slow-rm/rm"
chmod +x "$TMP/bad-ps/ps" "$TMP/slow-rm/rm"

pass=0
fail=0
ok() { pass=$((pass + 1)); echo "  ok: $*"; }
bad() { fail=$((fail + 1)); echo "  FAIL: $*"; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
reset() { rm -rf "$LOCK" "$LOCK.reap" "$TMP"/out "$TMP"/log "$TMP"/child "$TMP"/gc "$TMP"/err; }
run() { "$SCRIPT" "$@" 2>"$TMP/err"; }
lock_gone() { [ ! -e "$LOCK" ]; }
start_of() { ps -o lstart= -p "$1" | sed 's/^ *//; s/ *$//'; }
dead() { ! kill -0 "$1" 2>/dev/null; }
wait_for_file() { for _ in $(seq 50); do [ -s "$1" ] && return; sleep 0.1; done; }
# A lock held by this test shell, which stays alive throughout.
fake_live_lock() {
  mkdir "$LOCK"
  printf '%s\n%s\nlive-holder\n' "$$" "$(start_of $$)" > "$LOCK/owner"
}

echo "usage error"
reset
run; rc=$?
check "no args exits 2" '[ $rc -eq 2 ]'

echo "runs command, passes exit status, writes owner, releases lock"
reset
run sh -c "cat '$LOCK/owner' > '$TMP/out'; cat '$LOCK/pgid' > '$TMP/gc'; echo \$\$ > '$TMP/child'; exit 7"; rc=$?
check "exit status passed through" '[ $rc -eq 7 ]'
check "owner line 1 is a pid" 'sed -n 1p "$TMP/out" | grep -Eq "^[0-9]+$"'
check "owner line 2 is the start time" '[ -n "$(sed -n 2p "$TMP/out")" ]'
check "owner line 3 has cwd and cmd" 'sed -n 3p "$TMP/out" | grep -q "cwd=.* cmd=sh -c"'
check "pgid file is the command's process group" '[ "$(cat "$TMP/gc")" = "$(cat "$TMP/child")" ]'
check "lock released" lock_gone

echo "missing command releases lock"
reset
run no-such-command-xyz; rc=$?
check "exit 127" '[ $rc -eq 127 ]'
check "lock released" lock_gone

echo "mutual exclusion under contention"
reset
for i in 1 2 3 4; do
  COMET_BUILD_LOCK_MAX_WAIT_SECS=30 "$SCRIPT" \
    sh -c "echo start >> '$TMP/log'; sleep 0.5; echo end >> '$TMP/log'" 2>/dev/null &
done
wait
check "4 runs completed" '[ "$(grep -c start "$TMP/log")" -eq 4 ]'
check "no overlapping runs" '[ "$(tr "\n" " " < "$TMP/log")" = "start end start end start end start end " ]'
check "lock released" lock_gone

echo "stale lock: owner pid is dead"
reset
mkdir "$LOCK"
printf '99999999\nMon Jan  1 00:00:00 2024\ndead\n' > "$LOCK/owner"
touch -t 202401010000 "$LOCK/owner"
run echo ran > "$TMP/out"; rc=$?
check "reaped and ran" '[ $rc -eq 0 ] && grep -q ran "$TMP/out"'
check "logged removal" 'grep -q "removing stale lock held by: dead" "$TMP/err"'
check "lock released" lock_gone

echo "stale lock: pid reused by another process"
reset
mkdir "$LOCK"
printf '%s\nMon Jan  1 00:00:00 2024\nreused\n' "$$" > "$LOCK/owner"
touch -t 202401010000 "$LOCK/owner"
run true; rc=$?
check "reaped and ran" '[ $rc -eq 0 ]'
check "lock released" lock_gone

echo "stale lock: no owner file, older than grace"
reset
mkdir "$LOCK"
touch -t 202401010000 "$LOCK"
run true; rc=$?
check "reaped and ran" '[ $rc -eq 0 ]'
check "lock released" lock_gone

echo "live lock: no owner file, within grace"
reset
mkdir "$LOCK"
run echo ran > "$TMP/out"; rc=$?
check "gave up with 75" '[ $rc -eq 75 ]'
check "command did not run" '! grep -q ran "$TMP/out"'
check "lock left in place" '[ -d "$LOCK" ]'

echo "live lock: owner process alive"
reset
fake_live_lock
cp "$LOCK/owner" "$TMP/owner.before"
run echo ran > "$TMP/out"; rc=$?
check "gave up with 75" '[ $rc -eq 75 ]'
check "command did not run" '! grep -q ran "$TMP/out"'
check "owner untouched" 'cmp -s "$LOCK/owner" "$TMP/owner.before"'
check "logged holder" 'grep -q "lock still held by: live-holder" "$TMP/err"'

echo "live lock: ps fails, so the owner counts as alive"
reset
fake_live_lock
PATH="$TMP/bad-ps:$PATH" COMET_BUILD_LOCK_MAX_WAIT_SECS=0 "$SCRIPT" echo ran > "$TMP/out" 2>/dev/null
check "lock not stolen" '[ -f "$LOCK/owner" ] && ! grep -q ran "$TMP/out"'

echo "owner died before the command recorded its process group"
reset
mkdir "$LOCK"
printf '99999999\nMon Jan  1 00:00:00 2024\nno-pgid\n' > "$LOCK/owner"
run echo ran > "$TMP/out"; rc=$?
check "within grace: not reaped" '[ $rc -eq 75 ] && ! grep -q ran "$TMP/out" && [ -f "$LOCK/owner" ]'
touch -t 202401010000 "$LOCK/owner"
run echo ran > "$TMP/out"; rc=$?
check "after grace: reaped and ran" '[ $rc -eq 0 ] && grep -q ran "$TMP/out"'
check "lock released" lock_gone

echo "ps fails when taking the lock"
reset
PATH="$TMP/bad-ps:$PATH" run echo ran > "$TMP/out"; rc=$?
check "refuses with 75" '[ $rc -eq 75 ] && ! grep -q ran "$TMP/out"'
check "no lock left" lock_gone

echo "reap lock left by a crashed cleanup"
reset
mkdir "$LOCK" "$LOCK.reap"
touch -t 202401010000 "$LOCK.reap"
run true; rc=$?
check "exits 75" '[ $rc -eq 75 ]'
check "lock left in place" '[ -d "$LOCK" ]'
check "reap lock left in place" '[ -d "$LOCK.reap" ]'

echo "release leaves a lock that is no longer ours"
reset
run sh -c "printf '1\n\nother\n' > '$LOCK/owner'"
check "lock left in place" '[ -d "$LOCK" ] && grep -q other "$LOCK/owner"'
check "logged it" 'grep -q "no longer ours" "$TMP/err"'

for sig in TERM INT HUP; do
  case $sig in TERM) want=143 ;; INT) want=130 ;; HUP) want=129 ;; esac
  echo "$sig while running stops the command and its children, releases the lock"
  reset
  # Without job control, bash starts background jobs with INT ignored, and
  # a signal ignored on entry cannot be trapped. Agents run the script in the
  # foreground, so start it the same way it would see signals there.
  set -m
  "$SCRIPT" sh -c "echo \$\$ > '$TMP/child'; sleep 30 & echo \$! > '$TMP/gc'; wait" 2>/dev/null &
  holder=$!
  set +m
  wait_for_file "$TMP/gc"
  t0=$(date +%s)
  kill -s "$sig" "$holder"
  wait "$holder"; rc=$?
  elapsed=$(($(date +%s) - t0))
  check "exit $want" '[ $rc -eq $want ]'
  check "returned within 5s (took ${elapsed}s)" '[ $elapsed -lt 5 ]'
  check "command stopped" 'dead "$(cat "$TMP/child")"'
  check "command's child stopped" 'dead "$(cat "$TMP/gc")"'
  check "lock released" lock_gone
done

echo "command that ignores TERM gets KILL after the grace period"
reset
COMET_BUILD_LOCK_KILL_GRACE_SECS=1 "$SCRIPT" \
  sh -c "trap '' TERM; echo \$\$ > '$TMP/child'; while :; do sleep 0.2; done" 2>"$TMP/err" &
holder=$!
wait_for_file "$TMP/child"
t0=$(date +%s)
kill -TERM "$holder"
wait "$holder"; rc=$?
elapsed=$(($(date +%s) - t0))
check "exit 143" '[ $rc -eq 143 ]'
check "returned within 5s (took ${elapsed}s)" '[ $elapsed -lt 5 ]'
check "command killed" 'dead "$(cat "$TMP/child")"'
check "logged KILL" 'grep -q "sending KILL" "$TMP/err"'
check "lock released" lock_gone

echo "SIGKILLed script: the build keeps the lock until it finishes"
reset
"$SCRIPT" sh -c "sleep 3; echo first-end >> '$TMP/log'" 2>/dev/null &
holder=$!
wait_for_file "$LOCK/pgid"
kill -KILL "$holder"
wait "$holder" 2>/dev/null
COMET_BUILD_LOCK_MAX_WAIT_SECS=15 run sh -c "echo second >> '$TMP/log'"; rc=$?
check "second run succeeded" '[ $rc -eq 0 ]'
check "second run waited for the orphaned build" '[ "$(tr "\n" " " < "$TMP/log")" = "first-end second " ]'
check "then reaped the lock" 'grep -q "removing stale lock" "$TMP/err"'
check "lock released" lock_gone

echo "TERM during cleanup of a stale lock leaves no reap lock"
reset
mkdir "$LOCK"
printf '99999999\nMon Jan  1 00:00:00 2024\ndead\n' > "$LOCK/owner"
touch -t 202401010000 "$LOCK/owner"
PATH="$TMP/slow-rm:$PATH" "$SCRIPT" echo ran > "$TMP/out" 2>/dev/null &
waiter=$!
sleep 1
kill -TERM "$waiter"
wait "$waiter"; rc=$?
check "exit 143" '[ $rc -eq 143 ]'
check "command did not run" '! grep -q ran "$TMP/out"'
check "no reap lock left" '[ ! -e "$LOCK.reap" ]'
check "no lock left" lock_gone

echo "TERM while waiting does not touch the holder's lock"
reset
fake_live_lock
# An unusual poll interval so the leftover check below cannot match an
# unrelated sleep on this machine.
COMET_BUILD_LOCK_MAX_WAIT_SECS=60 COMET_BUILD_LOCK_POLL_SECS=31.7 "$SCRIPT" true 2>/dev/null &
waiter=$!
sleep 1
t0=$(date +%s)
kill -TERM "$waiter"
wait "$waiter"; rc=$?
elapsed=$(($(date +%s) - t0))
check "exit 143" '[ $rc -eq 143 ]'
check "returned within 5s (took ${elapsed}s)" '[ $elapsed -lt 5 ]'
check "holder's lock left in place" 'grep -q live-holder "$LOCK/owner"'
check "no leftover poll sleep" '! pgrep -f "^sleep 31.7$" > /dev/null'

echo
echo "passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
