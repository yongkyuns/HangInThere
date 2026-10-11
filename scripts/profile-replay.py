#!/usr/bin/env python3
"""Sample the synthetic replay test's PID as it enters the real test body.

Xcode launches a discovery host before the real tests. Profiling an app name
can capture that host instead. This diagnostic follows the live test log;
it never changes test timing, selection, or exit status.
"""
import argparse
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
import re
import signal
import subprocess
import time

REPLAY_TESTS = {
    "automatic": "automaticLockRewindsOnceAndResumesWithoutManualSetup",
    "manual": "manualSetupCancelsAnAutomaticResultAlreadyInFlight",
}


class SignalCancellation:
    """A signal handler must never acquire a lock interrupted code may hold."""
    def __init__(self):
        self.requested = False

    def set(self):
        self.requested = True

    def is_set(self):
        return self.requested

    def wait(self, seconds):
        if not self.requested:
            time.sleep(seconds)
        return self.requested


class ReplayLogTail:
    """Keep unread lines when test entry and completion arrive in one poll."""
    def __init__(self, log):
        self.log = log
        self.position = 0
        self.pending = ""
        self.lines = deque()

    def read_lines(self):
        try:
            with self.log.open(encoding="utf-8", errors="replace") as stream:
                stream.seek(self.position)
                self.pending += stream.read()
                self.position = stream.tell()
        except FileNotFoundError:
            pass
        lines = self.pending.split("\n")
        self.pending = lines.pop()
        self.lines.extend(lines)
        while self.lines:
            yield self.lines.popleft()


def wait_for_replay_pid(log, stopped, timeout=300, wait_for_test_start=False,
                        replay_test="automatic", tail=None):
    name = re.escape(REPLAY_TESTS[replay_test])
    replay_start = re.compile(r"Replay profile target: pid=([1-9][0-9]*), test=" + name + r"$")
    test_start = re.compile(r"Test " + name + r"\(\) started\.$")
    # Cold simulator launch/discovery can outlast the PID timeout even after
    # compilation. The runner owns this pre-test wait and cancels us on exit;
    # arm the same bounded PID wait only when the selected test actually starts.
    deadline = None if wait_for_test_start else time.monotonic() + timeout
    tail = tail if tail is not None else ReplayLogTail(log)
    while not stopped.is_set():
        if deadline is not None and time.monotonic() >= deadline:
            raise TimeoutError(f"No replay test PID appeared within {timeout:g} seconds.")
        for line in tail.read_lines():
            match = replay_start.search(line)
            if match:
                return int(match[1])
            if deadline is None and test_start.search(line):
                deadline = time.monotonic() + timeout
        stopped.wait(0.1)
    return None


def stop_sample(child):
    if child.poll() is None:
        child.send_signal(signal.SIGINT)  # Finalizes the partial call graph.
        try:
            # Symbolication of an app's loaded frameworks can outlast a short
            # test. Allow report finalization before escalating, still bounded.
            child.wait(timeout=30)
        except subprocess.TimeoutExpired:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)


def profile(log, output, stopped, sampler="/usr/bin/sample", timeout=300,
            wait_for_test_start=False, replay_test="automatic"):
    tail = ReplayLogTail(log)
    pid = wait_for_replay_pid(log, stopped, timeout, wait_for_test_start, replay_test, tail)
    if pid is None or stopped.is_set():
        return 0
    completion = re.compile(r"Replay profile complete: pid=" + str(pid) + r", test=" +
                            re.escape(REPLAY_TESTS[replay_test]) +
                            r", unixSeconds=([0-9]+(?:\.[0-9]+)?), wallMilliseconds=([0-9]+(?:\.[0-9]+)?)$")

    def completed():
        for line in tail.read_lines():
            if completion.search(line):
                print(f"{datetime.now(timezone.utc).isoformat()} completion observed: {line}", flush=True)
                return True
        return False

    # Delayed log delivery can make entry and completion visible together.
    # Attaching now would collect an adjacent test, not the selected replay.
    if completed():
        print("Selected replay already completed; no sampler was launched.", flush=True)
        return 1
    print(f"{datetime.now(timezone.utc).isoformat()} {REPLAY_TESTS[replay_test]} entry observed; "
          f"requesting sampler launch for test PID {pid}.", flush=True)
    # Collect stacks immediately; -mayDie first resolves all loaded images,
    # which can consume the short replay window before any stack is sampled.
    child = subprocess.Popen([sampler, str(pid), "40", "10", "-file", str(output)])
    reason = "sampler exited"
    try:
        while child.poll() is None:
            if stopped.is_set():
                reason = "runner stopped"
                break
            if completed():
                reason = "selected replay completed"
                break
            stopped.wait(0.1)
    finally:
        print(f"{datetime.now(timezone.utc).isoformat()} capture stop requested: {reason}.", flush=True)
        stop_sample(child)
    sampled = output.is_file() and re.search(
        r"(?m)^\s+[1-9][0-9]* Thread_", output.read_text(encoding="utf-8", errors="replace")) is not None
    print(f"Native sampler exited {child.returncode}; report has stack samples: {sampled}.", flush=True)
    return child.returncode or (0 if sampled else 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--replay-test", choices=REPLAY_TESTS, default="automatic",
                        help="Select the diagnostic attachment target; all mechanics tests still run.")
    parser.add_argument("--wait-for-test-start", action="store_true",
                        help="Start the five-minute PID timeout at the real test entry; "
                             "the test runner bounds cold simulator startup.")
    args = parser.parse_args()
    stopped = SignalCancellation()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stopped.set())
    try:
        print("Waiting for the synthetic replay test PID.", flush=True)
        return profile(args.log, args.output, stopped,
                       wait_for_test_start=args.wait_for_test_start, replay_test=args.replay_test)
    except (OSError, TimeoutError) as error:
        print(f"Native replay profile unavailable: {error}", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
