# Test footage and provenance

The generated fixtures are **not included** in the source archive or normal app.
Run `python3 scripts/prepare-fixtures.py` before building the iOS test target.
CI runs that command explicitly. An absent download or missing body observations
fails the check; it is not converted into a skipped or passing model test.

`source.json` pins a 32,946,819-byte public-domain pull-up B-roll video by its
Wikimedia-published SHA-1. The script additionally records the acquired source's
SHA-256, tool version, recipe, derivative SHA-256 values, and actual decoded PTS.
The source is an official-duty U.S. Marine Corps work, recorded as public domain
in the United States by [its Commons record](https://commons.wikimedia.org/wiki/File:Get_Fit-_Proper_Pull-Up_Technique,_Marine_Corps_Air_Station,_Iwakuni,_Japan_2026_(B-ROLL)_(1017606).webm).
The [original release](https://www.dvidshub.net/video/1017606) is credited to
Andrew Knight and Saul Hernandez, U.S. Marine Corps / AFN Iwakuni. No endorsement
of this app is implied. This material is for test infrastructure, not marketing.

The derived video uses its first four seconds, resized to width 640 and sampled
at 10 FPS, with audio removed. The PNG is the derivative's frame at one second.
Those are intentionally documented fixture transformations, not a claim about
the source camera's native frame rate. The original archive stays in ignored
`Data/external/`; the small derivatives stay in ignored `generated/` and are only
bundled into tests. No network access happens inside the app or tests themselves.

The real-image test requires an observed shoulder/elbow/wrist chain. The video
test uses the **app's actual AVAssetReader/Core Image/Vision path**, checks every
presentation timestamp against ffprobe, and requires some real arm observations.
These are executable backend/decoder smoke tests, **not independent joint labels,
rep labels, or an exercise-accuracy benchmark**. Do not cite them as evidence of
counting accuracy. Dip footage and held-out endpoint annotation remain P1+ work.

Separate tests create four-quadrant videos with AVAssetWriter. Their pixels are
synthetic and test orientation, actual decoding, irregular timestamps, and
playback lifecycle. They are never substituted for the real-human smoke fixture.

At source preparation time (2026-09-26), remote metadata was checked but this
session could not download or inspect the B-roll bytes. Acquisition, derived
frame inspection, the real Vision assertions, and any necessary fixture review
remain unexecuted gates. Do not weaken body assertions merely to make CI pass.