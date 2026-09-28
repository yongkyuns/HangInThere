# Continuous-video movement evaluation

`prepare-temporal-pilot.py` and `score-temporal.py` extend the existing native
video evaluator; they do not introduce another app backend or counting model.
The counter is compiled from the **same production Swift sources** and runs on
fresh AVFoundation/Vision observations. PR #7 intentionally changes the counter to
policy v2; the frozen event labels remain unchanged and the generated reference
records bind themselves to policy version 2.

## Frozen reference set

`fixtures/temporal-pilot.json` freezes source hashes, contiguous native frame
ranges, event uncertainty windows, selected anatomical arm and excluded initial
intervals **before running the counter on these sequences**. A single assistant
reviewed the footage without counter output; this is not expert adjudication.
The pull-up source was previously used for pose-only comparison, so this is not
a held-out trial. The two dip shots share one publisher/session. No subject
independence or pretrained-model training-data independence is established.

| Clip | Native frames [first,end) | Observable movement events | Limitation |
| --- | --- | ---: | --- |
| Pull-up demonstration | [0,180) | 1 | Opening ascent's start hidden; final descent cut |
| Rear-view parallel-bar dips | [8682,9471) | 10 | Opening effort already underway; spectators and dismount remain |
| Side-view parallel-bar dips | [9940,10537) | 6 | Opening effort already underway; spectators and partial head crop remain |

These are continuous clips with observable full movement cycles, **not complete
uncensored workout sets**. Pull-ups are labelled at the top of a visible ascent
from a visible extended start. Dips are labelled on return to the local upper
endpoint after a visible descent/ascent. Neither label asserts chin clearance,
required dip depth, lockout, safety or valid form. The background is not cropped
away and the scorer never selects the person nearest a reference.

Dip source: *AAW23 - Fittest All American Challenge*, U.S. Army video by Sgt. Jacob
Moir / 49th Public Affairs Detachment, DVIDS 884241, VIRIN 230523-A-JM069-001.
The publisher lists public-domain status with restrictions:
https://www.dvidshub.net/video/884241/aaw23-fittest-all-american-challenge
https://www.dvidshub.net/about/copyright
The appearance of U.S. Department of War (DoW) visual information does not imply
or constitute DoW endorsement. No identity recognition, marketing or training
is performed. Publicity/trademark rights are not waived. The pull-up source is
FitnessScape's CC-BY-3.0 demonstration, credited in the existing source recipe;
complete per-file attribution accompanies generated artifacts.

Preparation downloads only pinned public bytes into an ignored cache. It
selects contiguous native frame indices, resizes the whole image and creates a
silent H264 derivative. No interior frames, people or motion pauses are removed.
Decoded output frame count is checked and both original and processed PTS arrays
are retained. Millisecond source timestamps may quantize during H264 encoding:
every corresponding PTS must differ by **at most 0.5 ms** after subtracting the
clip start. The counter receives actual decoded MP4 timestamps, not an assumed
frame-index/FPS clock. This small container-time quantization is recorded and
separate from the predeclared **0.35-second** event matching tolerance.

## Scoring and reproducibility

```sh
python3 scripts/prepare-temporal-pilot.py --cache Data/external/temporal \
  --output Data/local/temporal --fetch
./scripts/evaluate.sh Data/local/temporal/manifest.json \
  --root Data/local/temporal --output Evaluation/output/temporal-poses --public-output
python3 scripts/score-temporal.py Data/local/temporal/reference.json \
  --manifest Data/local/temporal/manifest.json --root Data/local/temporal \
  --pose-output Evaluation/output/temporal-poses \
  --output Evaluation/output/temporal-counts --public-output
```

The scorer verifies media/manifest hashes, pose-stream hashes, completion/EOF,
full source timestamps, counter policy/exercise/arm, matching source revisions,
compiled source fingerprints and summary/event consistency. Incomplete inputs
fail before publishing a success report. Counter thresholds, reference events
and ignored intervals are not selected from prediction outcomes.

Matching is chronological and one-to-one within reviewed uncertainty windows
plus the fixed tolerance. Extra predictions, including duplicates during a hold,
are false positives. Unmatched references are false negatives. Reported exact
count alone is insufficient: equal totals with wrongly timed events still fail
event matching. Precision/recall denominators of zero are null, not fabricated
perfect scores. Excluded initial intervals retain their predicted-event count
and reduce reported scored-time coverage. No-event time remains a negative test.
Partial/interrupted attempts remain diagnostics, not ground-truth form labels.

By default exit 0 means all clips were **processed and scored**, not that the
counter is accurate. CI prints actual TP/FP/FN and a warning for mismatches.
`--require-exact-events` returns 3 on any missed/extra event; invalid or incomplete
evidence returns 2. This diagnostic is not the POC's population accuracy gate,
and a successful execution does not qualify the known failing iOS Vision runtime.


## Policy-v2 interpretation

The retained temporal clips do not contain a confirmed bar reference, so this
benchmark exercises policy v2 in `fixedCameraOnly` mode. That is intentional:
it isolates the removal of projected-arm-length constancy and moving-wrist body
travel from the separate guided-bar setup. App replay with a confirmed bar adds
wrist/bar normal-offset continuity and reports `referenceMode: confirmedBar`.
Neither mode changes these frozen event windows or promotes movement events to
valid-form repetitions.
