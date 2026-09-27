# Native annotation intake and review

The host-only tools stage original Penn Action data and convert reviewed Penn
MAT or HAA4D raw 2D NPY annotations into the existing evaluation format. They do
not train a network, infer visibility, or turn a download into blanket permission.
NumPy/SciPy/Pillow are optional host tools; none ship in the app.

## Commands

```sh
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r Evaluation/import-requirements.txt

# Explicit large publisher download, never an ordinary build/test dependency.
python3 scripts/stage_penn.py --fetch \
  --archive Data/external/Penn_Action.tar.gz \
  --output Data/local/penn-intake --limit 6

# After inspecting originals and completing the review fields:
python3 scripts/import_poses.py Data/local/penn-intake/review.json \
  --root Data/local/penn-intake --output Data/local/penn-intake/converted
./scripts/evaluate.sh Data/local/penn-intake/converted/manifest.json \
  --root Data/local/penn-intake --output Evaluation/output/native-penn
```

Use a new output directory for every stage/conversion/evaluation. To reuse an
archive, omit `--fetch`; `--limit 0` explicitly selects all pull-up candidates.
`--sha256` requires a matching independently established or previously observed
archive pin. Without a pin, the downloader records a local snapshot digest, not
a publisher-issued digest or independent authentication. Existing inputs are not
overwritten. The publisher URL is linked from the [official page][penn].

## Intake is not approval

The stager scans all recognized native annotations, selects exact native action
names, and copies the selected original images/labels without re-encoding. The
actual publisher archive uses **`pullup`**, while its README lists `pull_ups`.
Only these two verified literals are accepted; their raw strings are retained.
Similar names such as `assisted_pullup` are not fuzzy-matched.

`intake-report.json` records action counts, raw split flags, dimensions, hashes,
selected IDs and target IDs not selected. Default selection is first-six native
IDs in ascending order, not a model-selected sample or six independent subjects.
`review.json` begins with pending rights, unknown subjects, unassigned splits,
unresolved source grouping, unset pixel origin and unreviewed labels.

The archive reader rejects traversal, links/special members, duplicate paths,
size-limit violations, missing sequence frames, invalid JPEGs, EXIF rotations and
image dimensions varying within a sequence. A native header/image-size mismatch
is **retained as `native_geometry.status: review_required`**, not repaired or
approved. This lets a reviewer inspect the actual originals instead of losing
an entire corpus at the first incorrect header. The converter still rejects that
clip until an exact geometry resolution is supplied. No failed conversion emits
a runnable partial manifest; every requested clip has an explicit status.

The manual-only `Native Penn intake` workflow uploads metadata, not readable
source images or native labels. Its ephemeral runner does not retain a reusable
corpus. Isolated acquisition/diagnostic branch runs are separate from normal CI;
private review transfers, when needed, use encrypted artifacts and a private key
outside GitHub. Neither a successful download nor encryption grants media rights.

## Review manifest

Start with an ordered image-clip manifest from [README.md](README.md). Include
all original images with SHA-256 pins and add:

```json
{
  "native_annotations": {
    "format": "penn_action_mat",
    "path": "labels/1149.mat",
    "sha256": "EXACT_NATIVE_FILE_SHA256"
  },
  "annotation_review": {
    "independently_reviewed": true,
    "provenance": "Actual review process and limitations, independent of tested predictions.",
    "pixel_origin": 1,
    "endpoint_frames": []
  }
}
```

This is a schema example, not permission approval. Inputs and review records
must match their hashes. Source/person overlap remains unresolved until reviewed;
filenames and official train/test flags do not establish subject-disjoint splits.
Image sequences use zero-based frame indices with no invented FPS or timestamps.
The converter preserves native hashes, review hash, joint mapping and exclusions.

### Penn Action

Read `x`, `y`, `visibility`, `dimensions`, `nframes`, `action`, and `train`, either
as top-level fields or in an `annotation` struct. Keep original contiguous
`000001.jpg` names and frame order. The twelve limb joints follow the author's
published order; **head is not Vision nose**. Binary visibility controls which
native references are scored. Nonfinite/out-of-image visible points reject the
conversion rather than being clamped. No silent array transposition is allowed.

