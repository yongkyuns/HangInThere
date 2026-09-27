# Dataset inventory and batch pose evaluation

This is a host evaluation tool, not another app framework. `scripts/evaluate.sh`
compiles the exact app `Analysis/` files, `VideoReplayReader` and
`VisionPoseEstimator` into one small macOS command-line entry point. Videos go
through the same actor, decoder, orientation handling and Vision request as the
app. Image sequences use the same Vision estimator after EXIF orientation; they
are not converted into an invented fixed-rate video. Python tooling uses only the
standard library. No training system, cloud inference, new iOS dependency or new
Xcode target is added.

**Scope:** evaluate existing pose output first. Rep counting is not implemented
in P0, so every report says `rep_metrics.status: not_implemented`. Unlabelled
footage can prove integration/coverage, not joint or form accuracy. A green
batch smoke test does not clear PR #2's separate simulator or phone gates.

## Run

```sh
# Available on Linux and macOS; no media needed for these synthetic contract tests.
python3 -m unittest discover -s Evaluation/tests -v

# Metadata only: counts are listed candidates, NOT usable/evaluated videos.
python3 scripts/inventory.py haa4d --fetch --output /tmp/haa4d.json
python3 scripts/inventory.py countix --fetch --output /tmp/countix.json
# Or use --input with an already-downloaded official CSV/archive instead of --fetch.

# On macOS, reuse the approved P0 fixture to exercise the real batch path.
python3 scripts/prepare-fixtures.py
mkdir -p build
python3 scripts/evaluation.py smoke-manifest --root "$PWD" --output build/batch-smoke.json
./scripts/evaluate.sh build/batch-smoke.json --root "$PWD" \
  --output Evaluation/output/my-smoke --public-output

# Your reviewed corpus lives outside Git. No automatic video download occurs.
python3 scripts/evaluation.py inspect /path/to/manifest.json --root /path/to/corpus \
  --output /tmp/preflight.json
./scripts/evaluate.sh /path/to/manifest.json --root /path/to/corpus \
  --output Evaluation/output/my-evaluation
```

Use a **new output directory** per run. Exit 2 means the corpus is incomplete,
blocked, invalid, or has an evaluation failure; not success. Exit 0 means all
listed clips were processed, **not** that accuracy targets passed. Missing media,
unapproved use, mismatched hashes and missing model assets never become success.
No live-camera or real-time dropping is simulated here; timings are native Mac
diagnostics only.

## Manifest v1

```json
{
  "schema_version": 1,
  "confidence_threshold": 0.3,
  "clips": [{
    "id": "pullup_001", "dataset": "your-reviewed-corpus",
    "exercise": "pull_up", "split": "test",
    "source_group": "original-source-video-001", "subject_group": "subject-001",
    "rights": {"status": "approved", "evidence": "reference to your permission review", "public_outputs": false},
    "media": {"kind": "video", "expected_frames": 240,
      "files": [{"path": "videos/001.mp4", "sha256": "REPLACE_WITH_EXACT_64_HEX_SHA256"}]},
    "annotations": {"path": "labels/001.json", "sha256": "REPLACE_WITH_EXACT_64_HEX_SHA256"}
  }]
}
```

`annotations` and `expected_frames` are optional. For an ordered image sequence,
set `kind` to `images` and list **every image in order**, with its SHA-256 pin.
Frame indices are zero-based list positions/decoded video-frame positions. A
video's timestamp remains its real presentation timestamp, not callback time or
an assumed frame rate. Images export `timebase: frame_index` and no timestamp.
Image annotation coordinates must be upright after EXIF orientation.

Exercise values: `pull_up`, `parallel_bar_dip`, `bench_dip`, `dip_unverified`,
`other`. Generic dataset labels such as `dips` are **not** silently promoted to
parallel-bar dips. Split values: `unassigned`, `development`, `validation`,
`test`, `smoke`. Unknown subjects must stay `unassigned` or `smoke`. Source IDs,
subject groups and identical media hashes cannot cross splits. Near-duplicate
videos and unknown pretraining exposure still require a separate review; hashing
cannot detect every derivative of the same source. Preserve official splits in
inventory metadata, then resolve subject/source overlap before qualification.

A rights entry is the maintainer's recorded review, not legal authorization
invented by the program. `pending` and `denied` are not processed. The supplied
public metadata inventories do not confer rights to videos or annotations.
`--public-output` additionally rejects clips not approved for public outputs.
Only the fixed approved CI smoke fixture and public metadata reports are uploaded
by the workflow; no arbitrary corpus or user recordings are uploaded.

## Reviewed 2D reference v1

```json
{
  "schema_version": 1,
  "coordinates": "upright_pixels_top_left",
  "independently_reviewed": true,
  "provenance": "dataset/review version, joint convention, reviewer process",
  "media_sha256": ["SAME_HASH_AS_EXACT_MEDIA_FILE"],
  "frames": [{
    "frame_index": 0, "width": 640, "height": 480,
    "scale_pixels": 100, "endpoint": true,
    "points": {"leftShoulder": [100,100], "leftElbow": [100,200], "leftWrist": [200,200]}
  }]
}
```

List visible, independently reviewed joints only. `scale_pixels` is optional,
and must come from a documented reference convention, not a model prediction.
`endpoint` is optional. Video labels may supply `timestamp_seconds` as an extra
alignment assertion; image sequences may not. Include hashes of all sequence
images in the same order. No predicted or lifted 3D skeleton is ground truth.

Joint names follow `PoseJoint` in the app. In a Penn Action conversion, use its
published shoulder/elbow/wrist etc. definitions, but **do not map its head point
to Vision's nose**. Verify pixel origin, indexing, visibility and actual dimensions
when converting MATLAB labels. Native Penn MAT, HAA4D skeleton, HAA500, RepCount
and OVR annotation importers are not implemented in this change; these sources
can use the common format after a reviewed conversion. No guessed joint ordering
or fabricated FPS is built into the runner.

## What reports establish

Each run binds the manifest, exact media/annotation hashes, source commit, hashes
of compiled app/evaluator sources, executable hash, model revision, target/OS and
confidence threshold. Inputs are rechecked after inference. The decoder must
reach EOF and publish a completion record; a partial/truncated output cannot be
scored as complete. Observations are streamed one frame at a time, with no video
image cache. Failures retain partial observations but are not scored.

Per-clip metrics include pixel error by joint, optional reference-scale-normalized
error, endpoint-only error, and **image-plane** elbow-angle error, with mean and
nearest-rank p95. Each metric includes eligible reference and measured counts plus
coverage. Multiple predicted people are ambiguous; no oracle chooses whichever
person is closest to ground truth. Missing joints decrease coverage rather than
contribute zero error. Scoring rescales predictions into the labelled image's
pixel dimensions and rejects mismatched aspect ratios.

There is no pooled accuracy claim across datasets/exercises, no pass/fail form
score and no release gate inferred from tiny samples. Per-exercise status counts
retain failed/unavailable clips. Training remains deferred until independently
labelled data demonstrates a residual model failure. The next additions are
reviewed dataset converters/rights clearance, real multi-clip pose comparisons,
then the counting engine and its separate temporal labels.
