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

Both apps use [`shared/`](../shared/README.md) for motion analysis, automatic
readiness/set boundaries and recovery/storage policy. The iOS app links its
static `WorkoutCore` framework. The established Swift counter/setup remains
the policy-v8 reference, protected by per-frame Kotlin parity tests. Platform
pose predictions and native performance require their own evaluation.

The project and shared scheme retain relative paths within this directory.
Historical result JSON and frozen evaluation specifications retain the paths
recorded at their original revisions, before the move under `ios/`.

## Shared workout analysis

Install JDK 17 and keep it available to Xcode (`JAVA_HOME` when building from the
shell). The Xcode build phase runs the repository Gradle wrapper and links the
static `WorkoutCore` framework for its SDK/architecture. It uses Apple-only
configuration and does not require an Android SDK. See
[automatic workout analysis](../docs/workout/AUTOMATIC_ANALYSIS.md).
