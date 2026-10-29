#!/usr/bin/env python3
"""Replace only the torso view component with a head-crop view component.

Appearance and face weights are frozen from the independently supervised run.
Checkpoint selection sees only orientation labels on validation videos.
"""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import random
import numpy as np
import torch

SPEC = importlib.util.spec_from_file_location('components', Path(__file__).with_name('train-grip-components.py'))
learning = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(learning)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('input', type=Path)
    p.add_argument('source_model', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--patch-review', type=Path)
    p.add_argument('--warmup', type=int, default=12)
    p.add_argument('--epochs', type=int, default=8)
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    assert not (args.output/'training-receipt.json').exists(), 'Use a new output directory'
    rows = learning.base.load(args.input, args.patch_review)
    assert all(r['annotation']['split'] in ['train', 'validation'] for r in rows), 'Test input cannot enter training'
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    torch.manual_seed(31)
    random.seed(31)
    np.random.seed(31)
    source_receipt = json.loads((args.source_model/'training-receipt.json').read_text())
    assert hashlib.sha256((args.source_model/'GripContextClassifier.pt').read_bytes()).hexdigest() == source_receipt['modelSHA256']
    model = learning.Components(pretrained=False)
    model.load_state_dict(torch.load(args.source_model/'weights.pt', weights_only=True))
    before = {n: copy.deepcopy(getattr(model, n).state_dict()) for n in ['appearance', 'face']}
    model.body = learning.ImageHead(3, pretrained=True)
    body_receipt = learning.fit('body', model.body, rows, args)
    for n, weights in before.items():
        assert all(torch.equal(v, getattr(model, n).state_dict()[k]) for k, v in weights.items())
    model.eval()
    torch.save(model.state_dict(), args.output/'weights.pt')
    traced = torch.jit.trace(model, (torch.zeros(1, 3, 128, 128), torch.zeros(1, 3, 128, 128), torch.zeros(1, 6)))
    traced.save(str(args.output/'GripContextClassifier.pt'))
    receipt = dict(source_receipt, body=body_receipt,
                   inputSHA256=hashlib.sha256(args.input.read_bytes()).hexdigest(),
                   scriptSHA256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   componentTrainerSHA256=hashlib.sha256(Path(learning.__file__).read_bytes()).hexdigest(),
                   sourceModelSHA256=source_receipt['modelSHA256'], contextInput='selected-athlete head crop, unmirrored RGB 128',
                   frozenComponents=['appearance', 'face'],
                   modelSHA256=hashlib.sha256((args.output/'GripContextClassifier.pt').read_bytes()).hexdigest())
    (args.output/'training-receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
    print(json.dumps(dict(modelSHA256=receipt['modelSHA256'], body=body_receipt)), flush=True)


if __name__ == '__main__':
    main()
