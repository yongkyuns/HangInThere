# Bar-specific object detector prototype

This experiment asks one narrow question before changing the iOS bar backend:
**can a small bar-specific detector identify the correct gripping apparatus region
on source-separated pull-up and dip images?** It does not estimate body pose and it
does not count repetitions.

## Architecture under test

The intended pipeline separates semantics from measurement:

```
image -> bar-specific object detector -> coarse grip-bar region
                                      -> local LSD/native edge refinement -> precise edge

image -> body-pose estimator ---------------------------------------------> joints

precise apparatus geometry + joints -> exercise analysis
```

No wrist, elbow, shoulder, person box, pose score, or repetition state enters the
object-detector training data or evaluation. The single training label is
`grip_bar`; the user has already selected the exercise.

## Data policy

`Evaluation/fixtures/bar-object-prototype.json` freezes the coarse boxes before
training. The boxes deliberately surround the relevant apparatus region rather than
pretending to be precise edge annotations.

Training uses separate public-domain DVIDS video sources:

- **Dead Hang Pullup**, DVIDS 639937 / DOD_106211182, sampled at 1 Hz. The source
  contains two fixed-camera views in each frame, so both gripping bars are labelled.
- The already byte-pinned **rear** and **side** parallel-bar-dip clips from the
  temporal evaluation, sampled at 1 Hz.

Held-out testing uses different source groups already present in the source pilot:

- rear-view pull-up photograph;
- front-view parallel-bar-dip photograph with two rails;
- bench-dip photograph as a negative control.

The held-out set is only three images. It prevents direct source leakage in this
prototype, but it is far too small for a population accuracy claim. Correlated
video frames in training are also not independent examples.

## Model

The host-only experiment uses Apple Create ML
`transferLearning(.objectPrint(revision: 1))`, 30 iterations, and no randomly
split validation data. The independent source-group test set is evaluated only
*after* training. The resulting Core ML file is a short-retention CI artifact and
is not committed or loaded by the app.

Create ML metrics and raw held-out predictions are retained. A green workflow means
that preparation, training, evaluation, and artifact creation completed. **It does
not mean the detector meets an accuracy gate.**

## Decision rule

Do not replace `VisionBarDetector` merely because this model trains. The prototype
must first demonstrate all of the following on independent controlled-setting data:

1. correct pull-up apparatus selection;
2. correct parallel-bar selection, including both visible rails where appropriate;
3. no bar detection on a bench-dip negative;
4. materially better semantic selection than generic line extraction;
5. a useful predicted region for a second-stage edge refiner.

If this small source-separated screen is promising, the next experiment combines
its predicted region with the already benchmarked LSD/native-contour edge refinement
and scores finite-edge localization. If it is not promising, expand rights-clear
bar-specific data before changing production code.

Public datasets identified during research (for a later larger experiment) include
the CC BY 4.0 295-image Pullup/Dips Bar dataset and the public-domain Pullup Bar
Detection dataset on Roboflow. They are not silently downloaded or mixed into this
prototype, so an unavailable API key cannot change the training population.