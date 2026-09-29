# Live debug-session capture

Live Workout can optionally record a **local developer qualification package**.
This is separate from ordinary content-free qualification telemetry and is off by
default.

## On iPhone

Open **Device qualification** and tap **Start local debug capture** before bar
calibration. The app refuses to start a debug capture while a bar reference is
already confirmed; clear the bar first. Exercise and tracking side are locked
while recording so one package cannot silently change configuration mid-capture.
Bar calibration is also held until the movie recorder has started and at least
one analyzed source frame is anchored inside the recording, avoiding a
start-recording/calibration race. The app records video from a separate
`AVCaptureMovieFileOutput` while the
existing `AVCaptureVideoDataOutput` continues real-time Vision analysis. No
microphone input is added.

A capture stops automatically when the set finishes or is interrupted. Export
creates a `.hangdebug` package containing:

- `video.mov` — recorded live camera stream;
- `session.json` — counter policy, exercise/side, bar geometry, camera-source
  PTS plus paired movie-elapsed anchors, movement timeline, tracking/count
  results, app version/build;
- `qualification.json` — snapshot of the existing device qualification report;
- `hashes.json` — SHA-256 and byte pins for the other three files.

Successful export deletes the temporary in-app movie. Leaving the screen without
export also discards the temporary capture. Nothing is uploaded automatically.

The movie is an independent camera-session recording rather than a dump of only
frames that happened to finish Vision inference. This is deliberate: offline
evaluation should be able to re-run the full source even when live analysis
dropped frames. Video stabilization is explicitly disabled on both the analysis
and movie connections so the two paths do not use different geometric warps.

## Verify on a Mac

```sh
python3 scripts/debug_session.py verify \
  /path/to/HangInThere-debug-session-....hangdebug \
  --output /tmp/debug-report.json
```

Verification recalculates every SHA-256/byte pin before trusting metadata, then
cross-checks the duplicated counter policy, exercise/side, count, tracking, and
set-termination fields between `session.json` and `qualification.json`. It
also verifies that bar-calibration and live-set source timestamps lie inside the
recorded capture anchors and that calibration precedes the set. Schema-v2
packages additionally require paired camera-source/movie-elapsed anchors. Older
schema-v1 packages remain verifiable but do not have enough timing provenance for
automatic set-window replay.

## Run the production offline pose pipeline

Manifest generation requires explicit rights/consent evidence and always marks
the capture private:

```sh
python3 scripts/debug_session.py manifest \
  /path/to/session.hangdebug \
  --rights-evidence "Participant consented to private local evaluation." \
  --output /tmp/debug-manifest.json

./scripts/evaluate.sh /tmp/debug-manifest.json \
  --root /path/to/session.hangdebug \
  --output Evaluation/output/debug-run
```

The generated manifest uses the exact pinned `video.mov`, does not authorize
public outputs, and carries the originating debug-session policy version,
exercise/side, source-timing metadata, and set summary alongside the clip. The
production evaluator ignores these extra provenance fields for inference, but
they remain available to compare a replay against the policy that produced the
live result.

The app can also import `video.mov` directly through its existing Workout Review
file importer to replay the same recorded session interactively.

## Compare the live set with the current production replay

Schema-v2 packages can run the full workflow with one command on macOS:

```sh
python3 scripts/debug_session.py compare \
  /path/to/session.hangdebug \
  --rights-evidence "Participant consented to private local evaluation." \
  --output /tmp/debug-replay
```

The command verifies the package, runs the current production Apple Vision
pipeline on the pinned movie, maps the captured live-set source-time window into
movie time using the paired clock anchors, extracts only that set window,
renumbers frames without inventing FPS, scales the captured bar edge into the
offline image geometry, and feeds those observations through the exact Swift
movement counter.

`comparison.json` retains both the captured and replay policy versions and
reports live/replay movement counts, partial/interrupted attempts, tracking
coverage, chronological movement-event timing deltas, the selected movie window,
clock-rate mapping, endpoint alignment errors, source revision, and content
hashes. A policy change is reported explicitly rather than treated as a
failure. The result is a movement/debug diagnostic, not strict-form
qualification.

The output directory also retains the generated private manifest, full pose
output, set-window observations, and counter report so a discrepancy can be
inspected without re-running the pipeline.

## Limits

This first version does not export a live per-frame pose trace. Re-running the
movie therefore exercises the current Vision model again, which is desirable for
regression evaluation but cannot by itself distinguish a model-version change
from the original live pose output. A bounded/streamed observation trace can be
added later if that distinction becomes important.

Physical-iPhone recording overhead, long-session storage, and exact movie-vs-live
frame alignment remain device qualification gates. The paired anchors make
offline alignment explicit and measurable, but they do not substitute for that
future on-device qualification.
