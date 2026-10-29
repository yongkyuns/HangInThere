#!/usr/bin/env python3
"""Bind the completed diagnostic; keep original reference scores and gates."""
import hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-regression-v10'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def metrics(receipt):
    c=receipt['surfaceCoverage'];recall={k:v['correct']/v['crops'] for k,v in c.items()}
    return dict(knownSurfaceRecall=recall,macroRecall=sum(recall.values())/3,
        gatePassed=min(recall.values())>=.65 and sum(recall.values())/3>=.7 and all(v['crops']>=6 and v['videos']>=3 for v in c.values()),
        bestEpoch=receipt['bestEpoch'],epochsRun=receipt['epochsRun'],modelSHA256=receipt['modelSHA256'])
def main():
    controls={}
    for name in ['allViews','supportedOnly']:
        folder=OUT/('control-'+name);receipt=json.loads((folder/'diagnostic-receipt.json').read_text())
        assert sha(OUT/'training-scope-control-freeze.json')==receipt['freezeSHA256']
        assert sha(folder/'GripSurfaceClassifier.pt')==receipt['modelSHA256']
        controls[name]=metrics(receipt)
    notes=json.loads((OUT/'pixel-review-notes.json').read_text());prior=json.loads((ROOT/'build/grip-front-rear-v9/validation-comparison.json').read_text())
    result=dict(qualification='Development diagnosis, not independent test accuracy; no label correction, app change or new final-test exposure',
        modelComparison=prior,controls=controls,pixelReview=notes['summary'],
        causalLimits='A/B uses the same v2 loss/sampler/architecture/seed/epoch budget and expanded validation; only original oblique training samples differ. With fixed epochs, the larger dataset entails more gradient steps. Checkpoints are selected separately on development validation. The v9 candidate also changes the loss/sampler, so comparisons with it do not isolate that change.',
        conclusion='Keeping side-camera anatomy training examples improves edge recall under the controlled recipe, but palm collapse remains. Data coverage alone does not qualify anatomy. No experiment yet isolates multitask gradient interference.',
        nextExperiment='First freeze clear/mixed/unclear reference adjudication across training and validation, retaining old metrics. Then compare baseline-weight surface-only fine-tuning with the same warm-start shared-head control, while freezing quality heads. Only after anatomy qualification, train quality heads separately with encoder frozen.',
        originalLabelsUnchanged=True,confidenceThresholdsUnchanged=True,candidateEnabledInApp=False,finalReserveInspected=False,
        hashes={str(p):sha(p) for p in [OUT/'summary.json',OUT/'predictions.json',OUT/'pixel-review-notes.json',OUT/'training-label-sample.json',OUT/'training-scope-control-freeze.json']})
    (OUT/'results.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(dict(controls=controls,pixelReview=notes['summary']),indent=2))
if __name__=='__main__':main()
