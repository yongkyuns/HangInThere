The two public MediaPipe Tasks model bundles are pinned in `manifest.json` and verified at runtime on both platforms. Their purpose is optional grip metadata; the primary body trackers and rep counting policies remain unchanged.

- [Hand Landmarker documentation and model card](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker)
- [Pose Landmarker documentation and model card](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker)
- [MediaPipe source and Apache 2.0 license](https://github.com/google-ai-edge/mediapipe)

The iOS binary distribution is 0.10.21 (official Google archives; supports Intel and ARM simulators). Android retains the existing checksum-pinned 0.10.32 Tasks Vision runtime. The same weights and shared analysis policy do not imply identical floating-point predictions or native performance. `scripts/prepare-grip-sdk.sh` pins the iOS archives and keeps downloaded SDK binaries outside Git.
