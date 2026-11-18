#!/usr/bin/env python3
# Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
"""Reads a guided tracking test (OpenView Experience) from logcat and grades it.

The experience writes, while its guided test runs:
  OV_TEST_STEP index=2 id=forward kind=MOVE seconds=6 expected=0.0,0.0,-1.0
  OV_TEST t=12.345 step=2 pos=0.0123,-0.0040,-0.9980 yaw=1.2 pitch=-3.0 flags=VT
and the runtime adds its own tracker events ("SLAM tracking lost", ...).

    analyze.py file logcat.txt            grade a saved log
    analyze.py run --serial IP:PORT       start the test on the phone, wait, grade it

Positions are the head in the recentred OpenXR LOCAL space: metres, -Z ahead.
A move is measured between the still step before it and the one after it,
so the time the user takes to start or stop walking does not matter.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

STEP_RE = re.compile(r"OV_TEST_STEP index=(\d+) id=(\S+) kind=(\S+) seconds=(\d+) expected=(\S+)")
SAMPLE_RE = re.compile(
    r"OV_TEST t=([-\d.]+) step=(-?\d+) pos=([-\d.]+),([-\d.]+),([-\d.]+) yaw=([-\d.]+) pitch=([-\d.]+) flags=(\S\S)")
EVENT_RE = re.compile(r"SLAM tracking (lost[^:]*|started)|SLAM status: (\w[\w ]*?),")

# Grading thresholds. Quest-class tracking holds a few millimetres; these are the
# bars a phone with one camera must clear to feel stable, not to match a Quest.
STILL_GOOD_M, STILL_OK_M = 0.03, 0.10
SCALE_GOOD, SCALE_OK = 0.10, 0.25          # |ratio - 1|
DIRECTION_GOOD_DEG, DIRECTION_OK_DEG = 15.0, 30.0
JUMP_SPEED_M_S = 2.5                        # faster than a careful walk in a headset
JUMP_STEP_M = 0.25                          # one sample to the next
# Turning the head swings the eyes around the neck (~0.1 m); more is invented translation.
TURN_SPREAD_GOOD_M, TURN_SPREAD_OK_M = 0.12, 0.25


@dataclass
class Step:
    index: int
    id: str
    kind: str
    expected: tuple[float, float, float] | None
    samples: list[tuple[float, float, float, float, str]] = field(default_factory=list)  # t, x, y, z, flags


def parse(lines) -> tuple[list[Step], list[str]]:
    steps: dict[int, Step] = {}
    events: list[str] = []
    for line in lines:
        m = STEP_RE.search(line)
        if m:
            exp = None if m.group(5) == "any" else tuple(float(v) for v in m.group(5).split(","))
            steps[int(m.group(1))] = Step(int(m.group(1)), m.group(2), m.group(3), exp)
            continue
        m = SAMPLE_RE.search(line)
        if m:
            step = steps.get(int(m.group(2)))
            if step is not None:
                step.samples.append((float(m.group(1)), float(m.group(3)), float(m.group(4)),
                                     float(m.group(5)), m.group(8)))
            continue
        m = EVENT_RE.search(line)
        if m and (m.group(1) or (m.group(2) and m.group(2) != "tracking")):
            events.append(line.strip())
    return [steps[i] for i in sorted(steps)], events


def mean(points):
    n = len(points)
    return tuple(sum(p[i] for p in points) / n for i in range(3)) if n else None


def sub(a, b):
    return tuple(x - y for x, y in zip(a, b))


def norm(v):
    return math.sqrt(sum(x * x for x in v))


def settled(step: Step):
    """Mean head position over the second half of a still step (the user has stopped by then)."""
    pts = [s[1:4] for s in step.samples]
    return mean(pts[len(pts) // 2:]) if pts else None


def grade(value, good, ok):
    return "BIEN" if value <= good else "ACEPTABLE" if value <= ok else "MAL"


def analyse(steps: list[Step], events: list[str]) -> dict:
    report = {"steps": [], "jumps": [], "events": events}
    all_samples = []
    for step in steps:
        all_samples += [(step.id,) + s for s in step.samples]
        tracked = sum(1 for s in step.samples if s[4][1] == "T")
        entry = {"id": step.id, "kind": step.kind, "samples": len(step.samples),
                 "tracked": round(tracked / len(step.samples), 3) if step.samples else 0.0}
        if step.kind == "STILL" and step.samples and step.expected is not None:
            centre = mean([s[1:4] for s in step.samples])
            drift = max(norm(sub(s[1:4], centre)) for s in step.samples)
            entry.update(drift_m=round(drift, 3), grade=grade(drift, STILL_GOOD_M, STILL_OK_M))
        if step.kind == "ROTATE" and step.samples:
            pts = [s[1:4] for s in step.samples]
            path = sum(norm(sub(b, a)) for a, b in zip(pts, pts[1:]))
            spread = max(norm(sub(p, pts[0])) for p in pts)
            entry.update(path_m=round(path, 3), spread_m=round(spread, 3),
                         grade=grade(spread, TURN_SPREAD_GOOD_M, TURN_SPREAD_OK_M))
        report["steps"].append(entry)

    # Moves: between the settled positions of the neighbouring still steps.
    for i, step in enumerate(steps):
        if step.kind != "MOVE" or step.expected is None or not (0 < i < len(steps) - 1):
            continue
        before, after = settled(steps[i - 1]), settled(steps[i + 1])
        entry = next(e for e in report["steps"] if e["id"] == step.id)
        if before is None or after is None:
            entry["grade"] = "SIN DATOS"
            continue
        moved = sub(after, before)
        entry["moved_m"] = [round(v, 3) for v in moved]
        want = norm(step.expected)
        if want < 1e-6:  # crouch and stand up: should come back where it started
            back = norm(moved)
            entry.update(return_error_m=round(back, 3), grade=grade(back, 0.05, 0.15))
            continue
        ratio = norm(moved) / want
        horizontal = (moved[0], moved[2])
        wanted = (step.expected[0], step.expected[2])
        cos = (horizontal[0] * wanted[0] + horizontal[1] * wanted[1]) / max(
            1e-9, math.hypot(*horizontal) * math.hypot(*wanted))
        angle = math.degrees(math.acos(max(-1.0, min(1.0, cos))))
        scale_grade = grade(abs(ratio - 1), SCALE_GOOD, SCALE_OK)
        dir_grade = grade(angle, DIRECTION_GOOD_DEG, DIRECTION_OK_DEG)
        worst = max((scale_grade, dir_grade), key=["BIEN", "ACEPTABLE", "MAL"].index)
        entry.update(scale=round(ratio, 3), direction_error_deg=round(angle, 1), grade=worst)

    for a, b in zip(all_samples, all_samples[1:]):
        dt = b[1] - a[1]
        step_m = norm(sub(b[2:5], a[2:5]))
        if dt <= 0:
            continue
        if step_m > JUMP_STEP_M or (dt >= 0.05 and step_m / dt > JUMP_SPEED_M_S):
            report["jumps"].append({"step": b[0], "t": round(b[1], 3), "jump_m": round(step_m, 3),
                                    "dt_s": round(dt, 3)})

    stills = [s for s in steps if s.kind == "STILL" and s.expected is not None and s.samples]
    if len(stills) >= 2:
        closure = norm(sub(settled(stills[-1]), settled(stills[0])))
        report["loop_closure_m"] = round(closure, 3)
        report["loop_grade"] = grade(closure, 0.05, 0.20)
    total = sum(len(s.samples) for s in steps)
    report["tracked_fraction"] = round(sum(
        sum(1 for x in s.samples if x[4][1] == "T") for s in steps) / total, 3) if total else 0.0
    return report


def render(report: dict) -> str:
    out = ["Paso              tipo     muestras rastreado  resultado"]
    for e in report["steps"]:
        detail = ""
        if "drift_m" in e:
            detail = f"deriva {e['drift_m'] * 100:.1f} cm"
        elif "scale" in e:
            detail = f"movido {e['moved_m']} m · escala {e['scale']:.2f} · dirección {e['direction_error_deg']:.0f}°"
        elif "return_error_m" in e:
            detail = f"vuelve a {e['return_error_m'] * 100:.1f} cm"
        elif "path_m" in e:
            detail = (f"se aleja hasta {e['spread_m'] * 100:.0f} cm al girar"
                      f" · temblor acumulado {e['path_m'] * 100:.0f} cm")
        out.append(f"{e['id']:<17} {e['kind']:<8} {e['samples']:>8} {e['tracked'] * 100:>8.0f} %  "
                   f"{e.get('grade', ''):<10} {detail}")
    out.append(f"Rastreado en total: {report['tracked_fraction'] * 100:.0f} %")
    if "loop_closure_m" in report:
        out.append(f"Cierre del recorrido: {report['loop_closure_m'] * 100:.1f} cm ({report['loop_grade']})")
    out.append(f"Saltos (> {JUMP_STEP_M} m entre muestras o > {JUMP_SPEED_M_S} m/s): {len(report['jumps'])}")
    for j in report["jumps"][:20]:
        out.append(f"  t={j['t']:.2f}s en {j['step']}: {j['jump_m'] * 100:.0f} cm en {j['dt_s'] * 1000:.0f} ms")
    if report["events"]:
        out.append("Eventos del tracker:")
        out += ["  " + e for e in report["events"][:20]]
    return "\n".join(out)


KEEP = ("OV_TEST", "SLAM ", "OV_VIO", "OpenViewXR")


def run_on_device(args) -> list[str]:
    """Streams logcat while the test runs: phones log so much that a dump taken at
    the end has already rotated the first steps out of the ring buffer."""
    adb = [args.adb, "-s", args.serial]
    subprocess.run(adb + ["logcat", "-c"], check=True, capture_output=True)
    reader = subprocess.Popen(adb + ["logcat", "-v", "time"], stdout=subprocess.PIPE, text=True,
                              encoding="utf-8", errors="replace")
    subprocess.run(adb + ["shell", "am force-stop dev.openview.experience; input keyevent KEYCODE_WAKEUP; "
                          "am start -n dev.openview.experience/.MainActivity --ez camera false --ez test true"],
                   check=True, capture_output=True)
    deadline = time.time() + args.timeout
    lines: list[str] = []
    samples = 0
    try:
        for line in reader.stdout:
            if any(k in line for k in KEEP):
                lines.append(line.rstrip("\n"))
                if "OV_TEST t=" in line:
                    samples += 1
                    if samples % 75 == 0:
                        print(f"{samples} muestras", file=sys.stderr)
            if "OV_TEST_END" in line or "OV_TEST_CANCEL" in line or time.time() > deadline:
                break
    finally:
        reader.kill()
    if args.save:
        Path(args.save).write_text("\n".join(lines), encoding="utf-8")
    return lines


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub_p = parser.add_subparsers(dest="mode", required=True)
    f = sub_p.add_parser("file")
    f.add_argument("log", type=Path)
    r = sub_p.add_parser("run")
    r.add_argument("--serial", required=True)
    r.add_argument("--adb", default="adb")
    r.add_argument("--timeout", type=int, default=150)
    r.add_argument("--save", help="write the raw logcat here")
    for p in (f, r):
        p.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    lines = (args.log.read_text(encoding="utf-8", errors="replace").splitlines() if args.mode == "file"
             else run_on_device(args))
    steps, events = parse(lines)
    if not steps:
        print("No hay pasos OV_TEST_STEP en el registro: ¿se lanzó la prueba guiada?", file=sys.stderr)
        return 2
    report = analyse(steps, events)
    print(json.dumps(report, indent=2, ensure_ascii=False) if args.json else render(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())
