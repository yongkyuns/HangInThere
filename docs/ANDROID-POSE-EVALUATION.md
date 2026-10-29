# Complete public-source pose evaluation

The Android workflow can export the shipping decoder and MediaPipe Heavy CPU
observations for all four frozen temporal sources: 180 pull-up, 789 rear-dip,
597 side-dip and 300 controlled-dip frames. This is a measurement diagnostic;
it does not run a replacement counter or qualify detection accuracy.

```sh
gh workflow run android.yml --ref codex/android-parity \
  -f selected_pose_diagnostic=true
```

The API 29/35 matrix uses the production `VideoFrames` and `PoseEstimator`.
Each source gets a fresh VIDEO estimator. Source hashes, every container PTS
truncated to Android microseconds, output dimensions and complete source counts
are checked. Both image and same-request world observations are retained at their
original person indices, including unavailable measurements and multiple people.
Event windows and apparatus annotations are not packaged for this exporter.
Existing automatic setup, functional, accuracy and resource gates remain fatal.

The public artifacts include `selected-pose-manifest.json`,
`selected-pose-*-observations.jsonl`, `selected-pose-report.json` and
`selected-pose-ATTRIBUTION.txt`. The completion report is invalidated before any
asset read and written only after all four sources finish. The runner clears its
owned public output directories before execution; partial traces cannot reuse
an older success report. Test videos and the test APK are excluded from uploads.

```sh
python3 scripts/check-selected-pose-export.py downloaded-android-evaluation
```

This checker binds all sources to the unchanged recipe, verifies trace hashes,
frame/PTS coverage, provider context, body/world correspondence and the shipping
model/runtime pins. It also requires matching SDK/model/runtime metadata from
the ordinary native evaluation report. Hashes bind retained evidence; they are
not independent authentication of SDK execution.

Pixel receipts hash actual `Bitmap.copyPixelsToBuffer` storage, including row
stride, before and after inference. They check that inference preserves its
canonical source bitmap. They are neither PNG hashes nor proof that Android and
Apple decoded identical colors. The JSON preserves source timestamps; no frame
index is substituted for time. Per-frame timing covers SDK inference, its owned
copy and close; decoding, hashing, serialization, shared geometry queries and
model initialization are excluded. Emulator timing is not phone performance.

Default CI does not add this extra inference workload. The exporter runs only
when explicitly selected, while its host receipt and preparation regressions
run in the existing Android script-test suite on every CI run.
