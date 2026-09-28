#!/usr/bin/env python3
"""Analyze local HangInThere physical-device qualification JSON reports.

Stdlib-only by design. This tool is descriptive: it summarizes evidence and
threshold usage but never changes or recommends runtime thresholds.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import statistics
import sys
from typing import Any, Iterable, Sequence

THERMAL_SEVERITY = {
    "unknown": -1,
    "nominal": 0,
    "fair": 1,
    "serious": 2,
    "critical": 3,
}

FORBIDDEN_KEY_FRAGMENTS = (
    "video",
    "image",
    "landmark",
    "filename",
    "location",
    "deviceidentifier",
    "deviceid",
    "account",
)

REQUIRED_TOP_LEVEL_KEYS = {
    "schemaVersion",
    "runtime",
    "visionLatency",
    "sceneRegistrationLatency",
    "stability",
    "thresholds",
    "observedMovements",
    "trackingCoverage",
    "setPhase",
    "setEndReason",
    "omittedSamples",
    "samples",
}


class ReportError(ValueError):
    pass


def _finite_number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    value = float(value)
    return value if math.isfinite(value) else None


def _percentile(values: Iterable[float], fraction: float) -> float | None:
    sorted_values = sorted(v for v in values if math.isfinite(v))
    if not sorted_values:
        return None
    fraction = min(1.0, max(0.0, fraction))
    index = round((len(sorted_values) - 1) * fraction)
    return sorted_values[index]


def _median(values: Iterable[float]) -> float | None:
    finite = [v for v in values if math.isfinite(v)]
    return statistics.median(finite) if finite else None


def _normalize_key(key: str) -> str:
    return "".join(ch for ch in key.lower() if ch.isalnum())


def _find_forbidden_keys(value: Any, path: str = "$") -> list[str]:
    found: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = _normalize_key(str(key))
            if any(fragment in normalized for fragment in FORBIDDEN_KEY_FRAGMENTS):
                found.append(f"{path}.{key}")
            found.extend(_find_forbidden_keys(child, f"{path}.{key}"))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            found.extend(_find_forbidden_keys(child, f"{path}[{index}]"))
    return found


def validate_report(report: dict[str, Any]) -> None:
    if report.get("schemaVersion") != 1:
        raise ReportError(f"unsupported schemaVersion {report.get('schemaVersion')!r}; expected 1")
    missing = sorted(REQUIRED_TOP_LEVEL_KEYS.difference(report))
    if missing:
        raise ReportError(f"missing top-level keys: {', '.join(missing)}")
    if not isinstance(report.get("samples"), list):
        raise ReportError("samples must be an array")

    forbidden = _find_forbidden_keys(report)
    if forbidden:
        raise ReportError(
            "report violates the content-free privacy schema; forbidden keys: "
            + ", ".join(forbidden[:8])
        )

    thresholds = report.get("thresholds")
    if not isinstance(thresholds, dict):
        raise ReportError("thresholds must be an object")
    for key in (
        "orientationDegrees",
        "sceneTranslationFraction",
        "sceneScaleFraction",
    ):
        value = _finite_number(thresholds.get(key))
        if value is None or value <= 0:
            raise ReportError(f"thresholds.{key} must be finite and > 0")

    previous_elapsed = -math.inf
    previous_counters = {
        "analyzedFrames": -1,
        "droppedFrames": -1,
        "analysisFailures": -1,
        "sceneRegistrationFailures": -1,
    }
    for index, sample in enumerate(report["samples"]):
        if not isinstance(sample, dict):
            raise ReportError(f"samples[{index}] must be an object")
        elapsed = _finite_number(sample.get("elapsedSeconds"))
        if elapsed is None or elapsed < previous_elapsed:
            raise ReportError(f"samples[{index}].elapsedSeconds must be finite and monotonic")
        previous_elapsed = elapsed
        for key in previous_counters:
            current = sample.get(key)
            if not isinstance(current, int) or current < previous_counters[key]:
                raise ReportError(f"samples[{index}].{key} must be a monotonic integer counter")
            previous_counters[key] = current


def load_report(path: pathlib.Path) -> dict[str, Any]:
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ReportError(f"{path}: {exc}") from exc
    if not isinstance(report, dict):
        raise ReportError(f"{path}: top-level JSON value must be an object")
    validate_report(report)
    return report


def _thermal_max(samples: Sequence[dict[str, Any]]) -> str:
    levels = [str(sample.get("thermalLevel", "unknown")) for sample in samples]
    return max(levels or ["unknown"], key=lambda level: THERMAL_SEVERITY.get(level, -1))


def _sample_values(samples: Sequence[dict[str, Any]], key: str) -> list[float]:
    values: list[float] = []
    for sample in samples:
        value = _finite_number(sample.get(key))
        if value is not None:
            values.append(value)
    return values


def _format_number(value: float | None, digits: int = 3) -> str:
    if value is None:
        return "—"
    return f"{value:.{digits}f}"


def _format_fraction_percent(value: float | None, digits: int = 2) -> str:
    if value is None:
        return "—"
    return f"{value * 100:.{digits}f}%"


def summarize_report(report: dict[str, Any], name: str) -> dict[str, Any]:
    runtime = report["runtime"]
    stability = report["stability"]
    return {
        "name": name,
        "durationSeconds": _finite_number(runtime.get("durationSeconds")) or 0.0,
        "effectiveAnalyzedFPS": _finite_number(runtime.get("effectiveAnalyzedFPS")) or 0.0,
        "dropFraction": _finite_number(runtime.get("dropFraction")),
        "analysisFailures": int(runtime.get("analysisFailures", 0)),
        "sceneRegistrationFailures": int(runtime.get("sceneRegistrationFailures", 0)),
        "visionP95Milliseconds": _finite_number(report["visionLatency"].get("p95Milliseconds")),
        "sceneRegistrationP95Milliseconds": _finite_number(
            report["sceneRegistrationLatency"].get("p95Milliseconds")
        ),
        "maximumOrientationDeltaDegrees": _finite_number(
            stability.get("maximumOrientationDeltaDegrees")
        ),
        "maximumSceneShiftFraction": _finite_number(stability.get("maximumSceneShiftFraction")),
        "maximumSceneScaleFraction": _finite_number(stability.get("maximumSceneScaleFraction")),
        "minimumTranslationConsensusPatches": stability.get(
            "minimumTranslationConsensusPatches"
        ),
        "scaleMeasurementSamples": int(stability.get("sceneScaleMeasurementSamples", 0)),
        "maximumThermalLevel": stability.get("maximumThermalLevel")
        or _thermal_max(report["samples"]),
        "observedMovements": int(report.get("observedMovements", 0)),
        "trackingCoverage": _finite_number(report.get("trackingCoverage")),
        "setEndReason": report.get("setEndReason"),
        "omittedSamples": int(report.get("omittedSamples", 0)),
    }


def _thresholds_match(reports: Sequence[dict[str, Any]]) -> dict[str, Any]:
    if not reports:
        raise ReportError("at least one report is required")
    first = reports[0]["thresholds"]
    for index, report in enumerate(reports[1:], start=2):
        if report["thresholds"] != first:
            raise ReportError(
                f"report {index} uses different thresholds; analyze threshold cohorts separately"
            )
    return first


def stationary_analysis(reports: Sequence[dict[str, Any]]) -> dict[str, Any]:
    thresholds = _thresholds_match(reports)
    orientation = [
        value
        for report in reports
        for value in _sample_values(report["samples"], "orientationDeltaDegrees")
    ]
    shift = [
        value
        for report in reports
        for value in _sample_values(report["samples"], "sceneShiftFraction")
    ]
    scale = [
        value
        for report in reports
        for value in _sample_values(report["samples"], "sceneScaleFraction")
    ]

    def metric(values: list[float], threshold: float) -> dict[str, Any]:
        maximum = max(values) if values else None
        p95 = _percentile(values, 0.95)
        usage = (maximum / threshold) if maximum is not None and threshold > 0 else None
        return {
            "sampleCount": len(values),
            "p95": p95,
            "maximum": maximum,
            "threshold": threshold,
            "maximumThresholdUsage": usage,
            "remainingHeadroom": (1.0 - usage) if usage is not None else None,
        }

    return {
        "reportCount": len(reports),
        "orientation": metric(orientation, float(thresholds["orientationDegrees"])),
        "translation": metric(shift, float(thresholds["sceneTranslationFraction"])),
        "scale": metric(scale, float(thresholds["sceneScaleFraction"])),
        "totalAnalysisFailures": sum(
            int(report["runtime"].get("analysisFailures", 0)) for report in reports
        ),
        "totalSceneRegistrationFailures": sum(
            int(report["runtime"].get("sceneRegistrationFailures", 0)) for report in reports
        ),
        "highestThermalLevel": max(
            (
                report["stability"].get("maximumThermalLevel")
                or _thermal_max(report["samples"])
                for report in reports
            ),
            key=lambda level: THERMAL_SEVERITY.get(str(level), -1),
        ),
    }


def _window_metrics(samples: Sequence[dict[str, Any]]) -> dict[str, Any]:
    if len(samples) < 2:
        return {
            "sampleCount": len(samples),
            "visionMedianMilliseconds": _median(_sample_values(samples, "visionProcessingMilliseconds")),
            "sceneMedianMilliseconds": _median(
                _sample_values(samples, "sceneRegistrationMilliseconds")
            ),
            "effectiveAnalyzedFPS": None,
            "dropFraction": None,
        }

    first, last = samples[0], samples[-1]
    elapsed0 = _finite_number(first.get("elapsedSeconds")) or 0.0
    elapsed1 = _finite_number(last.get("elapsedSeconds")) or elapsed0
    duration = elapsed1 - elapsed0
    analyzed_delta = int(last.get("analyzedFrames", 0)) - int(first.get("analyzedFrames", 0))
    dropped_delta = int(last.get("droppedFrames", 0)) - int(first.get("droppedFrames", 0))
    total_delta = analyzed_delta + dropped_delta

    return {
        "sampleCount": len(samples),
        "visionMedianMilliseconds": _median(
            _sample_values(samples, "visionProcessingMilliseconds")
        ),
        "sceneMedianMilliseconds": _median(
            _sample_values(samples, "sceneRegistrationMilliseconds")
        ),
        "effectiveAnalyzedFPS": analyzed_delta / duration if duration > 0 else None,
        "dropFraction": dropped_delta / total_delta if total_delta > 0 else None,
    }


def thermal_analysis(report: dict[str, Any]) -> dict[str, Any]:
    samples = report["samples"]
    if not samples:
        return {
            "sampleCount": 0,
            "early": _window_metrics([]),
            "late": _window_metrics([]),
            "visionMedianChangeFraction": None,
            "sceneMedianChangeFraction": None,
            "analysisFPSChangeFraction": None,
            "dropFractionChange": None,
            "maximumThermalLevel": "unknown",
        }

    window_size = max(2, len(samples) // 4) if len(samples) >= 2 else 1
    early = _window_metrics(samples[:window_size])
    late = _window_metrics(samples[-window_size:])

    def relative_change(before: Any, after: Any) -> float | None:
        before_value = _finite_number(before)
        after_value = _finite_number(after)
        if before_value is None or after_value is None or abs(before_value) < 1e-12:
            return None
        return (after_value - before_value) / before_value

    drop_before = _finite_number(early["dropFraction"])
    drop_after = _finite_number(late["dropFraction"])

    return {
        "sampleCount": len(samples),
        "early": early,
        "late": late,
        "visionMedianChangeFraction": relative_change(
            early["visionMedianMilliseconds"], late["visionMedianMilliseconds"]
        ),
        "sceneMedianChangeFraction": relative_change(
            early["sceneMedianMilliseconds"], late["sceneMedianMilliseconds"]
        ),
        "analysisFPSChangeFraction": relative_change(
            early["effectiveAnalyzedFPS"], late["effectiveAnalyzedFPS"]
        ),
        "dropFractionChange": (
            drop_after - drop_before
            if drop_before is not None and drop_after is not None
            else None
        ),
        "maximumThermalLevel": _thermal_max(samples),
    }


def _render_summary(summaries: Sequence[dict[str, Any]]) -> str:
    lines = [
        "# Device qualification summary",
        "",
        "| Run | Duration | Analysis FPS | Drop | Pose p95 | Scene p95 | Thermal | End |",
        "| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |",
    ]
    for summary in summaries:
        drop = summary["dropFraction"]
        lines.append(
            "| {name} | {duration:.1f}s | {fps:.2f} | {drop} | {pose} ms | {scene} ms | {thermal} | {end} |".format(
                name=summary["name"],
                duration=summary["durationSeconds"],
                fps=summary["effectiveAnalyzedFPS"],
                drop="—" if drop is None else f"{drop * 100:.2f}%",
                pose=_format_number(summary["visionP95Milliseconds"], 1),
                scene=_format_number(summary["sceneRegistrationP95Milliseconds"], 1),
                thermal=summary["maximumThermalLevel"],
                end=summary["setEndReason"] or "—",
            )
        )
    lines.append("")
    lines.append("This is descriptive evidence, not an automatic release verdict.")
    return "\n".join(lines)


def _render_stationary(analysis: dict[str, Any]) -> str:
    lines = [
        "# Stationary qualification envelope",
        "",
        f"Reports: {analysis['reportCount']}",
        "",
        "| Signal | Samples | p95 | Max | Runtime threshold | Max threshold usage | Remaining headroom |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    specs = (
        ("Orientation", analysis["orientation"], "°"),
        ("Translation", analysis["translation"], ""),
        ("Scale", analysis["scale"], ""),
    )
    for label, metric, unit in specs:
        usage = metric["maximumThresholdUsage"]
        headroom = metric["remainingHeadroom"]
        lines.append(
            f"| {label} | {metric['sampleCount']} | "
            f"{_format_number(metric['p95'], 4)}{unit} | "
            f"{_format_number(metric['maximum'], 4)}{unit} | "
            f"{_format_number(metric['threshold'], 4)}{unit} | "
            f"{'—' if usage is None else f'{usage * 100:.1f}%'} | "
            f"{'—' if headroom is None else f'{headroom * 100:.1f}%'} |"
        )
    lines.extend(
        [
            "",
            f"Analysis failures: {analysis['totalAnalysisFailures']}",
            f"Scene-registration failures: {analysis['totalSceneRegistrationFailures']}",
            f"Highest thermal state: {analysis['highestThermalLevel']}",
            "",
            "Do not tune thresholds from this table alone; compare multiple stationary runs with deliberate-motion trials.",
        ]
    )
    return "\n".join(lines)


def _render_thermal(name: str, analysis: dict[str, Any]) -> str:
    def percent(value: float | None) -> str:
        return "—" if value is None else f"{value * 100:+.1f}%"

    early, late = analysis["early"], analysis["late"]
    return "\n".join(
        [
            f"# Thermal/runtime drift — {name}",
            "",
            "| Metric | Early window | Late window | Change |",
            "| --- | ---: | ---: | ---: |",
            f"| Pose median latency | {_format_number(early['visionMedianMilliseconds'], 1)} ms | {_format_number(late['visionMedianMilliseconds'], 1)} ms | {percent(analysis['visionMedianChangeFraction'])} |",
            f"| Scene median latency | {_format_number(early['sceneMedianMilliseconds'], 1)} ms | {_format_number(late['sceneMedianMilliseconds'], 1)} ms | {percent(analysis['sceneMedianChangeFraction'])} |",
            f"| Analysis FPS | {_format_number(early['effectiveAnalyzedFPS'], 2)} | {_format_number(late['effectiveAnalyzedFPS'], 2)} | {percent(analysis['analysisFPSChangeFraction'])} |",
            f"| Drop fraction | {_format_fraction_percent(early['dropFraction'])} | {_format_fraction_percent(late['dropFraction'])} | {percent(analysis['dropFractionChange'])} |",
            "",
            f"Maximum thermal state: {analysis['maximumThermalLevel']}",
            "",
            "This reports observed drift only; it does not define a pass/fail thermal limit.",
        ]
    )


def analyze(paths: Sequence[pathlib.Path], profile: str) -> dict[str, Any]:
    reports = [load_report(path) for path in paths]
    summaries = [summarize_report(report, path.name) for path, report in zip(paths, reports)]

    result: dict[str, Any] = {
        "profile": profile,
        "summaries": summaries,
    }
    if profile == "stationary":
        result["stationary"] = stationary_analysis(reports)
    elif profile == "thermal":
        result["thermal"] = [
            {"name": path.name, **thermal_analysis(report)}
            for path, report in zip(paths, reports)
        ]
    return result


def render_markdown(result: dict[str, Any]) -> str:
    profile = result["profile"]
    if profile == "summary":
        return _render_summary(result["summaries"])
    if profile == "stationary":
        return _render_stationary(result["stationary"])
    if profile == "thermal":
        return "\n\n".join(
            _render_thermal(item["name"], item) for item in result["thermal"]
        )
    raise AssertionError(profile)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Analyze HangInThere physical-iPhone qualification JSON reports."
    )
    parser.add_argument(
        "--profile",
        choices=("summary", "stationary", "thermal"),
        default="summary",
        help="analysis profile (default: summary)",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="emit machine-readable analysis JSON instead of Markdown",
    )
    parser.add_argument(
        "--output",
        type=pathlib.Path,
        help="write output to a file instead of stdout",
    )
    parser.add_argument("reports", nargs="+", type=pathlib.Path)
    args = parser.parse_args(argv)

    try:
        result = analyze(args.reports, args.profile)
    except ReportError as exc:
        print(f"qualification analysis failed: {exc}", file=sys.stderr)
        return 2

    output = (
        json.dumps(result, indent=2, sort_keys=True) + "\n"
        if args.json
        else render_markdown(result) + "\n"
    )
    if args.output:
        args.output.write_text(output, encoding="utf-8")
    else:
        sys.stdout.write(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
