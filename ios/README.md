# iOS client

`HangInThere/` contains the native SwiftUI app: `Capture` owns AVFoundation camera
and replay input, `Pose` owns Apple Vision adapters, `Analysis` contains the Swift
workout reference, and `App`/`UI` coordinate sessions and presentation.
`HangInThereTests/` contains core tests, Apple-framework integration tests, and
reviewed test-fixture specifications. Generated test media is ignored.

Open the project from the repository root:

```sh
open ios/HangInThere.xcodeproj
./scripts/test-core.sh
./scripts/test-ios.sh mechanics
```

Build scripts resolve the repository root themselves and keep outputs in
`build/`. For real-Vision and host integration tests, prepare the pinned public
fixtures using the commands in the [root README](../README.md#tests).

The app currently runs the Swift workout implementation. Android runs the Kotlin
core in [`shared/`](../shared/README.md); parity tests compare their behavior on
identical observations. Building the `WorkoutCore` iOS framework does not mean
the app uses it. Platform pose predictions and native performance require their
own evaluation.

The project and shared scheme retain relative paths within this directory.
Historical result JSON and frozen evaluation specifications retain the paths
recorded at their original revisions, before the move under `ios/`.
