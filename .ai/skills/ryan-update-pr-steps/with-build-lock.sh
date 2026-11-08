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
# Run a command while holding a machine-wide build lock, so that only one
# Comet session compiles at a time on this 8 GB machine.
#
#   with-build-lock.sh <command> [args...]
#
# The lock is a directory created with mkdir, which is atomic. The owner file
# in it records this script's PID and start time; the pgid file records the
# command's process group, written by the command itself before it execs, so
# it is recorded even if this script is SIGKILLed right after starting it. The
# lock is stale only when this script is gone (or its PID was reused) and
# nothing is left in the command's process group, so a build that outlives a
# SIGKILLed script still holds the lock. A stale lock is removed
# only while holding a second lock ($LOCK_DIR.reap), so that two waiters
# cannot both clean up and delete each other's fresh lock. If ps cannot tell,
# an existing PID counts as alive: when in doubt, wait rather than steal.
#
# The command runs in its own process group with stdin from /dev/null. On
# HUP, INT or TERM the whole group gets TERM, then KILL if it is still running
# after a grace period, and the lock is released.
#
# Exit status is the command's, or 75 if the lock could not be acquired.
#
# Tests: test-with-build-lock.sh in this directory.

set -u

LOCK_DIR="${COMET_BUILD_LOCK:-/Users/tien-lungfu/Desktop/RyanRepos/.comet-build.lock}"
REAP_DIR="$LOCK_DIR.reap"
POLL_SECS="${COMET_BUILD_LOCK_POLL_SECS:-60}"
MAX_WAIT_SECS="${COMET_BUILD_LOCK_MAX_WAIT_SECS:-1800}"
# A lock dir without an owner file is treated as stale only after this long.
# A live holder writes the owner file right after mkdir, so a missing owner
# file means that holder crashed in between.
OWNER_GRACE_SECS="${COMET_BUILD_LOCK_OWNER_GRACE_SECS:-60}"
REAP_STALE_SECS="${COMET_BUILD_LOCK_REAP_STALE_SECS:-300}"
KILL_GRACE_SECS="${COMET_BUILD_LOCK_KILL_GRACE_SECS:-10}"
EX_LOCK=75

if [ $# -eq 0 ]; then
  echo "usage: $(basename "$0") <command> [args...]" >&2
  exit 2
fi

log() { echo "[build-lock] $*" >&2; }

now() { date +%s; }

age_secs() {
  local m
  m=$(stat -f %m "$1" 2>/dev/null) || return 1
  echo $(($(now) - m))
}

proc_start() {
  ps -o lstart= -p "$1" 2>/dev/null | sed 's/^ *//; s/ *$//'
}

is_number() {
  case "$1" in
    '' | *[!0-9]*) return 1 ;;
  esac
}

# Alive if the PID exists and, when ps can tell, still has the recorded start
# time (a different start time means the PID was reused).
pid_alive() {
  local current
  is_number "$1" || return 1
  kill -0 "$1" 2>/dev/null || return 1
  current=$(proc_start "$1")
  [ -z "$current" ] || [ -z "$2" ] || [ "$current" = "$2" ]
}

group_alive() {
  is_number "$1" || return 1
  kill -0 -- "-$1" 2>/dev/null
}

owner_field() {
  sed -n "$1p" "$LOCK_DIR/owner" 2>/dev/null
}

owner_info() {
  if [ -f "$LOCK_DIR/owner" ]; then
    owner_field '3,$'
  else
    echo "(no owner file)"
  fi
}

lock_is_stale() {
  local age pgid
  if [ ! -f "$LOCK_DIR/owner" ]; then
    age=$(age_secs "$LOCK_DIR") || return 1
    [ "$age" -ge "$OWNER_GRACE_SECS" ]
    return
  fi
  pid_alive "$(owner_field 1)" "$(owner_field 2)" && return 1
  pgid=$(cat "$LOCK_DIR/pgid" 2>/dev/null)
  if [ -n "$pgid" ]; then
    group_alive "$pgid" && return 1
    return 0
  fi
  # The owner died without a recorded process group. It may have been killed
  # between starting the command and the command writing its pgid, so give
  # the command the same grace period before treating the lock as stale.
  age=$(age_secs "$LOCK_DIR/owner") || return 1
  [ "$age" -ge "$OWNER_GRACE_SECS" ]
}

