"""Frozen temporal reference validation shared by raw-counter and native replay diagnostics."""
from __future__ import annotations
import math

try:
    from . import evaluation as ev
    from .temporal_events import intervals
except ImportError:
    import evaluation as ev
    from temporal_events import intervals

DEFINITION = {"pullUp": "observed_start_to_top", "dip": "observed_top_bottom_top"}
EXERCISE = {"pullUp": "pull_up", "dip": "parallel_bar_dip"}



def reference_check(ref, clip):
    ev.require(ref.get("schema_version") == 1 and ref.get("id") == clip["id"], "Reference identity mismatch")
    development_exposed = ref.get("development_after_prior_counter_exposure") is True
    ev.require((ref.get("reviewed_without_counter_output") is True or development_exposed) and ref.get("provenance"),
               "Temporal label provenance required")
    ev.require(ref.get("form_verification") == "unverified", "Movement labels do not establish valid form")
    exercise = ref.get("exercise")
    ev.require(exercise in DEFINITION and EXERCISE[exercise] == clip["exercise"], "Exercise mismatch")
    ev.require(ref.get("event_definition") == DEFINITION[exercise] and ref.get("side") in ("left", "right"), "Event or arm policy missing")
    ev.require(clip["media"]["kind"] == "video" and ref.get("media_sha256") == clip["media"]["files"][0]["sha256"], "Wrong temporal media")
    pts = ref.get("frame_pts_seconds")
    ev.require(isinstance(pts, list) and len(pts) > 1 and all(ev.number(t) and t >= 0 for t in pts)
               and all(a < b for a, b in zip(pts, pts[1:])), "Full actual source PTS are required")
    ev.require(len(pts) == clip["media"].get("expected_frames"), "Reference frame count mismatch")
    span = ref.get("span_seconds")
    ev.require(isinstance(span, list) and len(span) == 2 and all(ev.number(t) for t in span)
               and abs(span[0] - pts[0]) < 1e-5 and pts[-1] < span[1] <= pts[-1] + 1, "Invalid full observation span")
    windows = intervals(ref.get("events"), span)
    excluded = intervals(ref.get("ungradable_intervals"), span, reason=True)
    edge = ref.get("bar_reference_edge")
    if edge is not None:
        ev.require(isinstance(edge, list) and len(edge) == 4 and all(ev.number(x) for x in edge),
                   "Invalid fixed apparatus reference")
        ev.require(math.hypot(edge[2]-edge[0], edge[3]-edge[1]) >= 2, "Degenerate apparatus reference")
        image_size = ref.get("bar_reference_image_size")
        ev.require(isinstance(image_size, list) and len(image_size) == 2 and
                   all(ev.number(x) and x > 0 for x in image_size),
                   "Apparatus reference needs its reviewed image size")
        ev.require(isinstance(ref.get("bar_reference_provenance"), str) and ref["bar_reference_provenance"].strip(),
                   "Apparatus reference needs provenance")
    tolerance = ref.get("tolerance_seconds")
    ev.require(ev.number(tolerance) and 0 <= tolerance <= .5, "Invalid predeclared timing tolerance")
    for a, b in windows:
        ev.require(all(b + tolerance < x or a - tolerance >= y for x, y in excluded), "Event window overlaps ungradable time")
    return windows, excluded
