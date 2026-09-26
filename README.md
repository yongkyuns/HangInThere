# HangInThere

A lean, on-device iPhone app for pull-up and parallel-bar dip counting, with
confidence-aware, camera-view-specific range-of-motion analysis.

**Status: POC design only.** This initial package contains documentation, not a
runnable app. No pose model, dataset, iOS build, or accuracy claim has been
qualified yet.

## Start here

Read [the POC implementation and validation plan](docs/POC.md).

The intended first version has one SwiftUI app, AVFoundation camera/video input,
one selected pose backend, and deterministic Swift repetition logic. Apple
Vision is the zero-dependency baseline; MediaPipe Heavy is the first independent
comparison. The production choice depends on exercise-specific measurements,
not generic benchmark rankings.

## Product boundaries

- One person, a stationary rear camera, and explicitly supported camera placement.
- Pull-ups and parallel-bar dips selected by the user; no automatic exercise classifier.
- Live tracking and imported-video replay use the same processing and counting code.
- Checked reps, observed partial attempts, and unverified movement stay distinct.
- No login, server, cloud inference, subscription, Android layer, or model-training
  platform in the POC.

A body skeleton alone does not establish chin-over-bar clearance. The plan
includes a calibrated bar reference, a separately evaluated face-contour
measurement, and an explicit unknown result when the evidence is insufficient.
Image-plane observations are not presented as motion-capture-grade 3D measurements.

## Development without a paid Apple account

The planned GitHub workflow builds and tests against an iOS simulator and checks
an unsigned device build. It has no Apple credentials, TestFlight publishing,
or installable-IPA promise.

Physical-iPhone testing happens later from local Xcode using the owner's free
Personal Team. See [the local-device checklist](docs/POC.md#10-local-iphone-verification-with-a-free-account)
and the linked Apple documentation for current provisioning restrictions.

## First implementation milestone

Create a real Xcode project and shared scheme, run an actual Apple Vision request
on a reviewed fixture, replay a local video through the app, and execute its
first simulator test in GitHub Actions. Do not add passing placeholder tests
or badges before those operations run.

## Data and licensing

No third-party footage, model weights, personal workout recordings, signing
material, or access tokens are included. A source-code licence has not been
selected. Dataset permissions and model-asset terms must be reviewed separately
before acquisition, use, redistribution, or bundling.

## Repository

```sh
git clone https://github.com/yongkyuns/HangInThere.git
cd HangInThere
```

This repository currently contains the documentation seed only. The Xcode project,
application code, and CI workflow will be added with the first implementation
milestone; there is no runnable app or installable build yet.