# Returns 0 if a stale lock was removed. Call inside a critical section so a
# signal cannot leave the reap lock behind.
try_reap() {
  local reaped=1 age
  if mkdir "$REAP_DIR" 2>/dev/null; then
    # Re-check under the reap lock; what we saw before may be outdated.
    if [ -d "$LOCK_DIR" ] && lock_is_stale; then
      log "removing stale lock held by: $(owner_info)"
      rm -rf "$LOCK_DIR"
      reaped=0
    fi
    rmdir "$REAP_DIR"
  elif age=$(age_secs "$REAP_DIR") && [ "$age" -ge "$REAP_STALE_SECS" ]; then
    log "reap lock $REAP_DIR is ${age}s old; another cleanup likely crashed."
    log "remove it by hand after checking no session is using it, then retry."
    exit "$EX_LOCK"
  fi
  return "$reaped"
}

my_start=$(proc_start $$)
if [ -z "$my_start" ]; then
  log "ps cannot report this script's start time; not taking the lock."
  exit "$EX_LOCK"
fi
info="$(date '+%Y-%m-%d %H:%M:%S') cwd=$PWD cmd=$*"

write_owner() {
  printf '%s\n%s\n%s\n' "$$" "$my_start" "$info" > "$LOCK_DIR/owner.tmp" \
    && mv "$LOCK_DIR/owner.tmp" "$LOCK_DIR/owner"
}

acquired=0
child=
sleeper=
# Signals that arrive inside a critical section are handled when it ends, so
# the lock is never left without an owner file and the reap lock is never
# left behind.
critical=0
pending=

release() {
  [ "$acquired" -eq 1 ] || return
  acquired=0
  if [ "$(owner_field 1)" = "$$" ]; then
    rm -rf "$LOCK_DIR"
  else
    log "lock is no longer ours; leaving it alone"
  fi
}

stop_child() {
  local ticks=0
  if [ -n "$sleeper" ]; then
    kill "$sleeper" 2>/dev/null
  fi
  [ -n "$child" ] || return
  kill -TERM -- "-$child" 2>/dev/null
  while group_alive "$child" && [ "$ticks" -lt $((KILL_GRACE_SECS * 5)) ]; do
    sleep 0.2
    ticks=$((ticks + 1))
  done
  if group_alive "$child"; then
    log "command still running ${KILL_GRACE_SECS}s after TERM; sending KILL"
    kill -KILL -- "-$child" 2>/dev/null
  fi
  wait "$child" 2>/dev/null
  child=
}

on_signal() {
  if [ "$critical" -eq 1 ]; then
    pending=$1
    return
  fi
  stop_child
  exit "$1"
}

leave_critical() {
  local p=$pending
  critical=0
  pending=
  if [ -n "$p" ]; then
    on_signal "$p"
  fi
}

trap release EXIT
trap 'on_signal 129' HUP
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

started=$(now)
while :; do
  critical=1
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    if ! write_owner; then
      rm -rf "$LOCK_DIR"
      log "failed to write $LOCK_DIR/owner"
      exit "$EX_LOCK"
    fi
    acquired=1
    leave_critical
    break
  fi
  if try_reap; then
    leave_critical
    continue
  fi
  leave_critical
  waited=$(($(now) - started))
  if [ "$waited" -ge "$MAX_WAIT_SECS" ]; then
    log "gave up after ${waited}s; lock still held by: $(owner_info)"
    exit "$EX_LOCK"
  fi
  log "waiting (${waited}s so far); lock held by: $(owner_info)"
  sleep "$POLL_SECS" &
  sleeper=$!
  wait "$sleeper"
  sleeper=
done

log "acquired $LOCK_DIR"
critical=1
# Job control puts the command in its own process group, so signals reach
# everything it starts (cargo -> rustc, mvnw -> java), and the group outlives
# this script if it is SIGKILLed.
set -m
# shellcheck disable=SC2016
sh -c 'echo $$ > "$1.tmp" && mv "$1.tmp" "$1"; shift; exec "$@"' \
  sh "$LOCK_DIR/pgid" "$@" < /dev/null &
child=$!
set +m
leave_critical
wait "$child"
status=$?
child=
exit "$status"