Pixel origin must be explicitly reviewed, not inferred from `.mat`. The inspected
publisher `tools/CreatePointLightDisplay.m` uses `sub2ind(dims,Y,X,T)` directly,
which supports a one-based native display convention. Its observed SHA-256 is
`1fbd4c8e1d868f8483c33cab26479f68f3b32cb70b73325aa75498883f86e121`.
For that convention the converter subtracts one from x/y, and does not rescale
coordinates. Preserve annotation uncertainty rather than tune origin to a model.

The first six original sequences exposed two width-header discrepancies:
1153 says 481x270 while its JPEGs are 480x270; 1154 says 481x365 while its JPEGs
are 480x365. An independently reviewed identity mapping can be recorded under
`annotation_review`, for example for sequence 1153:

```json
{
  "geometry_resolution": {
    "native_size": [481, 270],
    "image_size": [480, 270],
    "coordinate_mapping": "identity",
    "evidence": "Describe the actual original-image/native-overlay review establishing unchanged coordinates on the decoded canvas."
  }
}
```

Both sizes must match the actual inputs and the evidence must be nonempty.
This is not a global one-pixel tolerance. Only identity mapping is supported:
no inferred crop, stretch, resampling or model-fitted label correction. The exact
resolution is retained in the converted reference provenance. A different
header, image size or mapping is rejected. Pixel-origin conversion is separate.

### HAA4D

Read raw `[frames,17,2]` NPY arrays with pickle disabled. Original frame names are
`0001.png` onward. Lifted/normalized 3D and object-pickled arrays are rejected.
The author calls indices 13/16 **hand**, not wrist. These and ambiguous head/spine
points are omitted; ten common limb joints remain. This native mapping does not
supply elbow-angle scores because it does not establish wrist references.

The [author's save routine][haa-save] drops visibility and writes only `[:2]`
coordinates. Require a separate, possibly sparse review; unlisted points remain
unreviewed, not visible:

```json
{
  "visible_frames": [
    {"frame_index": 4, "visible_native_joints": [11, 12, 14, 15]}
  ]
}
```

Place this in `annotation_review`, with explicit origin and provenance. Do not
infer the visibility mask from the evaluated model.

## Evidence and scope

The full observed Penn archive pin and actual action inventory are recorded in
[fixtures/penn-archive-snapshot.json](fixtures/penn-archive-snapshot.json): 2,326
native annotation records, including 199 pull-up sequences / 13,865 frames.
This is native-data inventory, not a benchmark score. Original sequences
1149–1154 contain 271 images; their hashes and native overlays have been inspected
and a private local conversion exercised. Latest execution/score evidence belongs
in [PR #3](https://github.com/yongkyuns/HangInThere/pull/3), not an inferred badge.
These are not six independently established people or held-out sets.

Synthetic MAT/NPY/archive tests qualify importer contracts, not native label
accuracy. Native labels themselves have uncertainty and were not expert-reannotated
by this project. No Penn/HAA images or joint arrays are committed. The publisher
provides a research dataset and requests citation; that does not settle commercial
training, marketing or redistribution rights. Keep use scopes separate. HAA500's
software-style MIT notice does not by itself establish rights to every source
video or HAA4D annotation. HAA4D native-media evaluation remains unperformed.

The earlier public-source pilot remains a separate smoke check: three intervals
from one source/apparently one athlete, 100 images and 46 approximate visible
points across nine assistant-reviewed frames, selected before model inspection.
It tests real processing and scoring, not the five-degree target or generalization.
Hidden/cropped wrists are omitted, not guessed. Source/frame selection and output
hashes are retained. Reproduce it with:

```sh
python3 scripts/prepare-fixtures.py
python3 scripts/prepare-pilot.py --source Data/external/p0-pullup-source.webm --output Data/local/public-pilot
./scripts/evaluate.sh Data/local/public-pilot/manifest.json --root Data/local/public-pilot --output Evaluation/output/pilot --public-output
```

P0's hosted-simulator model failure is not skipped or reclassified by a passing
host-only evaluation. Native Mac timing is not iPhone performance. No strict
rep, chin/bar, 3D pose, live capture, or parallel-bar-dip qualification follows
from these results.

[penn]: https://dreamdragon.github.io/PennAction/
[haa-save]: https://github.com/Morris88826/HAA4D/blob/0b15333a277e8fdf42b6dd6916f7a46cef389b96/annotation_tool/labelling_ui/libs/ui/page2.py#L787-L801
