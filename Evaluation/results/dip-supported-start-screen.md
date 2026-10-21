# Supported dip acquisition screen — 2026-10-18

Neither a heavier pose model nor a broader supported-hold start has demonstrated
recovery of the missed public dips. Both candidates remain outside the app. The
[audit](dip-supported-start-screen.audit.json) binds retained public-only local
artifacts and records the qualifications separately.

The pinned Android Heavy task, run through Python CPU VIDEO inference over all
300 controlled-video source frames, produces zero matched events on either side
under the unchanged experimental world-geometry screen: nine misses, zero extras
per side. This is a Python model screen, not Android SDK or device qualification.

A separate shared-code prototype requires three actual supported observations
over 120 ms, bounded shoulder/angle drift, retained grip, and every available
opposite-arm contradiction check. Exact rational time comparisons and explicit
source ownership prevent stale callbacks or rounding from supplying a hold.
Its receipt establishes activity only; it never accepts a rep or proves an upper
endpoint. Twelve authored contract tests pass.

The actual controlled-video screen freezes each side's first-frame LSD proposal
and bypasses independent camera/apparatus qualification solely to isolate this
candidate. It finds 28 left-arm and 19 right-arm supported frames, no extended
starting frames, and **zero qualified holds** on either side. The prototype was
removed from shipping sources. No count, geometry, confidence or timing criterion
was relaxed to match labels.

The experiment exposed separate bugs in the existing shared readiness gate:
mixed-timescale Double subtraction can misclassify both 120 ms holds and 350 ms
gaps, while faults or overload switches could erase its time fence. The app's
gate now reuses exact source-duration comparisons, revokes pending dwell on
faults, and retains the active clock until its owner explicitly resets it. Both
apps already consume this common gate. The scalar compatibility API requires an
explicit reset before switching clock representations.

Seven readiness regressions fail against the prior implementation. The repaired
implementation passes all 446 JVM, 433 Kotlin Native and 31 Android unit tests,
including the unchanged Swift counting/setup oracles. The actual shipping Swift
bridge passes 160 readiness checks and the existing host performance budgets
(readiness p95 0.001471 ms across 2,000 authored frames). This measures host FFI,
not camera/model/device throughput. Original video qualification is reported on
PR #29 and issue #31. This timing repair
does not demonstrate a public dip detection improvement: the last frozen
production result remains **1/26 matched, 25 misses, zero extras** over all 1,866
frames. The detection goal remains active and PR #29 remains draft.
