#!/usr/bin/env python3
"""Versioned held-out reference correction; preserve all inference pixels/roles."""
import argparse
import copy
import hashlib
import json
from pathlib import Path


def apply(rows,corrections):
    entries={e['recordingId']:e for e in corrections};found=set();output=copy.deepcopy(rows)
    for row in output:
        entry=entries.get(row['recordingId'])
        if entry is None:continue
        annotation=row['annotation'];assert annotation['split']=='test','This overlay only corrects held-out references'
        assert annotation['label']==entry['previousLabel'] and annotation['viewpoint']==entry['previousViewpoint']
        annotation.update(label=entry['label'],viewpoint=entry['viewpoint'],referenceCorrection='public-grip-expansion-corrections-v2')
        found.add(row['recordingId'])
    assert found==set(entries),'Reference correction did not match source'
    for before,after in zip(rows,output):
        a=copy.deepcopy(before);b=copy.deepcopy(after);a.pop('annotation');b.pop('annotation')
        assert a==b,'Correction changed model inputs'
    return output


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('input',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--corrections',type=Path,default=Path('Evaluation/fixtures/public-grip-expansion-corrections-v2.json'));a=p.parse_args()
    data=[json.loads(l) for l in a.input.read_text().splitlines()];corrected=apply(data,json.loads(a.corrections.read_text())['corrections'])
    a.output.write_text(''.join(json.dumps(r)+'\n' for r in corrected))
    receipt=dict(inputSHA256=hashlib.sha256(a.input.read_bytes()).hexdigest(),outputSHA256=hashlib.sha256(a.output.read_bytes()).hexdigest(),
        correctionSHA256=hashlib.sha256(a.corrections.read_bytes()).hexdigest(),rows=len(data),modelInputsUnchanged=True)
    a.output.with_suffix('.receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(receipt)

if __name__=='__main__':main()
