# Live debug-session capture

Live Workout can optionally record a **local developer qualification package**.
This is separate from ordinary content-free qualification telemetry and is off by
default.

## On iPhone

Open **Device qualification** and tap **Start local debug capture** before bar
calibration. The app refuses to start a debug capture while a bar reference is
already confirmed; clear the bar first. Exercise and tracking side are locked
while recording so one package cannot silently change configuration mid-capture.
The app records video from a separate `AVCaptureMovieFileOutput` while the
existing `AVCaptureVideoDataOutput` continues real-time Vision analysis. No
microphone input is added.

A capture stops automatically when the set finishes or is interrupted. Export
creates a `.hangdebug` package containing:

- `video.mov` — recorded live camera stream;
- `session.json` — counter policy, exercise/side, bar geometry, camera-source
  PTS anchors, movement timeline, tracking/count results, app version/build;
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
set-termination fields between `session.json` and `qualification.json`.

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

## Limits

This first version does not export a live per-frame pose trace. Re-running the
movie therefore exercises the current Vision model again, which is desirable for
regression evaluation but cannot by itself distinguish a model-version change
from the original live pose output. A bounded/streamed observation trace can be
added later if that distinction becomes important.

Physical-iPhone recording overhead, long-session storage, and exact movie-vs-live
frame alignment remain device qualification gates.
