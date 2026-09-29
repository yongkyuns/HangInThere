# Live debug capture and offline reproduction

Debug capture is a development/qualification feature, not normal workout recording.
It is exposed in the DEBUG UI only and is off by default.

## What it records

Start **Debug capture** before bar calibration. The live camera's raw `CMSampleBuffer`
stream is offered to a separate bounded `AVAssetWriter` queue before Vision
inference. The analysis queue never waits for the encoder. If recording cannot
keep up, queue/writer drops are recorded instead of slowing pose inference.

When the live set finishes normally or through a tracked interruption, the app
finalizes three local files:

- `video.mov` — H.264 encoding of the same rotated camera sample stream offered
  to live analysis;
- `session.json` — counter policy, exercise/side, capture-relative set/movement
  times, confirmed bar geometry, tracking/count results, recording-drop counts,
  app version/build, and SHA-256 pins;
- `qualification.json` — the existing privacy-safe timing/thermal/stability report.

Nothing is uploaded automatically. The DEBUG UI uses the system share sheet to
export all three files together. Leaving setup before completing a set discards
an in-progress capture. The user can explicitly delete a completed local bundle.

The recorder deliberately does not write rendered overlays or screen pixels:
offline evaluation needs original camera input.

## Reproduce offline

Keep the three exported files in one directory. On macOS:

```sh
python3 scripts/debug_session.py run /path/session.json \
  --root /path/to/exported-files \
  --output Evaluation/output/debug-session-001
```

The command:

1. verifies video and qualification byte counts + SHA-256;
2. creates a private/local standard evaluation manifest;
3. runs the exact production `VideoReplayReader` + `VisionPoseEstimator`;
4. verifies decoded geometry still matches the live confirmed-bar coordinate system;
5. runs the exact production movement counter using recorded exercise, side and bar;
6. compares evaluated counter policy with the version recorded live;
7. writes `reproduction.json`.

By default, a policy mismatch is an error. For an intentional comparison of a
new counter policy against an old recording, add `--allow-policy-mismatch`.

`prepare` can be used on Linux/macOS when only integrity checking and standard
evaluation-manifest generation are needed:

```sh
python3 scripts/debug_session.py prepare /path/session.json \
  --root /path/to/exported-files \
  --output /tmp/evaluation-manifest.json
```

## Scope

A reproduced debug session is not ground truth. It helps answer:

- did offline Vision return similar usable tracking evidence?
- did a new counter policy change the rep result?
- was a field failure caused by tracking, the counter, bar geometry, or camera stability?
- were samples lost by the optional recorder itself?

Independent review/labels still belong in the session-qualification workflow.
Do not publish user debug video without separate permission review.
