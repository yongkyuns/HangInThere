#!/usr/bin/env bash
# Replay saved SOURCE-PTS observations through the exact app counter on Linux/Mac.
set -euo pipefail
[[ $# == 4 ]] || { echo 'Usage: count-replay.sh observations.jsonl pullUp|dip left|right output.json' >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
swiftc -O -o "$WORK/count-replay" "$ROOT"/HangInThere/Analysis/*.swift "$ROOT/Evaluation/CountReplay.swift"
python3 - "$ROOT" "$WORK/count-replay" "$@" <<'PY'
import hashlib, json, pathlib, subprocess, sys
root, executable, source, exercise, side, output = map(pathlib.Path, sys.argv[1:])
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
before = digest(source)
with source.open('rb') as stream:
    run = subprocess.run([str(executable), str(exercise), str(side)], stdin=stream, stdout=subprocess.PIPE, check=True)
if digest(source) != before: raise SystemExit('Input changed while counting')
report = json.loads(run.stdout)
report['input_sha256'] = before
report['source_dirty'] = bool(subprocess.check_output(['git', '-C', str(root), 'status', '--porcelain'], text=True).strip())
report['source_revision'] = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
report['source_sha256'] = {str(p.relative_to(root)): digest(p) for p in [*sorted((root/'HangInThere/Analysis').glob('*.swift')), root/'Evaluation/CountReplay.swift']}
report['executable_sha256'] = digest(executable)
report['toolchain'] = subprocess.check_output(['swift', '--version'], text=True).strip()
output.parent.mkdir(parents=True, exist_ok=True)
# Never replace a previous report with a new run.
with output.open('x') as stream: json.dump(report, stream, indent=2, sort_keys=True); stream.write('\n')
print(f'{report["frames"]} frames: {report["summary"]}')
PY
