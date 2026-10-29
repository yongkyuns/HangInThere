# Front/rear grip scope and reviewed cohort — 2026-10-23

The app now restricts grip inference to front/rear torso views. A supported
monocular body frame must have `abs(facing.z) >= 0.85`; both signs are accepted.
This is a conservative model estimate, not measured camera yaw or a calibrated
view probability. It uses shoulders and hips, never face absence or head turns.
Production camera eligibility still needs real phone recordings at borderline
angles; synthetic tests establish policy behavior rather than view accuracy.

Unsupported views skip hand inference, return `gripViewUnsupported`, and clear
previous hand confirmation immediately. Returning to a supported view requires
fresh hand evidence. The iPhone setup/running overlay displays a large **Move
camera toward your front or back** card with **Grip unknown · Rep counting can
continue**. Grip recognition does not block readiness, start a set, or reset rep
counting. Existing width measurement also requires the supported view. Shared
geometry policy is version 4; hand-inference body preflight is version 2.
Android displays the same running-set prompt; its SDK build was not run here.

## Data admission before retraining

`Evaluation/fixtures/grip-cohort-audit-v9.json` and the identical
`build/grip-front-rear-v9/coverage-before-training.json` bind the exact fitting
JSONL before any model fitting. All twelve grip × view × role cells now meet the
previously proposed development floor of three independent readable groups.
This is a coverage minimum, not proof of reliability.

| Grip | Train front/rear groups | Validation front/rear groups |
| --- | ---: | ---: |
| Overhand | 24 / 20 | 5 / 6 |
| Underhand | 11 / 6 | 3 / 3 |
| Neutral | 4 / 3 | 3 / 3 |

The supported fitting set has 483 source frames and 627 automatic hand patches:
409 training and 218 validation patches. The prior fitting roles and training
labels remain unchanged. Supplemental development inputs fill the deficient
validation cells; repeated frames never count as new independent groups.
All camera cuts of a publisher athlete remain in validation. StrengthLog's two
clips share one athlete group. No prior test recording enters fitting.

Public intake used official Kinetics 600 train and validation class archives,
excluding all previously allocated YouTube IDs. Before pixel review, novel
recordings were separated into development candidates and fourteen untouched
final reserve groups. Kinetics yielded useful dorsal overhand/quality inputs,
but most purported neutral/underhand candidates did not fill the actual gaps.

Additional sources:

