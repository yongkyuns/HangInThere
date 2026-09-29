# Physical iPhone live-workout qualification

This protocol turns the in-app **Device qualification** report into repeatable evidence for the live workout path. It is for engineering qualification, not customer-facing workout scoring.

## Exported evidence

The JSON report is deliberately content-free. It includes:

- elapsed session time;
- analyzed / dropped / failed frame counters;
- effective analyzed FPS;
- Apple Vision body-pose latency;
- static-scene registration latency and failure count;
- Core Motion orientation delta;
- peripheral translation consensus and image-space shift;
- availability/value of the global homographic scale measurement;
- thermal state;
- set phase, count, tracking coverage, and end reason;
- the compiled provisional stability thresholds.

It contains **no video, images, pose landmarks, filenames, location, account data, or device identifiers**.

Snapshots are stored at most once per second and capped at 900 samples (15 minutes). Runtime counters remain current after the snapshot cap.

## Offline analysis

Use **Save JSON qualification report** in the in-app **Device qualification**
disclosure. iOS exports a timestamped `.json` file that can be saved to Files,
AirDropped, or shared to another local destination. Then analyze one or more files
with the stdlib-only helper:

```sh
# One or more ordinary runs
python3 scripts/analyze_device_qualification.py report-a.json report-b.json

# Combine multiple stationary runs that used the exact same compiled thresholds
python3 scripts/analyze_device_qualification.py \
  --profile stationary \
  stationary-01.json stationary-02.json stationary-03.json

# Compare the early and late windows of a thermal-soak report
python3 scripts/analyze_device_qualification.py \
  --profile thermal \
  thermal-soak-01.json

# Machine-readable analysis for spreadsheets/notebooks
python3 scripts/analyze_device_qualification.py \
  --profile stationary --json --output stationary-analysis.json \
  stationary-*.json
```

The analyzer validates schema version, monotonic counters, and the report privacy
boundary before processing. Stationary analysis reports p95/max observed
orientation, translation, and scale together with **how much of the compiled
runtime threshold was used** and the remaining headroom. It refuses to combine
reports built with different threshold values.

Thermal analysis compares the first and last quarter of the stored snapshots for
pose latency, scene-registration latency, effective analyzed FPS, and drop fraction.

These outputs are descriptive evidence only. The analyzer deliberately does not
produce a pass/fail release verdict or automatically recommend replacement
thresholds.

## Q1 — stationary baseline (5 minutes)

Use a tripod or rigid mount. Complete live setup/bar calibration, then do not touch the phone for five minutes. Normal athlete motion is allowed.

Export the report and record maximum orientation delta, background translation, homographic scale, minimum translation consensus, scale-measurement availability, body-pose and scene-registration latency, analyzed FPS, drop fraction, failures, and maximum thermal state.

Use this distribution before changing the provisional 1.5° / 0.8% / 1.2% gates.

## Q2 — normal pull-up set

Perform at least 10 ordinary pull-up movement cycles with a fixed camera. Compare the observed movement timeline against manual observation and inspect tracking coverage, latency, drops, registration failures, and camera-stability metrics. Athlete motion must not look like camera motion.

## Q3 — normal dip set

Repeat Q2 for parallel-bar dips with the intended side view.

## Q4 — deliberate phone rotation

After calibration, introduce roughly 0.5°, 1°, and 2° rotation disturbances, returning to the original orientation between trials. A tripod head with angle markings is preferable. The current gate is 1.5° sustained for 0.25 s.

## Q5 — deliberate lateral translation

Translate the mounted phone sideways without intentionally rotating it. Exercise several small distances (for example ~5, 10, 20, 40 mm at the normal setup distance). The current image-space translation gate is 0.8% of image short side sustained for 0.25 s.

## Q6 — deliberate toward/away motion

Move the camera toward/away from the scene while approximately preserving orientation. The global homographic scale gate is 1.2% sustained for 0.25 s. Confirm the set ends with sceneScaled rather than sceneShifted for approximately centered toward/away motion.

## Q7 — 15-minute thermal soak

Run live analysis continuously for 15 minutes with periodic sets. Capture reports near the beginning and end. Compare ProcessInfo thermal state, body-pose Vision p95/max latency, scene-registration p95/max latency/failures, analyzed FPS/drop fraction, inference failures, and tracking coverage.

Do not restart the app to hide accumulated thermal load.

## Tuning rule

Do not tune from one deliberate-motion trial. First establish multiple stationary runs, then preserve a clear margin between stationary noise and the smallest reliably detected deliberate disturbance.

Until physical runs exist, all current camera-stability thresholds are **engineering defaults**, not validated limits.

A useful first POC gate is:

- no false stability invalidation while stationary;
- no unexpected pose/registration failures;
- no unbounded latency or drop growth during thermal soak;
- deliberate rotation/lateral/scale disturbances invalidate calibration reliably;
- ordinary pull-up/dip motion does not invalidate a fixed camera.

Counting accuracy and form-validation qualification remain separate.