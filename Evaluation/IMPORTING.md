# Native annotation import and first visual-reference pilot

## Scope

`scripts/import_poses.py` converts Penn Action `.mat` or HAA4D raw 2D `.npy`
annotations into the existing evaluation format. It does not download datasets,
clear media permissions, train a network, or certify the native labels. The
optional NumPy/SciPy/Pillow dependencies are host tools, never app dependencies.

```sh
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r Evaluation/import-requirements.txt
python3 scripts/import_poses.py /data/review.json --root /data --output /data/converted
./scripts/evaluate.sh /data/converted/manifest.json --root /data --output Evaluation/output/my-run
```

The output directory must be new and inside the data root. Inputs are read-only.
Every requested clip gets a conversion status; any rejection prevents emitting a
runnable partial manifest. No failed clip is silently discarded. The converted
references preserve input media hashes, native annotation hashes, review hash,
joint-order source, omitted joints and exclusion counts. The evaluator still
checks source/subject split separation and publication permission independently.

## Reviewed input contract

Start with the image-clip manifest in [README.md](README.md). List original image
files in their original order, with SHA-256 pins, and add the following fields to
each clip (omit `annotations` until conversion):

```json
{
  "native_annotations": {
    "format": "penn_action_mat",
    "path": "Penn_Action/labels/0001.mat",
    "sha256": "REPLACE_WITH_REVIEWED_NATIVE_FILE_SHA256"
  },
  "annotation_review": {
    "independently_reviewed": true,
    "provenance": "Describe who checked the native labels, coordinate convention and visibility, independently of evaluated predictions.",
    "pixel_origin": 0,
    "endpoint_frames": []
  }
}
```

This is a field example, not permission approval or a ready-to-run manifest.
`pixel_origin` must explicitly be 0 or 1 after review. The importer subtracts 1
only for a reviewed one-based coordinate source. A MATLAB filename alone does
not prove its coordinate origin. No FPS is inferred for image sequences. Original
frame names begin at 1; exported `frame_index` begins at 0.

**Penn Action:** supports published `x`, `y`, `visibility`, `dimensions`, `nframes`,
`action`, `train` fields, either top-level or in an `annotation` struct. Only the
`pull_ups` category is admitted initially. The 12 limb joints follow the author's
published ordering; `head` is deliberately not mapped to Vision `nose`. Native
binary visibility gates scoring. Arrays cannot silently be transposed; missing
images, wrong dimensions, changed hashes, EXIF rotations and invalid visible
coordinates reject conversion. Preserve the raw split flag as `penn_train_flag`
metadata; this does not assign subject-disjoint evaluation splits.

**HAA4D:** supports only raw `[frames,17,2]` arrays with pickle disabled. Its author
calls indices 13/16 `left_hand`/`right_hand`, not wrist. These and ambiguous
head/spine joints are omitted; only the ten common limb joints are mapped.
Consequently this native mapping does **not** produce elbow-angle scores, which
need an independently established wrist point. Do not silently use hand as wrist.

The HAA4D labeling UI tracks visibility, but its `save()` routine writes only
`[:2]` coordinate values. An NPY therefore does not establish which joints were
visible. Require a separately reviewed, possibly sparse visibility list:

```json
{
  "format": "haa4d_npy",
  "annotation_review": {
    "independently_reviewed": true,
    "provenance": "Describe the independent visibility and coordinate review.",
    "pixel_origin": 0,
    "visible_frames": [
      {"frame_index": 4, "visible_native_joints": [11, 12, 14, 15]}
    ]
  }
}
```

Put `format` inside `native_annotations` in the full clip; the shortened example
shows the review-specific difference. Unlisted frames/joints remain unreviewed,
not automatically visible. Lifted 3D/normalized skeleton arrays are rejected.
The importer never generates a subject identity or a test split from filenames.

## Evidence and limits

Converter tests create original synthetic MAT/NPY/image files. They test exact
mapping, visibility, safe deserialization, shapes, hashes, order, origins and
failure handling. They are **not** proof that native Penn/HAA media have been
acquired, that all native variants work, or that either dataset is cleared for
commercial training or redistribution. Those corpus gates remain open. No
Penn/HAA images or native annotations are included in this change.

The official Penn page offers a research dataset and asks for citation, but does
not itself specify a media redistribution/commercial licence. HAA500's linked MIT
notice is software wording and does not by itself settle HAA4D annotations or all
source YouTube footage. Keep the separate review records; do not copy a blanket
`approved` flag into an entire inventory. These are unresolved permission scopes,
not a claim that research use is prohibited.

## Public-source pilot: what can run now

`fixtures/public-pilot.json` freezes three disjoint intervals of the already
reviewed public-domain source: hanging/setup, upper-position/descent, and a
hang/ascent/return cycle. They share **one source and an apparently common athlete**;
they are not three independent subjects or held-out sets. All remain `smoke`.

100 images are selected by exact decoded source-frame indices, with original PTS
cross-checked for identity. No new timestamp is attributed to the image sequence.
The scaler is fixed at 960x540. Source SHA-256, recipe SHA-256, generated image
hashes, annotation hashes and ffmpeg version are retained. Different PNG encoding
bytes may arise with encoder versions; the immutable source/frame selection and
fixed geometric transform define label alignment. Generated image hashes bind
that run's references to its actual images.

Nine source frames have **46 approximate visible limb points** selected by one
assistant visual review before viewing this pilot's model output. Hidden/cropped
wrists are omitted, not inferred. Shoulder centres under clothing and other
manual locations have uncalibrated uncertainty. No second human reviewer or
motion-capture reference was used. These references can reveal large localization
or mapping errors and exercise the scorer end to end, but cannot qualify the
5-degree target, strict form, rep accuracy, or generalization. Model disagreement
must trigger independent image review, not automatic relabeling toward the model.

```sh
python3 scripts/prepare-fixtures.py  # existing public source acquisition/pin
python3 scripts/prepare-pilot.py --source Data/external/p0-pullup-source.webm --output Data/local/public-pilot
./scripts/evaluate.sh Data/local/public-pilot/manifest.json --root Data/local/public-pilot --output Evaluation/output/pilot --public-output
```

CI checks processing of all 100 images and reference-denominator integrity. It
does not gate on small measured errors from these approximate references. The
artifact retains each hashed reference and its annotation provenance beside the report. Inspect the exact-head
Dataset evaluation run for results; no outcome is asserted by this document.
P0's hosted-simulator model failure is neither skipped nor reclassified by this
host-only image evaluation.

## Primary sources inspected

- [Penn Action annotation fields and joint order](https://dreamdragon.github.io/PennAction/)
- [HAA4D joint order](https://github.com/Morris88826/HAA4D/blob/0b15333a277e8fdf42b6dd6916f7a46cef389b96/libs/skeleton.py)
- [HAA4D save routine: coordinates without visibility](https://github.com/Morris88826/HAA4D/blob/0b15333a277e8fdf42b6dd6916f7a46cef389b96/annotation_tool/labelling_ui/libs/ui/page2.py#L787-L801)
- [HAA4D original image extraction](https://github.com/Morris88826/HAA4D/blob/0b15333a277e8fdf42b6dd6916f7a46cef389b96/get_HAA500.py)
- [HAA4D annotation workflow](https://cse.hkust.edu.hk/haa4d/annotation.html)
- [HAA500 linked licence](https://www.cse.ust.hk/haa/LICENSE)
