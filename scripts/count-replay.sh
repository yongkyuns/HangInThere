#!/usr/bin/env bash
# Replay saved SOURCE-PTS observations through the exact app counter on Linux/Mac.
set -euo pipefail
[[ $# == 4 || $# == 8 ]] || {
  echo 'Usage: count-replay.sh observations.jsonl pullUp|dip left|right output.json [barX1 barY1 barX2 barY2]' >&2
  exit 2
}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
swiftc -O -o "$WORK/count-replay" "$ROOT"/HangInThere/Analysis/*.swift "$ROOT/Evaluation/CountReplay.swift"
python3 - "$ROOT" "$WORK/count-replay" "$@" <<'PY'
import hashlib, json, pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1]); executable = pathlib.Path(sys.argv[2])
source = pathlib.Path(sys.argv[3]); exercise = sys.argv[4]; side = sys.argv[5]
output = pathlib.Path(sys.argv[6]); coords = sys.argv[7:]
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
before = digest(source)
args = [str(executable), exercise, side, *coords]
with source.open('rb') as stream:
    run = subprocess.run(args, stdin=stream, stdout=subprocess.PIPE, check=True)
if digest(source) != before: raise SystemExit('Input changed while counting')
report = json.loads(run.stdout)
report['input_sha256'] = before
report['source_dirty'] = bool(subprocess.check_output(['git', '-C', str(root), 'status', '--porcelain'], text=True).strip())
report['source_revision'] = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
report['source_sha256'] = {str(p.relative_to(root)): digest(p) for p in [*sorted((root/'HangInThere/Analysis').glob('*.swift')), root/'Evaluation/CountReplay.swift']}
report['executable_sha256'] = digest(executable)
report['toolchain'] = subprocess.check_output(['swift', '--version'], text=True).strip()
output.parent.mkdir(parents=True, exist_ok=True)
with output.open('x') as stream: json.dump(report, stream, indent=2, sort_keys=True); stream.write('\n')
print(f'{report["frames"]} frames: {report["summary"]}')
PY