- [Tuscaloosa Fitness neutral demonstration](https://www.youtube.com/watch?v=TXIhho2I1Zk): one athlete with rear, side and front cuts. Its supported cuts supply front and rear neutral groups; side cuts remain unsupported inputs.
- [MyTrainingPRO neutral demonstration](https://www.youtube.com/watch?v=A9lX5OZ8Dz4): a separate athlete with rear and close-up oblique cuts. The rear cuts supply the third rear-neutral group.
- [StrengthLog chin-up](https://www.strengthlog.com/chin-up/): rear supinated source with directly inspected palmar hand crops, providing the third rear-underhand group.
- [StrengthLog neutral demonstration](https://www.strengthlog.com/pull-ups-with-a-neutral-grip/): no automatic hand crops met the existing detector settings. This is retained as a miss, contributing zero readable coverage.
- [Legion neutral demonstration](https://legionathletics.com/neutral-grip-pull-up/): real neutral handles but a strongly oblique body view. Its provisional rear label was corrected; it contributes zero supported coverage.

The already-validation `ZvkHAS7aJO8` recording supplies another front-neutral
group after automatic wrist searches recovered actual hands on separate hanging
handles. Its setup reaching/open fingers are explicitly excluded from grip
supervision. This is component-data mining, not a claim that production hand
localization improved.

Original videos/GIFs, original byte hashes, source-frame indices, timestamps,
source RGB, automatic box coordinates, crop hashes, missed frames and rejected
proposals are retained. GIF images use composited RGB and original GIF frame
durations. Publisher MP4 images use original decoded frame indices/PTS. These
component inputs are not presented as native Apple decoder parity results.

`review.json` binds 142 supplemental direct crop reviews. First review used the
source image plus crop; second used seeded shuffled crop-only sheets. These are
two passes by Codex, not independent human gold. Surface labels come from pixels,
not an exercise title or a derived grip/view lookup. Ambiguous anatomy stays
unclear; ambiguous bar axes receive no grip supervision. In particular,
commando/fingerboard examples, false head/torso detections, open hands, and
unverified doorway/angled grips do not become neutral positives. Eight
palm-versus-unclear disagreements were conservatively adjudicated unclear.

## Training and evaluation policy

`train-grip-front-rear-v9.py` requires the exact coverage audit before fitting.
It trains image-only MobileNetV3-small surface, visibility and contact heads,
with two bounded encoder variants. Source-group/class weights and importance
correction keep each quality head's loss balanced despite the shared sampler.
Both variants use only training sources for gradients and validation sources
for checkpoint/calibration selection. Surface qualification remains at least
65% recall in every anatomical class and 70% macro recall, with minimum crop
and recording counts unchanged. Confidence thresholds remain 0.8.

Grip/orientation fusion is allowed only after anatomical qualification passes.
The guarded fusion makes neutral probability image-only: changing supplied view
cannot change it. Unsupported views are rejected by the independent app gate;
they remain in `unsupported-reviewed.jsonl`, not silently counted as successes.
Earlier models/scripts/results remain untouched. Same-validation comparisons
are development evidence, not fresh test accuracy. The fourteen new final
reserve recordings remain unread unless the model qualifies and is frozen.

## Verification and storage

The full shared JVM workout suite passes. The targeted native host tests pass
(17 tests), including fresh/stale/future/ambiguous view feedback. The unsigned
iPhone build succeeds; this turn has not installed an update on the phone.
Four prompt renders at widths 320/430, idle/running, pass text checks. All four renders were inspected visually. No Android SDK build.

Cache cleanup removed 976,056,059 bytes of duplicate downloaded archives and
185,328,206 bytes of unreferenced native RGB exports. Originals, all sampled
source RGB/crops, clocks, labels, frozen reserve media and models remain.
Receipts are stored beneath `build/grip-front-rear-v9/`. Available space was
about 182 GB after intake and cleanup. Temporary native test packages cleaned
up their build caches; the standard research compiler-cache inspection found
no further allowlisted cache directories.

## Retraining result

Both bounded candidates completed twelve epochs (three head-only, nine fine-tune)
and selected epoch four using validation loss. The late-block candidate was
selected, but it fails both the per-class and macro anatomical gates. No fusion
training, Core ML export, learned model activation or final-reserve scoring follows.
The new hand model is retained as an offline failed candidate.

On the exact same 218 validation patches (137 readable anatomy references):

| Anatomical recall | Previous surface model | New candidate |
| --- | ---: | ---: |
| Palm | 37/43 (86.0%) | 32/43 (74.4%) |
| Dorsum | 34/62 (54.8%) | 35/62 (56.5%) |
| Hand edge | 29/32 (90.6%) | 24/32 (75.0%) |
| Macro | 77.2% | 68.6% |

The previous model also fails the 65% dorsal gate on this expanded supported
cohort. These are hand-anatomy recalls, not pull-up/chin-up/neutral accuracies.
The candidate reduces unclear crops called visible at raw argmax from 74/81 to
37/81, but loses readable-hand coverage and anatomical accuracy. At the unchanged
0.8 surface/visible/contact gates it accepts only 2/218 crops (baseline 0/218),
with no unclear/noncontact accepts in that small accepted subset. Contact errors
remain substantial. Neither candidate demonstrates reliable usable grip coverage.

The source-group-balanced macro recalls are also retained in
`validation-comparison.json`; correlated frames must not inflate an independent
video claim. Validation is used for model selection, so this comparison cannot
replace a frozen final test. Fourteen new reserved videos remain undecoded.
The result argues for targeted dorsal/quality supervision and clearer hand
inputs; adding coverage cells alone did not solve the appearance task. No
threshold or safety gate was relaxed to make the candidate appear deployable.

The final Python check run passes 469 tests. The physical iPhone was listed as
unavailable by `devicectl`; only a simulated phone was connected. The UI build
is ready for installation when the physical phone reconnects.

Follow-up: [regression diagnosis and training-scope controls](GRIP_REGRESSION_DIAGNOSIS_V10.md).
