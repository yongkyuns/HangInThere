import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).absolute().parents[2]
def module(name, path):
    spec=importlib.util.spec_from_file_location(name,path)
    output=importlib.util.module_from_spec(spec);spec.loader.exec_module(output);return output
train=module("patch_training",ROOT/"scripts/train-grip-patches.py")
extract=module("patch_extraction",ROOT/"scripts/extract-grip-patches.py")

class PatchProvenanceTests(unittest.TestCase):
    def row(self):
        return dict(recordingId="a",imageSHA256="b",annotation=dict(recordingId="a",label="overhand",split="train",start=0,end=1,subjectId=None),
            pose=dict(timestamp=dict(value=0,timescale=1)),patchPlan=dict(sourceId="a",timestamp=dict(value=0,timescale=1)),
            patches=[dict(side=side,status="missing") for side in ["left","right"]])
    def load(self,rows):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"input.jsonl";path.write_text("".join(json.dumps(r)+"\n" for r in rows))
            return train.load_rows(path)
    def test_recording_leakage_and_known_person_leakage_rejected(self):
        a=self.row();b=copy.deepcopy(a);b["annotation"]["split"]="test"
        b["pose"]["timestamp"]["value"]=1;b["patchPlan"]["timestamp"]["value"]=1
        with self.assertRaises(AssertionError):self.load([a,b])
        b["recordingId"]=b["annotation"]["recordingId"]=b["patchPlan"]["sourceId"]="c"
        a["annotation"]["subjectId"]=b["annotation"]["subjectId"]="knownPerson"
        with self.assertRaises(AssertionError):self.load([a,b])
    def test_foreign_plan_changed_pixels_and_duplicate_source_rejected(self):
        a=self.row();b=copy.deepcopy(a);b["patchPlan"]["sourceId"]="foreign"
        with self.assertRaises(AssertionError):self.load([b])
        with self.assertRaises(AssertionError):self.load([a,a])
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"patch.png";path.write_bytes(b"modified")
            a["patches"][0].update(status="patchReady",patch=str(path),patchSHA256=hashlib.sha256(b"original").hexdigest())
            with self.assertRaises(AssertionError):self.load([a])
    def test_review_bound_to_exact_source_and_patch(self):
        a=self.row();a["patches"][0].update(status="patchReady",patchSHA256="pixelHash")
        review=dict(recordingId="a",imageSHA256="b",side="left",patchSHA256="pixelHash",reason="openHand")
        wrong=dict(review,patchSHA256="otherPixels")
        with self.assertRaises(AssertionError):train.apply_reviews([copy.deepcopy(a)],[wrong])
        train.apply_reviews([a],[review]);self.assertEqual(a["patches"][0]["status"],"visualExclusion:openHand")
        self.assertEqual(a["annotation"]["label"],"overhand")
    def test_manual_training_regions_cannot_enter_validation_or_test(self):
        for split in ["validation","test"]:
            row=self.row();row["annotation"]["split"]=split
            row["patches"][0].update(origin="manualTrainingRegion",manualAnnotationSHA256="review")
            row["manualPlan"]=dict(sourceId="a",timestamp=row["pose"]["timestamp"],annotationSHA256="review")
            with self.assertRaises(AssertionError):self.load([row])
    def test_crops_keep_hand_detail_and_image_bounds_without_stretching(self):
        import numpy as np
        points=np.array([[5.,20.],[25.,40.],[15.,30.]])
        box=extract.hand_patch(points,[20.,60.],(100,100))
        self.assertEqual(box[2]-box[0],box[3]-box[1])
        self.assertTrue(all(box[0]<=x<=box[2] and box[1]<=y<=box[3] for x,y in points))
        self.assertTrue(min(box)>=0 and max(box)<=100)
        self.assertIsNone(extract.hand_patch(points*.1,[20,60],(100,100)))
        self.assertGreater(extract.iou([0,0,20,20],[1,1,21,21]),.35)

if __name__=="__main__":unittest.main()
