#!/usr/bin/env python3
"""Fail closed on missing evidence. Budgets are CI regressions, not device promises."""
import argparse,json,math
from pathlib import Path

def finite_positive(x):return isinstance(x,(int,float)) and math.isfinite(x) and x>0
def core(path):
    r=json.loads(path.read_text());assert r['cases']>=25 and r['frames']>=180
    p95=r['p95NanosecondsPerFrame'];assert finite_positive(p95) and p95<1_000_000, 'Shared core p95 exceeds 1ms/frame'
    s=r['swift']['p95NanosecondsPerFrame'];assert finite_positive(s)
    assert finite_positive(r['checksum']) and abs(r['checksum']-r['swift']['checksum'])<1e-7, 'Benchmark output was not consumed identically'
    print(f"Same-host core p95: Swift {s/1000:.1f}µs, Kotlin {p95/1000:.1f}µs ({p95/s:.2f}×); core budget<1ms. Camera/model excluded.")
def native(path):
    r=json.loads(path.read_text());assert r['policyVersion']==8 and not r['failures']
    assert {c['id'] for c in r['cases']}=={'pullup_smoke','dip_controlled_pexels'}
    for c in r['cases']:
        assert c['functionalParity'] and c['observedMovements']==c['expectedMovements'],f"Count failure {c['id']}"
        assert c['frames']>=40 and c['singlePersonFrames']>0
        latency=c['poseLatencyMillis'];assert latency['samples']==c['frames']
        assert finite_positive(latency['p95']) and latency['p95']<2000,'CPU emulator pose p95 exceeds2s (CI regression budget)'
        assert 0<c['peakPssKb']<1_048_576,'Peak PSS exceeds1GiB (CI regression budget)'
        production=c['productionReplay']
        assert production['functionalParity'],f"Production Auto replay failure {c['id']}"
        pipeline=production['pipelineWithDisplayCopyLatencyMillis']
        assert pipeline['samples']>=c['frames'] and finite_positive(pipeline['p95']) and pipeline['p95']<2500, 'Full production pipeline exceeds2.5s/frame (CI regression budget)'
        assert 0<production['peakPssKb']<1_048_576, 'Production Auto replay exceeds1GiB PSS'
        print(f"{c['id']}: {c['observedMovements']}/{c['expectedMovements']} movements; {c['frames']} decoded frames; pose p95 {latency['p95']:.1f}ms; peak PSS {c['peakPssKb']/1024:.1f}MiB")
    print('Emulator replay regression passed. Physical-camera throughput, thermals and battery remain unqualified.')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--core',type=Path);p.add_argument('--native',type=Path);a=p.parse_args()
    assert a.core or a.native
    if a.core:core(a.core)
    if a.native:native(a.native)
