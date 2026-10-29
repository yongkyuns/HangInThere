#!/usr/bin/env python3
"""Audit frozen new-video evidence, automatic crops and explicit set resets."""
from collections import Counter
from fractions import Fraction
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import statistics
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'build/grip-width-v23'


def read(p): return json.loads(Path(p).read_text())
def lines(p): return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def clock(p): return Fraction(p['timestamp']['value'], p['timestamp']['timescale'])
def module(name, filename):
    s = importlib.util.spec_from_file_location(name, ROOT/'scripts'/filename)
    m = importlib.util.module_from_spec(s); s.loader.exec_module(m); return m


def main():
    from PIL import Image
    pins = 0
    for file in ['protocol.json', 'reference-freeze.json', 'implementation-freeze.json',
                 'pre-inference-freeze.json', 'crop-review-receipt.json']:
        for path, digest in read(OUT/file)['sha256'].items():
            assert sha(path) == digest, path
            pins += 1
    refs = read(OUT/'source-references.json')
    intake = read(OUT/'intake.json')['cases']
    ids = {c['id'] for c in intake}
    assert len(ids) == len(intake) == refs['allNewSources'] == 6
    assert {c['id'] for c in refs['cases']} | {c['id'] for c in refs['excludedCases']} == ids
    assert len(refs['cases']) == len(refs['excludedCases']) == 3
    for c in intake:
        assert sha(c['path']) == c['sha256']
    fitting_paths = [ROOT/'build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl',
                     ROOT/'build/grip-threeway/fitting.jsonl']
    for p in fitting_paths:
        # Exact source IDs, groups and hashes, including nested annotation metadata.
        for row in lines(p):
            serialized = json.dumps(row)
            assert not any(c['id'] in serialized or c['sha256'] in serialized for c in intake)

    reference = module('width_reference_v23', 'grip-width-reference.py')
    crop = module('width_crop_v23', 'prepare-threeway-crops.py')
    policy = module('width_policy_v23', 'grip-controlled-v17.py')
    accounting = module('width_accounting_v23', 'evaluate-grip-supported-v19.py')
    native = lines(OUT/'test/native-input.jsonl')
    output = lines(OUT/'test/native-output.jsonl')
    rows = lines(OUT/'test/rows.jsonl')
    observations = lines(OUT/'test/observations.jsonl')
    assert len(native) == len(output) == 1517
    assert len(rows) == len(observations) == 218
    selected = {(r['caseId'], clock(r['pose'])): r for r in native}
    native_cases = {r['caseId'] for r in native}
    assert native_cases == {c['id'] for c in refs['cases']}
    all_body = {}
    for c in refs['cases']:
        bodies = lines(Path(c['bodyDirectory'])/'poses.jsonl')
        export = read(Path(c['bodyDirectory'])/'export.json')
        assert export['videoSHA256'] == c['recordingId']
        assert export['observationsSHA256'] == sha(Path(c['bodyDirectory'])/'poses.jsonl')
        for p in bodies:
            all_body[c['id'], clock(p)] = p
            chosen = selected[c['id'], clock(p)]['pose']
            assert dict(chosen, people=p['people']) == p
            assert len(chosen['people']) <= 1
            assert all(person in p['people'] for person in chosen['people'])
        anchor = c['shots'][0]['reference']
        assert sha(anchor['sourceImage']) == anchor['sourceImageSHA256']
        interval = reference.ratio_interval(anchor['regions'], anchor['support'], anchor['imageSize'])
        assert list(interval) == anchor['ratioInterval']
        assert reference.band(interval) == c['shots'][0]['referenceWidth']

    patches = 0
    for r, obs in zip(rows, observations):
        t = clock(r['pose']); original = all_body[r['caseId'], t]
        assert r['pose'] == original
        assert r['caseId'] == obs['caseId'] and r['imageSHA256'] == obs['imageSHA256']
        assert obs['seconds'] == float(t)
        source = Path(r['framesDirectory'])/r['image']
        assert sha(source) == r['imageSHA256']
        pose = selected[r['caseId'], t]['pose']
        with Image.open(source) as im:
            im = im.convert('RGB')
            ready = [p for p in r['patches'] if p['status'] == 'patchReady']
            assert len(ready) == len(obs['hands'])
            for p in ready:
                box, wrist = crop.geometry(pose['people'][0], p['side'], im.size)
                assert list(box) == p['box'] and list(wrist) == p['wrist']
                assert sha(p['patch']) == p['patchSHA256']
                expected = im.crop(box).resize((128, 128), Image.Resampling.BILINEAR)
                with Image.open(p['patch']) as actual:
                    assert expected.tobytes() == actual.convert('RGB').tobytes()
                patches += 1
    assert patches == read(OUT/'crop-review-receipt.json')['reviewedAutomaticCrops'] == 238

    # Attribute hasCurrentSupport's first failing gate. This is diagnostic only;
    # it does not alter the frozen detector, view labels or eligibility.
    gate_reports = []
    for c in refs['cases']:
        counts = Counter(); spans = []
        for i, o in zip(native, output):
            assert i['imageSHA256'] == o['imageSHA256']
            if i['caseId'] != c['id'] or not i['widthViewSupported']: continue
            pose = i['pose']; people = pose['people']; size = pose['imageSize']
            short = min(size.values())
            if len(people) != 1: counts['noTrackedPerson'] += 1; continue
            if not o['reference']: counts['noSupportReference'] += 1; continue
            lm = {p['joint']: p for p in people[0]['landmarks']}
            names = ['leftShoulder', 'rightShoulder', 'leftWrist', 'rightWrist']
            if any(j not in lm or lm[j]['confidence'] < .5 for j in names):
                counts['jointMissingOrLowConfidence'] += 1; continue
            if any(not 0 <= lm[j]['position']['x'] <= size['width'] or
                   not 0 <= lm[j]['position']['y'] <= size['height'] for j in names):
                counts['jointOutsideImage'] += 1; continue
            a, b = [lm[j]['position'] for j in names[:2]]
            span = math.hypot(a['x']-b['x'], a['y']-b['y']); spans.append(span)
            if span <= short*.08:
                counts['shoulderSpanBelowEightPercentShortSide'] += 1
                assert not o['widthBodyUsable']
            elif o['widthBodyUsable']: counts['widthBodyUsable'] += 1
            else: counts['otherSupportGeometryOrRelease'] += 1
        gate_reports.append(dict(id=c['id'],shotFrames=sum(counts.values()),firstGateCounts=dict(counts),
            medianConfidentShoulderPixels=statistics.median(spans) if spans else None,
            shoulderPixelFloor=57.6))

    sequence_in = lines(OUT/'test/alternating-input.jsonl')
    sequence = lines(OUT/'test/alternating-output.jsonl')
    plan = read(OUT/'alternating-plan.json')
    assert len(sequence_in) == len(sequence) == 2152
    assert all(sha(p) == h for p, h in plan['sha256'].items())
    compare = ['width','projectedSpanRatio','widthReason','phase','movements','reference',
               'exercise','widthBodyUsable','released','supportBySide','trackingIssue']
    baselines = {(f['caseId'], f['seconds']): f for f in output}
    set_results = []
    for entry in plan['order']:
        key = f"{entry['position']}:{entry['caseId']}"
        ins = [r for r in sequence_in if r['caseId'] == key]
        fs = [r for r in sequence if r['caseId'] == key]
        originals = [r for r in native if r['caseId'] == entry['caseId']]
        assert len(fs) == len(ins) == len(originals) == entry['frames']
        assert fs[0]['width'] == 'unknown'
        for before, after in zip(originals, ins):
            assert dict(after, caseId=before['caseId'], shotId=before['shotId']) == before
        for f in fs:
            assert all(f[k] == baselines[entry['caseId'],f['seconds']][k] for k in compare)
        case = next(c for c in refs['cases'] if c['id'] == entry['caseId'])
        rgb = {r['imageSHA256']:r for r in observations if r['caseId'] == case['id']}
        result = policy.decide(fs, rgb, case['confirmedView'], False)
        baseline = policy.decide([r for r in output if r['caseId'] == case['id']], rgb, case['confirmedView'], False)
        assert result['states'] == baseline['states']
        summary = accounting.summarize(case, fs, result['states'],
            [float(clock(r['pose'])) for r in rows if r['caseId'] == case['id']])
        assert summary['negativeControls'].get('newMovements', 0) == 0
        assert summary['negativeControls'].get('visibleLabels', 0) == 0
        set_results.append(dict(position=entry['position'],id=entry['caseId'],
            frames=len(fs),gripOutcome=summary['outcome'],negativeControls=summary['negativeControls']))

    for filename in ['native-replay-junit.xml','alternating-replay-junit.xml']:
        junit = ET.parse(OUT/filename).getroot()
        assert int(junit.attrib['tests']) == 1 and int(junit.attrib['failures']) == int(junit.attrib['errors']) == 0
    report = read(OUT/'test/report.json')
    assert [c['outcome'] for c in report['cases']] == ['correct']*3
    assert sum(c['wrongNewDecisions'] for c in report['cases']) == 0
    assert [c['widthOutcome'] for c in report['cases']] == ['correct','unverifiable','unknown']
    artifact_names = ['test/report.json','test/grip-results.json','test/native-output.jsonl',
        'test/rep-records.json','test/observations.jsonl','test/alternating-output.jsonl',
        'test/alternating-rep-records.json','protocol.json','reference-freeze.json',
        'pre-inference-freeze.json','crop-review-receipt.json','implementation-freeze.json',
        'alternating-plan.json','native-replay-junit.xml','alternating-replay-junit.xml']
    result = dict(schemaVersion=23,frozenPinsVerified=pins,originalSourcesVerified=6,
        fittingSourceOverlap=0,processedSources=3,excludedBeforeInference=3,
        nativeFrames=1517,scheduledOriginalPosesAndImagesVerified=218,automaticCropsRebuiltExactly=patches,
        independentlyHumanAdjudicated=False,referenceConvention='jointCenterRegions_v1',
        resolvedWidthReferences=2,uncertainWidthReferences=1,productionOrModelChanges=False,
        widthGateDiagnostics=gate_reports,assembledFramesVerified=2152,
        explicitSetResetOutputsMatchIsolated=True,assembledSets=set_results,
        continuousAlternatingWorkoutValidated=False,phoneAutomaticAcquisitionValidated=False,
        sha256={str((OUT/p).resolve()):sha(OUT/p) for p in artifact_names})
    result['sha256'][str(Path(__file__).resolve())] = sha(__file__)
    with (OUT/'verification.json').open('x') as f: json.dump(result,f,indent=2); f.write('\n')
    print(json.dumps({k:v for k,v in result.items() if k!='sha256'},indent=2))


if __name__ == '__main__': main()
