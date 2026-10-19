# Apple contour pixels

A small Swift/C++ adapter around pinned OpenCV. macOS uses OpenCV 4.11.0;
iOS uses the OpenCV 4.5.3 already embedded in MediaPipeTasksCommon 0.10.21.
Linking a second OpenCV into the iOS app causes duplicate symbols.

The iOS adapter is a private, pinned ABI dependency: its 28 transitive headers
come from the official 4.5.3 source archive, preserving notices and license.
`vendor-headers.py` regenerates them using actual device/simulator preprocessors;
`source-lock.json` records source/archive and individual header checksums. The
small generated module-availability header declares only the two modules used
by this adapter; it does not assert the SDK's entire module configuration.
Platform-specific compile assertions prevent using these headers on macOS.
`check-ios-sdk.py`, run by the SDK preparation build phase, verifies the actual
archived Common binary and every required C++ symbol for all three architectures.
A runtime version guard runs before Mat construction. SDK/header changes require
renewed app linking and real pixel tests; these guards alone do not prove ABI or
pixel compatibility. The iOS product must link the prepared Common framework.

The adapter consumes one retained,
upright CGImage and a bounded crop. The native stage only extracts ordered edge
pixels: RGBA to grayscale, 5×5 Gaussian sigma 1 with reflected borders, Canny
50/100 with 3-pixel L2 gradients, then RETR_LIST/CHAIN_APPROX_NONE contours. Pixel
centers retain absolute source coordinates. No contours or alternatives are
truncated. Invalid images, crops, budgets and backend failures remain errors.

Shared Kotlin owns finite rail fitting, ambiguity, temporal acquisition and
analysis. This module supplies no apparatus, contact, camera or repetition
qualification. Run it from the native acquisition worker, never the UI thread.
The C buffer is copied into Swift values and freed before returning.

`swift test --package-path native/apple` checks pixel orientation, finite crop
coordinates, blank inputs, adjacency and error handling. Binary artifacts remain
in SwiftPM caches; the manifest pins the cross-platform Apple archive and checksum
published by [opencv-spm 4.11.0](https://github.com/yeatse/opencv-spm/tree/4.11.0).
The OpenCV license and the vendored headers' copyright, redistribution and
disclaimer notices are bundled with the Swift product.
