#!/usr/bin/env python3
"""Export the evaluated RGB-only TorchScript model to a local Core ML artifact."""
import argparse
import hashlib
import json
from pathlib import Path
import coremltools as ct
import torch


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory",type=Path)
    parser.add_argument("--precision",choices=["float32","float16"],default="float32")
    args=parser.parse_args(); torch.set_num_threads(2)
    model=torch.jit.load(str(args.directory/"GripPatchClassifier.pt"),map_location="cpu").eval()
    converted=ct.convert(model,inputs=[ct.ImageType(name="rgb",shape=(1,3,128,128),scale=1/255)],
        outputs=[ct.TensorType(name="scores")],convert_to="mlprogram",
        minimum_deployment_target=ct.target.iOS16,compute_precision=ct.precision.FLOAT32 if args.precision=="float32" else ct.precision.FLOAT16)
    report=json.loads((args.directory/"report.json").read_text())
    converted.short_description="Development RGB hand-patch overhand/underhand classifier"
    converted.input_description["rgb"]="Upright unmirrored 128x128 RGB hand/short-forearm crop. Pixel scaling 1/255; channel mean/std inside model."
    converted.output_description["scores"]="[overhand, underhand], uncalibrated softmax scores."
    converted.user_defined_metadata.update(qualification="development-only",labels="overhand,underhand",trainingSHA256=report["trainingSHA256"])
    destination=args.directory/("GripPatchClassifier-"+args.precision+".mlpackage");converted.save(str(destination))
    weights=destination/"Data/com.apple.CoreML/weights/weight.bin"
    receipt=dict(modelPackage=str(destination.absolute()),precision=args.precision,trainingSHA256=report["trainingSHA256"],
        labels=report["labels"],parameters=report["parameters"],weightsSHA256=hashlib.sha256(weights.read_bytes()).hexdigest(),
        packageBytes=sum(p.stat().st_size for p in destination.rglob("*") if p.is_file()),
        input="RGB 128x128; ImageType scale=1/255; embedded ImageNet channel mean/std; no landmarks")
    (args.directory/("coreml-receipt-"+args.precision+".json")).write_text(json.dumps(receipt,indent=2)+"\n")
    print(json.dumps(receipt,indent=2))


if __name__=="__main__":main()
