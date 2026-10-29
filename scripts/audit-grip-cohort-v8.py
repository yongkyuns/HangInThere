#!/usr/bin/env python3
"""Inventory independent fitting groups for a proposed front/rear grip scope.

References are inherited agent annotations. Counts do not reverify pixels or
qualify a model. Oblique inputs remain explicit rejection/regression cases.
"""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def audit(rows):
    roles=defaultdict(set); cells=defaultdict(lambda:dict(available=0,readable=0,groups=set(),recordings=set()))
    for row in rows:
        split=row['annotation']['split']
        group=row['annotation'].get('sourceGroup') or row.get('sourceGroup') or 'recording:'+row['recordingId']
        roles[group].add(split)
        for patch in row['patches']:
            if patch['status']!='patchReady':continue
            ref=patch['threewayReview']
            if ref['grip'] not in ['overhand','underhand','neutral']:continue
            cell=cells[split,ref['grip'],ref['view']]
            cell['available']+=1
            if ref['surface'] in ['palm','dorsum','side'] and ref['contact']=='closed':
                cell['readable']+=1;cell['groups'].add(group);cell['recordings'].add(row['recordingId'])
    assert all(len(x)==1 for x in roles.values()),'Source group crosses fitting roles'
    return [dict(split=k[0],grip=k[1],view=k[2],availablePatches=v['available'],readableClosedPatches=v['readable'],
                 readableSourceGroups=len(v['groups']),sourceGroups=sorted(v['groups']),recordings=sorted(v['recordings']))
            for k,v in sorted(cells.items())]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('input',type=Path);p.add_argument('output',type=Path)
    args=p.parse_args();assert not args.output.exists()
    rows=[json.loads(x) for x in args.input.read_text().splitlines()]
    cells=audit(rows);lookup={(x['split'],x['grip'],x['view']):x for x in cells}
    gaps=[]
    for split in ['train','validation']:
        for grip in ['overhand','underhand','neutral']:
            for view in ['front','rear']:
                cell=lookup.get((split,grip,view),{})
                count=cell.get('readableSourceGroups',0)
                if count<3:gaps.append(dict(split=split,grip=grip,view=view,readableSourceGroups=count,shortfall=3-count))
    result=dict(schemaVersion=1,qualification='Metadata inventory of inherited two-pass agent annotations; not independent gold, not fresh-test accuracy, no relabeling or fitting.',
                input=str(args.input.resolve()),inputSHA256=sha(args.input),scriptSHA256=sha(__file__),
                proposedSupportedViews=['front','rear'],unsupportedViews=['oblique','unverifiable'],
                gripClasses=['overhand','underhand','neutral'],
                minimumReadableGroupsPerFittingCell=3,minimumDescription='Development coverage floor, not a statistical reliability claim or replacement for existing model-validation gates.',
                sourceRolesPreserved=True,cells=cells,coverageGaps=gaps,cohortReadyForFullGripRetraining=not gaps,
                policy='Unsupported side views remain explicit input-rejection tests; do not silently discard their false accepts. Do not promote exposed test videos into fitting. View support is independent of grip/hand surface. No hard yaw angle inferred from 2D pose.')
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(dict(coverageGaps=gaps,cohortReadyForFullGripRetraining=not gaps),indent=2))


if __name__=='__main__':main()
