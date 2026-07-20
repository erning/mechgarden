#!/usr/bin/env python3
"""Collect offline-training datasets for Proteus.

Runs battles against the PERSISTENT robots directory (robocode/robots) so the
robot's .data files survive — `just duel` runs in a temp dir that is deleted
afterwards. Enable Dataset.ENABLED in bots/proteus (never commit `true`),
deploy, then for example:

  python3 scripts/collect_dataset.py -e RaikoNano FloodMini -n 35
  python3 scripts/collect_dataset.py -c basic classic -n 35

Datasets land in robocode/robots/.data/zen/Proteus.data/*.pgf
"""
import argparse
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

import duel  # noqa: E402

DATA_DIR = duel.ROBOCODE_DIR / "robots" / ".data"
HOLD_DIR = ROOT / ".cache" / "proteus-datasets"
COLLECT_ROBOTS = ROOT / ".cache" / "proteus-collect-robots"


def harvest() -> None:
    """Move .pgf files out of the collection robots dir (its 200KB quota counts
    every file present, so datasets cannot accumulate there)."""
    HOLD_DIR.mkdir(parents=True, exist_ok=True)
    data_dir = COLLECT_ROBOTS / ".data" / "zen" / "Proteus.data"
    if not data_dir.exists():
        return
    for pgf in data_dir.glob("*.pgf"):
        if pgf.stat().st_size > 0:
            shutil.move(str(pgf), HOLD_DIR / pgf.name)
        else:
            pgf.unlink()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--robot", "-r", default="zen.Proteus")
    parser.add_argument("--enemies", "-e", nargs="*", default=[])
    parser.add_argument("--catalog", "-c", nargs="*", default=[])
    parser.add_argument("--rounds", "-n", type=int, default=35)
    parser.add_argument("--keep", action="store_true", help="keep previous datasets in the holding dir")
    args = parser.parse_args()

    if not args.keep:
        shutil.rmtree(HOLD_DIR, ignore_errors=True)
    HOLD_DIR.mkdir(parents=True, exist_ok=True)

    candidates = duel.load_candidates()
    robot = duel.resolve_one(candidates, args.robot, "robot")
    enemies = [duel.resolve_one(candidates, query, "enemy") for query in args.enemies]
    enemies.extend(duel.match_catalogs(candidates, args.catalog))
    if not enemies:
        parser.error("no enemies given")

    # A minimal robots dir per pairing: the full robocode/robots dir makes some
    # jars resolve as invalid (and just duel's temp dir would swallow .data).
    robot_source = robot.source.resolve()
    results = Path("/tmp/proteus-collect-results.txt")
    for enemy in enemies:
        shutil.rmtree(COLLECT_ROBOTS, ignore_errors=True)
        COLLECT_ROBOTS.mkdir(parents=True)
        os.symlink(robot_source, COLLECT_ROBOTS / robot_source.name)
        enemy_source = enemy.source.resolve()
        os.symlink(enemy_source, COLLECT_ROBOTS / enemy_source.name)
        completed = duel.run_robocode_engine(
            COLLECT_ROBOTS,
            f"{robot.class_name},{enemy.class_name}",
            args.rounds,
            results,
            robot.class_name,
        )
        status = "ok" if completed.returncode == 0 else f"exit {completed.returncode}"
        print(f"{enemy.selector}: {status}", flush=True)
        harvest()
    print(f"datasets: {HOLD_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
