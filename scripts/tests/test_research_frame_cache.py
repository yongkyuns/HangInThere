"""Cache eviction must preserve every referenced source pixel and its metadata."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]


class FrameCacheSafetyTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='frame-cache-test-',dir=ROOT/'build')
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.native=self.root/'native';self.folder=self.native/'frames/clip';self.folder.mkdir(parents=True)
        self.used=self.folder/'frame-0001.png';self.unused=self.folder/'frame-0002.png'
        self.used.write_bytes(b'reviewed RGB');self.unused.write_bytes(b'reproducible unused RGB')
        self.metadata=self.folder/'frames.jsonl';self.metadata.write_text('pose/clock evidence')
        (self.native/'prepared-threeway.json').write_text(json.dumps(dict(cases=[dict(status='ready',framesDirectory=str(self.folder),sha256='source')])))
        self.refs=self.root/'references.jsonl'
        self.reference=dict(framesDirectory=str(self.folder),image=self.used.name,imageSHA256=hashlib.sha256(self.used.read_bytes()).hexdigest())
        self.refs.write_text(json.dumps(self.reference)+'\n')

    def run_prune(self):
        return subprocess.run([sys.executable,str(ROOT/'scripts/prune-research-frame-cache.py'),str(self.native),str(self.refs),'--apply','--receipt',str(self.root/'receipt.json')],capture_output=True,text=True)

    def test_only_unreferenced_pngs_removed(self):
        result=self.run_prune();self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(self.used.read_bytes(),b'reviewed RGB');self.assertFalse(self.unused.exists())
        self.assertEqual(self.metadata.read_text(),'pose/clock evidence')
        receipt=json.loads((self.root/'receipt.json').read_text())
        self.assertEqual(receipt['removedFrames'],1);self.assertEqual(receipt['preservedFrames'],1)
        self.assertEqual(receipt['inventory'][0]['sha256'],hashlib.sha256(b'reproducible unused RGB').hexdigest())

    def test_changed_reference_aborts_before_deletion(self):
        self.used.write_bytes(b'changed pixels');result=self.run_prune()
        self.assertNotEqual(result.returncode,0);self.assertTrue(self.unused.exists())

    def test_no_matching_reviewed_frames_aborts_before_deletion(self):
        self.refs.write_text('');result=self.run_prune()
        self.assertNotEqual(result.returncode,0);self.assertTrue(self.used.exists());self.assertTrue(self.unused.exists())


if __name__=='__main__':unittest.main()
