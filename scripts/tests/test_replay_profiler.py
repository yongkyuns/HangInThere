"""Diagnostic process routing/lifecycle contracts; no Apple decoder is mocked."""
import importlib.util
from pathlib import Path
import select
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("replay_profiler", ROOT / "scripts/profile-replay.py")
profiler = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(profiler)


class ReplayProfilerTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        self.log = self.root / "tests.txt"
        self.output = self.root / "profile.txt"
        self.stopped = threading.Event()

    def marker(self, pid=1234):
        return (f"Replay stage: pid={pid}, wallMilliseconds=1.0, "
                "stage=preparation, boundary=began, sourcePTS=none, stageMilliseconds=none\n")

    def test_waits_for_real_preparation_and_complete_log_line(self):
        self.log.write_text("HangInThere[999:99] starting discovery\n" +
                            self.marker(0) + self.marker().replace("stage=preparation", "stage=context") +
                            self.marker().replace("boundary=began", "boundary=ended") +
                            self.marker().replace("pid=1234, ", "") + self.marker(4321)[:-1])
        result = []
        worker = threading.Thread(target=lambda: result.append(
            profiler.wait_for_replay_pid(self.log, self.stopped, timeout=2)))
        worker.start()
        self.addCleanup(self.stopped.set)
        self.addCleanup(lambda: worker.join(timeout=3))
        time.sleep(0.15)
        self.assertTrue(worker.is_alive(), "A partial or unrelated line must not attach a sampler.")
        with self.log.open("a") as stream:
            stream.write("\n")
        worker.join(timeout=2)
        self.assertEqual(result, [4321])

    def test_missing_marker_times_out_instead_of_profiling_discovery(self):
        self.log.write_text("HangInThere[999:99] starting discovery\n")
        with self.assertRaises(TimeoutError):
            profiler.wait_for_replay_pid(self.log, self.stopped, timeout=0.01)

    def test_cancelled_wait_does_not_start_a_sampler(self):
        self.stopped.set()
        self.assertEqual(profiler.profile(self.log, self.output, self.stopped,
                                         sampler="must-never-run"), 0)

    def test_actual_child_receives_test_pid_and_flushes_on_cancellation(self):
        self.log.write_text(self.marker(4321))
        sampler = self.root / "fake-sample"
        sampler.write_text(f"#!{sys.executable}\n" + '''import signal, sys, time
from pathlib import Path
report = Path(sys.argv[-1])
def finish(*_):
    report.write_text('flushed PID ' + sys.argv[1] + '\\nCall graph:\\n    1 Thread_1: Fake test stack\\n')
    raise SystemExit(0)
signal.signal(signal.SIGINT, finish)
report.with_suffix('.ready').touch()
while True: time.sleep(0.01)
''')
        sampler.chmod(0o755)
        timer = threading.Timer(3, self.stopped.set)
        timer.start()
        self.addCleanup(timer.cancel)
        canceller = threading.Thread(target=lambda: self.cancel_when_ready(self.output.with_suffix(".ready")))
        canceller.start()
        self.addCleanup(lambda: canceller.join(timeout=3))
        self.assertEqual(profiler.profile(self.log, self.output, self.stopped, sampler=str(sampler)), 0)
        self.assertEqual(self.output.read_text(), "flushed PID 4321\nCall graph:\n    1 Thread_1: Fake test stack\n")

    def cancel_when_ready(self, ready):
        while not ready.exists() and not self.stopped.wait(0.01):
            pass
        self.stopped.set()

    def test_unresponsive_sampler_is_reaped_with_bounded_waits(self):
        child = Mock()
        child.poll.return_value = None
        child.wait.side_effect = [subprocess.TimeoutExpired("sample", 30),
                                  subprocess.TimeoutExpired("sample", 5), 0]
        profiler.stop_sample(child)
        child.send_signal.assert_called_once_with(signal.SIGINT)
        child.terminate.assert_called_once()
        child.kill.assert_called_once()
        self.assertEqual(child.wait.call_count, 3)
        self.assertEqual([call.kwargs for call in child.wait.call_args_list],
                         [{"timeout": 30}, {"timeout": 5}, {"timeout": 5}])

    def test_zero_exit_without_report_is_not_successful_evidence(self):
        self.log.write_text(self.marker())
        child = Mock(returncode=0)
        child.poll.return_value = 0
        with patch.object(profiler.subprocess, "Popen", return_value=child):
            self.assertEqual(profiler.profile(self.log, self.output, self.stopped), 1)

    def test_empty_call_graph_is_not_successful_evidence(self):
        self.log.write_text(self.marker())
        self.output.write_text("Call graph:\n\nBinary Images:\nloaded libraries\n")
        child = Mock(returncode=0)
        child.poll.return_value = 0
        with patch.object(profiler.subprocess, "Popen", return_value=child):
            self.assertEqual(profiler.profile(self.log, self.output, self.stopped), 1)

    def test_cli_termination_exits_a_waiting_watcher_promptly(self):
        child = subprocess.Popen([sys.executable, str(ROOT / "scripts/profile-replay.py"),
                                  "--log", str(self.log), "--output", str(self.output)],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(lambda: child.kill() if child.poll() is None else None)
        self.assertTrue(select.select([child.stdout], [], [], 3)[0], "Watcher never became ready.")
        self.assertIn("Waiting for the synthetic replay", child.stdout.readline())
        child.terminate()
        stdout, stderr = child.communicate(timeout=3)
        self.assertEqual(child.returncode, 0, stdout + stderr)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
