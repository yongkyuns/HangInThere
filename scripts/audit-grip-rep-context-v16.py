#!/usr/bin/env python3
"""Verify phase reuse, unchanged hand/counter evidence and isolated negatives."""
from collections import Counter, defaultdict
import importlib.util
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('v16_audit', ROOT/'scripts/evaluate-grip-rep-context-v16.py')
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)
sha, lines, write = evaluation.sha, evaluation.lines, evaluation.write


def main():
    first = ROOT/'build/grip-rep-context-v16'
    second = ROOT/'build/grip-rep-context-v16b'
    freezes = [json.loads((p/'protocol.json').read_text()) for p in [first,second]]
    for f in freezes:
        assert all(sha(p) == digest for p,digest in f['sha256'].items())
    a, b = lines(first/'replay-output.jsonl'), lines(second/'replay-output.jsonl')
    assert len(a) == len(b) == 969
    assert [{k:v for k,v in r.items() if k != 'bodyView'} for r in a] == [
        {k:v for k,v in r.items() if k != 'bodyView'} for r in b]
    # Every full-rate counter input must be the original automatically tracked
    # body's exact native pose, without classifier or reference annotations.
    native = lines(first/'native-input.jsonl')
    assert all(set(r) == {'recordingId','imageSHA256','trackId','pose'} for r in native)
    old = evaluation.prior.selected()
    source_rows = {}
    for r in old:
        if r['recordingId'] not in source_rows:
            source_rows[r['recordingId']] = {r['imageSHA256']:r for r in lines(Path(r['framesDirectory'])/'frames.jsonl')}
    tracks = {r['imageSHA256']:r['track'] for r in lines(ROOT/'build/grip-motion-v15b/body-trajectories.jsonl')}
    for r in native:
        original = source_rows[r['recordingId']][r['imageSHA256']]
        track = tracks[r['imageSHA256']]
        index = track['personIndex'] if track['status']=='tracked' else None
        assert r['pose'] == dict(original['pose'], people=[original['pose']['people'][index]] if index is not None else [])
    controls = json.loads((second/'controls-protocol.json').read_text())
    assert sha(second/'control-input.jsonl') == controls['inputSHA256']
    assert sha(ROOT/'Evaluation/androidTests/GripRepContextV16Tests.kt') == controls['driverSHA256']
    assert sha(ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/ExerciseCounter.kt') == controls['counterSHA256']
    grouped = defaultdict(list)
    for r in lines(second/'control-output.jsonl'): grouped[r['recordingId']].append(r)
    control_results = []
    for w in controls['windows']:
        rs = grouped[w['id']]
        assert len(rs) == w['frames']
        previous, epochs, movements = {}, set(), 0
        for r in rs:
            epoch = r['sourceEpoch']; epochs.add(epoch)
            count = r['movements']
            assert count >= previous.get(epoch,0)
            movements += count-previous.get(epoch,0)
            previous[epoch] = count
        result = dict(w, observedMovements=movements, exerciseEligibleFrames=sum(r['exercise']['eligible'] for r in rs))
        assert result['observedMovements'] == result['exerciseEligibleFrames'] == 0
        control_results.append(result)
    orientation = json.loads((second/'orientation.json').read_text())
    view_errors = [r for r in orientation if r['supported'] and r['reference'] in ['front','rear'] and r['view'] != r['reference']]
    unsupported_accepts = [r for r in orientation if r['supported'] and r['reference']=='oblique']
    methods = Counter(r.get('method') for r in orientation if r['supported'])
    shared_tests = Counter()
    for path in (ROOT/'shared/build/test-results/jvmTest').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for k in ['tests','failures','errors','skipped']:
            shared_tests[k] += int(suite.attrib.get(k,0))
    assert shared_tests['tests'] > 0 and shared_tests['failures'] == shared_tests['errors'] == 0
    output_paths = [first/'report.json', first/'replay-output.jsonl', second/'report.json',second/'results.json',
                    second/'replay-output.jsonl',second/'orientation.json',second/'controls-protocol.json',
                    second/'control-input.jsonl',second/'control-output.jsonl']
    result = dict(schemaVersion=16, preservedCounterAndHandEvidence=True, nativePoseRowsVerified=len(native),
        freezePinsVerified=[len(f['sha256']) for f in freezes],
        isolatedNegativeWindows=control_results, isolatedNegativeNativeFrames=sum(w['frames'] for w in control_results),
        trueFrontRearErrors=view_errors, acceptedObliqueReferenceFrames=unsupported_accepts,
        supportedViewMethods=dict(methods), sharedJVMTests=dict(shared_tests),
        originalConfidenceThresholdsUnchanged=True, phoneInstalled=False,
        qualification='Exposed recording-held-out regression, same source references, no fitting or phone deployment. Support seeded from observed wrists; not a full native setup/acquisition replay. Additional negative windows are conservative spans between same-phase samples.',
        sha256={str(p.relative_to(ROOT)):sha(p) for p in output_paths}, auditScriptSHA256=sha(__file__))
    write(second/'verification.json',result)
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
