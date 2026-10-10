#!/usr/bin/env python3
"""Sample the synthetic replay test's PID as it enters the real test body.

Xcode launches a discovery host before the real tests. Profiling an app name
can capture that host instead. This diagnostic follows the live test log;
it never changes test timing, selection, or exit status.
"""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import re
import signal
import subprocess
import time

REPLAY_START = re.compile(
    r"Replay profile target: pid=([1-9][0-9]*), "
    r"test=automaticLockRewindsOnceAndResumesWithoutManualSetup$"
)


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


def wait_for_replay_pid(log, stopped, timeout=300):
    deadline = time.monotonic() + timeout
    position, pending = 0, ""
    while not stopped.is_set() and time.monotonic() < deadline:
        try:
            with log.open(encoding="utf-8", errors="replace") as stream:
                stream.seek(position)
                pending += stream.read()
                position = stream.tell()
        except FileNotFoundError:
            pass
        lines = pending.split("\n")
        pending = lines.pop()
        for line in lines:
            match = REPLAY_START.search(line)
            if match:
                return int(match[1])
        stopped.wait(0.1)
    if not stopped.is_set():
        raise TimeoutError(f"No replay test PID appeared within {timeout:g} seconds.")
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


def profile(log, output, stopped, sampler="/usr/bin/sample", timeout=300):
    pid = wait_for_replay_pid(log, stopped, timeout)
    if pid is None or stopped.is_set():
        return 0
    print(f"{datetime.now(timezone.utc).isoformat()} Replay test entry observed; "
          f"sampling test PID {pid}.", flush=True)
    # Collect stacks immediately; -mayDie first resolves all loaded images,
    # which can consume the short replay window before any stack is sampled.
    child = subprocess.Popen([sampler, str(pid), "40", "10", "-file", str(output)])
    try:
        while child.poll() is None and not stopped.wait(0.1):
            pass
    finally:
        stop_sample(child)
    sampled = output.is_file() and re.search(
        r"(?m)^\s+[1-9][0-9]* Thread_", output.read_text(encoding="utf-8", errors="replace")) is not None
    print(f"Native sampler exited {child.returncode}; report has stack samples: {sampled}.", flush=True)
    return child.returncode or (0 if sampled else 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    stopped = SignalCancellation()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stopped.set())
    try:
        print("Waiting for the synthetic replay test PID.", flush=True)
        return profile(args.log, args.output, stopped)
    except (OSError, TimeoutError) as error:
        print(f"Native replay profile unavailable: {error}", flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
