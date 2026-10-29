# Frozen quality-head robustness experiment (v12)

Adding severe blurred and washed-out **training** examples did not improve the
quality head. Both the matched real-only control and synthetic-negative arm
selected their starting checkpoint. Keep the previous offline model; there is
no new phone candidate.

## Fixed experiment

The input is v11's repaired `fitting-restored-row-metadata.jsonl`: all 526 real
training crops and 218 real validation crops, with source roles and original
references preserved. Start from v11 `frozenQuality/weights.pt`. Freeze the
MobileNet encoder, anatomy head, contact head, normalization, anatomy temperature
and contact temperature. Only the existing unambiguous-anatomy MLP and its
temperature may change. The head's label means anatomical readability, not just
whether an image contains a hand.

The 201 reviewed clear training crops each receive two deterministic negatives:
Gaussian blur at radius 12 on the 128-pixel input; and blur at radius 6 followed
by a washed-out RGB range of `0.86 + 0.12*x`. The 402 synthetic copies retain
their parents' source groups and training role. They are not new independent
videos. Original images and labels stay intact. Synthetic RGB is generated in
memory; only recipes, pixel hashes and review sheets are saved.

Before fitting, two class/group-stratified preview sheets showed 12 parents and
24 synthetic negatives. These were visually reviewed by the agent, not an
independent human annotator. The complete 402-image set was not manually
reviewed. The initial proposal also included exposure clipping alone; that
family left some side-profile anatomy readable, so it was excluded before any
training. The rejected proposal and review are retained separately in
`build/grip-quality-robustness-v12-proposal-01/`.

Both arms use seed 4115, 526 draws per epoch, batch size 16, AdamW at `1e-4`,
weight decay `0.02`, label smoothing `0.02`, a maximum of 80 epochs and patience
8. The real-only arm balances clear and unclear/mixed classes equally. The
synthetic arm keeps 50% sampling mass on real clear hands, 25% on real
unclear/mixed crops and 25% on synthetic negatives. Source groups, crops within
groups and corruption families share their allotted mass equally. Both arms
have the same update-budget rule; sampled indices differ because the negative
distributions differ.

Checkpoint selection uses class/source-group balanced NLL on all 218 real
validation crops, including the starting checkpoint. It does not select against
the two known washed-out errors. Temperature calibration uses the unchanged
`[1, 1.25, 1.5, 2, 3, 4]` grid. Anatomy, readability and contact acceptance gates
all stay at **0.8**.

## Result

| Model | Selected epoch | Validation quality NLL | Accepted / all crops | Correct anatomy + closed contact | Unclear/mixed/noncontact accepted |
|---|---:|---:|---:|---:|---:|
| v11 frozen-quality baseline | Starting checkpoint | 0.4401 | 14 / 218 | 12 | 2 |
| Real-only retraining control | 0 | 0.4401 | 14 / 218 | 12 | 2 |
| Synthetic-negative retraining | 0 | 0.4401 | 14 / 218 | 12 | 2 |

Both runs stop after eight epochs without beating the starting NLL. Real-only
epoch-one NLL is 0.4454; synthetic-negative epoch-one NLL is 0.4612. Their best
post-start NLLs are 0.4454 and 0.4513 respectively. No post-start checkpoint
improves the prespecified selection metric. The selected visible temperature is
1.0 in both arms. All selected predictions are bit-identical to the baseline.

There are zero accepted wrong **known** anatomical labels in these selected
models. The two bad-quality accepts are still H338 and H573, the two hands in
the same already exposed Kinetics frame. Their readability confidences remain
0.8872 and 0.8463. They are not two independent source failures.

The quality head alone admits 58/104 reviewed clear crops and 12/114
unclear/mixed crops at 0.8. The anatomy/contact conjunction reduces this to the
14 accepted inputs above. Correct accepted component coverage remains 12/218
(5.5%). This is not grip, bilateral-hand, rep, video or fresh-test accuracy.
Original-reference metrics and the full 218-crop denominator are retained in
`comparison.json`.

This bounded trial gives no support for shipping the augmented head. It also
does not prove that all blur augmentation or all shared encoders must fail. It
shows that this severe synthetic-negative recipe and head-only update, using
the frozen anatomy embeddings, did not improve real development validation.

## Next decision

Stop repeating head-only retraining on these embeddings. A useful next
controlled comparison is a separate lightweight RGB quality encoder, trained
for anatomical readability while keeping the anatomy/contact model frozen.
That tests whether an independently learned quality representation can preserve
sharp readable hands and reject natural low-detail failures. It needs realistic
quality examples, validation by source video, and the same acceptance/coverage
accounting; the two exposed errors alone cannot qualify it.

The v11 full-grip cohort gaps remain: train rear-neutral has one independent
readable group; validation front-underhand has one; validation front-neutral and
rear-neutral have two each, below the existing floor of three. No new videos
were acquired in v12. Filling those gaps remains required before grip fusion.
The final reserves were not decoded or scored, and no model is enabled in the
app.

## Reproduction and verification

Artifacts are in `build/grip-quality-robustness-v12/`. The existing research venv
and local weights are reused. On a fresh output directory, run
`scripts/grip-quality-robustness-v12.py prepare`, visually review the previews
and save a hash-bound `preview-review.json`, then run `train` and `evaluate`.
Existing evidence is never overwritten. `scripts/verify-grip-quality-v12.py`
checks all synthetic ancestry and pixels, group sampling mass, source-role
separation, selected weights and exported TorchScript predictions on every real
validation crop. Evaluation also checks the 147 historical v7 pins and retained
v9/v11 output hashes.

Both selected exports exactly preserve anatomy/contact and baseline quality
predictions. About 17 MB of candidate models, hashes, histories and review
evidence are retained, plus roughly 620 KB for the rejected preview proposal.
No video or full source frame is duplicated, and no synthetic image/embedding
cache is persisted. The repo's allowlisted compiler-cache inspection found
nothing to remove; about 191 GiB of disk space remains available.
