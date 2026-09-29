# Five-source evaluation pilot

`fixtures/source-pilot.json` adds **two parallel-bar-dip photographs, one
bench/rock-supported dip control, one rear-view pull-up photograph, and 45
ordered frames from a separate six-second pull-up demonstration**. Original
source bytes and selected frame indices/PTS are pinned. The source pages,
creators, licences, image-specific limitations and full visible-joint definitions
are recorded in the recipe; `ATTRIBUTION.txt` accompanies generated artifacts.

```sh
python3 scripts/prepare-source-pilot.py --cache Data/external/source-pilot \
  --fetch --output Data/local/source-pilot
./scripts/evaluate.sh Data/local/source-pilot/manifest.json \
  --root Data/local/source-pilot --output Evaluation/output/source-vision --public-output
```

There are 38 approximate point references on eight selected frames, frozen by
single-assistant visual review **before** either backend processes these sources.
This is not independent expert adjudication. All clips remain `unassigned` with
unknown subject groups; two photos share a publisher/session uncertainty. Source
variation must not be described as a statistically independent held-out cohort.
Bench dips are not parallel-bar positives. **Still photographs cannot validate
rep counting, endpoint transitions or full dip range of motion.** No counters,
exercise classifiers or form decisions are evaluated here.

Review caught an incorrect EXIF tag in `dip_cropped`: the raw JPEG is upright,
but its orientation-8 tag rotates it sideways. A byte-bound, source-specific
`raw-reviewed` preparation policy corrects this before labelling/inference;
the app's general EXIF handling is unchanged. Only its visible near shoulder and
elbow are labelled; the cropped wrists are never filled in. Other sources use
normal EXIF handling. No anatomical landmark is inferred from the model to make
a reference label.

Preparation fails on changed/missing originals, altered geometry/EXIF/PTS,
missing frames or invalid labels. It never publishes a smaller manifest after a
failure. A local cache avoids repeat downloads; `--fetch` is explicit, uses only
reviewed original media URLs and never retries rate-limit errors. No original
media or model weights are added to Git. CI transfers the prepared PNG bytes
from the Vision job to the MediaPipe job and keeps the existing scoring and
ambiguity policy unchanged. Credits and transformation notices are retained;
these source licences do not establish publicity/model-release rights or permit
endorsement claims. The media is not used in product marketing.
