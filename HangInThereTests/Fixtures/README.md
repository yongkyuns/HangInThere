# Real-video smoke fixture

`pullups.mov` is downloaded explicitly before build/test; it is not fetched by the
app and no test reaches the network. Missing media is a test failure, never a skip.

Source: [Pull-ups - exercise demonstration video.webm](https://commons.wikimedia.org/wiki/File:Pull-ups_-_exercise_demonstration_video.webm)
by **FitnessScape**, from [Half Rack Workout](https://www.youtube.com/watch?v=0I6q9NqK9tM).
The Commons file page identifies the media license as
[CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).
[Page revision reviewed](https://commons.wikimedia.org/w/index.php?title=File:Pull-ups_-_exercise_demonstration_video.webm&oldid=1145684599).
Retrieved derivative: Wikimedia's 360p MPEG-4/QuickTime transcode of the six-second
clip. Changes relative to the source: platform transcoding and downscaling; no
new exercise labels or anatomical annotations. No endorsement is implied.

This is ONE smoke fixture, not a held-out accuracy dataset. Tests check real
Vision landmark extraction and timestamp-preserving video integration. They do
not certify rep counts, chin clearance, dip tracking, or physical-device speed.
Keep this attribution with redistributed fixtures and derived visual artifacts.
Normal CI publishes textual logs and test results without fixture screenshots.
