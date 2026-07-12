#!/usr/bin/env python3
"""Run 1v1 Robocode duels against selected enemies."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Sequence

import refs


ROOT = Path(__file__).resolve().parent.parent
ROBOCODE_DIR = ROOT / "robocode"
ROBOTS_DIR = ROBOCODE_DIR / "robots"
BOTS_DIR = ROOT / "bots"
LOCAL_INDEX_PATH = refs.CACHE_DIR / "local-robots-index.json"
ROUND_INITIALIZING = re.compile(r"^Round\s+(\d+)\s+initializing")
BATTLE_RUNNER_SOURCE = ROOT / "scripts" / "BattleRunner.java"
BATTLE_RUNNER_CLASSDIR = refs.CACHE_DIR / "battle-runner"


@dataclass(frozen=True)
class RobotCandidate:
    name: str
    version: str | None
    class_name: str
    jar: str
    source: Path
    origin: str
    catalogs: tuple[str, ...] = ()

    @property
    def selector(self) -> str:
        if self.version:
            return f"{self.class_name} {self.version}"
        return self.class_name

    @property
    def display_version(self) -> str:
        return self.version or ""

    @property
    def search_text(self) -> str:
        return " ".join(
            (
                self.name,
                self.version or "",
                self.class_name,
                self.selector,
                self.jar,
                self.origin,
                *self.catalogs,
            )
        )


@dataclass(frozen=True)
class ScoreRow:
    score: float
    survival: float
    bullet_damage: float
    bullet_bonus: float


@dataclass(frozen=True)
class DuelResult:
    enemy: RobotCandidate
    aps: float | None
    survival: float | None
    dealt_per_round: float | None
    taken_per_round: float | None
    bonus_per_round: float | None
    enemy_bonus_per_round: float | None


@dataclass(frozen=True)
class LocalJar:
    path: Path
    origin: str


@dataclass(frozen=True)
class LocalJarMetadata:
    mtime_ns: int
    size: int
    robots: list[dict[str, object]]


class UserError(ValueError):
    pass


def robot_candidates_from_metadata(
    jar_path: Path,
    origin: str,
    robots: Sequence[dict[str, object]],
    catalogs: Sequence[str] = (),
) -> list[RobotCandidate]:
    candidates: list[RobotCandidate] = []
    for robot in robots:
        raw_class = robot.get("class")
        raw_name = robot.get("name")
        if not isinstance(raw_class, str) or not isinstance(raw_name, str):
            continue
        raw_version = robot.get("version")
        version = raw_version if isinstance(raw_version, str) else None
        candidates.append(
            RobotCandidate(
                name=raw_name,
                version=version,
                class_name=raw_class,
                jar=jar_path.name,
                source=jar_path,
                origin=origin,
                catalogs=tuple(catalogs),
            )
        )
    return candidates


def is_ref_link(path: Path) -> bool:
    if not path.is_symlink():
        return False
    target = path.resolve(strict=False)
    try:
        if target.is_relative_to(refs.REFS_DIR.resolve()):
            return True
    except OSError:
        pass
    # The robocode/ engine dir is commonly symlinked across worktrees, so a
    # ref link there may resolve under a sibling worktree's .cache/refs. Fall
    # back to matching by the link's direct target name against the refs index,
    # which is worktree-independent.
    try:
        link_target = Path(os.readlink(path))
    except OSError:
        return False
    return link_target.name in refs.load_index_by_jar(required=False)


def project_build_jars() -> Iterable[Path]:
    if not BOTS_DIR.is_dir():
        return []

    jars: list[Path] = []
    for module_dir in sorted(path for path in BOTS_DIR.iterdir() if path.is_dir()):
        libs_dir = module_dir / "build" / "libs"
        if not libs_dir.is_dir():
            continue
        for jar in sorted(libs_dir.glob("*.jar")):
            stem = jar.stem
            module_name = module_dir.name
            if stem == module_name:
                jars.append(jar)
    return jars


def installed_local_jars() -> Iterable[Path]:
    if not ROBOTS_DIR.is_dir():
        return []
    return [
        jar
        for jar in sorted(ROBOTS_DIR.glob("*.jar"))
        if jar.is_file() and not is_ref_link(jar)
    ]


def local_jars() -> list[LocalJar]:
    jars: dict[Path, LocalJar] = {}
    for jar in project_build_jars():
        jars[jar.resolve()] = LocalJar(path=jar, origin="build")
    for jar in installed_local_jars():
        jars.setdefault(jar.resolve(), LocalJar(path=jar, origin="robots"))
    return list(jars.values())


def relative_to_root(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path.resolve())


def load_local_index() -> dict[str, LocalJarMetadata]:
    if not LOCAL_INDEX_PATH.is_file():
        return {}
    try:
        data = json.loads(LOCAL_INDEX_PATH.read_text())
    except (OSError, json.JSONDecodeError) as error:
        print(f"warning: cannot read {LOCAL_INDEX_PATH}: {error}", file=sys.stderr)
        return {}

    if not isinstance(data, dict) or data.get("schemaVersion") != 1:
        print(f"warning: ignoring incompatible {LOCAL_INDEX_PATH}", file=sys.stderr)
        return {}
    raw_jars = data.get("jars")
    if not isinstance(raw_jars, list):
        return {}

    indexed: dict[str, LocalJarMetadata] = {}
    for raw_jar in raw_jars:
        if not isinstance(raw_jar, dict):
            continue
        path = raw_jar.get("path")
        mtime_ns = raw_jar.get("mtimeNs")
        size = raw_jar.get("size")
        robots = raw_jar.get("robots")
        if (
            not isinstance(path, str)
            or not isinstance(mtime_ns, int)
            or not isinstance(size, int)
            or not isinstance(robots, list)
        ):
            continue
        indexed[path] = LocalJarMetadata(
            mtime_ns=mtime_ns,
            size=size,
            robots=[robot for robot in robots if isinstance(robot, dict)],
        )
    return indexed


def write_local_index(indexed: dict[str, LocalJarMetadata]) -> None:
    LOCAL_INDEX_PATH.parent.mkdir(parents=True, exist_ok=True)
    jars = []
    for path_key in sorted(indexed):
        metadata = indexed[path_key]
        jars.append(
            {
                "path": path_key,
                "mtimeNs": metadata.mtime_ns,
                "size": metadata.size,
                "robots": metadata.robots,
            }
        )
    LOCAL_INDEX_PATH.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "jars": jars,
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n"
    )


def local_candidates() -> list[RobotCandidate]:
    previous_index = load_local_index()
    next_index: dict[str, LocalJarMetadata] = {}
    candidates: list[RobotCandidate] = []
    changed = False

    for local_jar in local_jars():
        path = local_jar.path
        try:
            stat = path.stat()
        except OSError as error:
            print(f"Cannot stat {path}: {error}", file=sys.stderr)
            continue

        key = relative_to_root(path)
        cached = previous_index.get(key)
        if cached and cached.mtime_ns == stat.st_mtime_ns and cached.size == stat.st_size:
            metadata = cached
        else:
            try:
                robots = refs.parse_jar_robots(path)
            except (OSError, zipfile.BadZipFile, UnicodeDecodeError) as error:
                print(f"Cannot scan {path}: {error}", file=sys.stderr)
                changed = True
                continue
            metadata = LocalJarMetadata(
                mtime_ns=stat.st_mtime_ns,
                size=stat.st_size,
                robots=robots,
            )
            changed = True

        next_index[key] = metadata
        candidates.extend(
            robot_candidates_from_metadata(path, local_jar.origin, metadata.robots)
        )

    if set(previous_index) != set(next_index):
        changed = True
    if changed:
        write_local_index(next_index)
    return candidates


def loose_robot_candidates() -> list[RobotCandidate]:
    if not ROBOTS_DIR.is_dir():
        return []

    candidates: list[RobotCandidate] = []
    for properties_path in sorted(ROBOTS_DIR.rglob("*.properties")):
        try:
            properties = refs.load_java_properties(properties_path.read_bytes())
        except (OSError, UnicodeDecodeError) as error:
            print(f"Cannot scan {properties_path}: {error}", file=sys.stderr)
            continue

        class_name = properties.get("robot.classname", "").strip()
        if not class_name:
            continue

        class_file = ROBOTS_DIR.joinpath(*class_name.split(".")).with_suffix(".class")
        if not class_file.is_file():
            continue

        package_parts = class_name.split(".")[:-1]
        if not package_parts:
            continue
        source = ROBOTS_DIR / package_parts[0]
        robot_name = properties.get("robot.name", "").strip() or refs.robot_name_from_class(
            class_name
        )
        version = properties.get("robot.version", "").strip() or None
        candidates.append(
            RobotCandidate(
                name=robot_name,
                version=version,
                class_name=class_name,
                jar=relative_to_root(properties_path),
                source=source,
                origin="robots",
            )
        )
    return candidates


def ref_candidates() -> list[RobotCandidate]:
    indexed = refs.load_index_by_jar(required=False)
    candidates: list[RobotCandidate] = []
    for jar, robots in indexed.items():
        jar_path = refs.REFS_DIR / jar
        if not jar_path.is_file():
            continue
        # A catalog entry names one downloaded JAR, whose conventional filename
        # is <robot.class>_<version>.jar. Some reference JARs also bundle helper
        # or sibling robots. Keep those robots directly selectable, but attach
        # the JAR's catalog membership only to its primary class so a 100-entry
        # catalog still expands to 100 opponents.
        expected_class = Path(jar).stem.rsplit("_", 1)[0]
        primary_class = next(
            (robot.class_name for robot in robots if robot.class_name == expected_class),
            robots[0].class_name if robots else None,
        )
        for robot in robots:
            candidates.append(
                RobotCandidate(
                    name=robot.name,
                    version=robot.version,
                    class_name=robot.class_name,
                    jar=jar,
                    source=jar_path,
                    origin="refs",
                    catalogs=robot.catalogs if robot.class_name == primary_class else (),
                )
            )
    return candidates


def load_candidates() -> list[RobotCandidate]:
    candidates = local_candidates()
    candidates.extend(loose_robot_candidates())
    candidates.extend(ref_candidates())

    deduped: dict[str, RobotCandidate] = {}
    for candidate in candidates:
        deduped.setdefault(candidate.selector, candidate)
    return list(deduped.values())


def matches(value: str, query: str) -> bool:
    return query.casefold() in value.casefold()


def exact_matches(candidates: Sequence[RobotCandidate], query: str) -> list[RobotCandidate]:
    needle = query.casefold()
    return [
        candidate
        for candidate in candidates
        if needle
        in {
            candidate.name.casefold(),
            candidate.class_name.casefold(),
            candidate.selector.casefold(),
            candidate.jar.casefold(),
        }
    ]


def fuzzy_matches(candidates: Sequence[RobotCandidate], query: str) -> list[RobotCandidate]:
    return [candidate for candidate in candidates if matches(candidate.search_text, query)]


def describe_candidate(candidate: RobotCandidate) -> str:
    suffix = f" [{','.join(candidate.catalogs)}]" if candidate.catalogs else ""
    return (
        f"{candidate.name} {candidate.display_version} "
        f"({candidate.class_name}, {candidate.jar}, {candidate.origin}){suffix}"
    ).strip()


def progress_name(candidate: RobotCandidate) -> str:
    if candidate.display_version:
        return f"{candidate.name} {candidate.display_version}"
    return candidate.name


def resolve_one(
    candidates: Sequence[RobotCandidate],
    query: str,
    role: str,
) -> RobotCandidate:
    found = exact_matches(candidates, query) or fuzzy_matches(candidates, query)
    if not found:
        raise UserError(f"no {role} matches '{query}'")
    if len(found) > 1:
        choices = "\n".join(f"  - {describe_candidate(candidate)}" for candidate in found)
        raise UserError(f"ambiguous {role} '{query}':\n{choices}")
    return found[0]


def configured_catalog_jars() -> dict[str, list[str]]:
    """Return each refs.jsonc catalog's JARs in its declared order."""
    try:
        data = json.loads(refs.strip_jsonc_comments(refs.CATALOG_PATH.read_text()))
    except (OSError, json.JSONDecodeError, refs.UserError) as error:
        raise UserError(f"cannot read {refs.CATALOG_PATH}: {error}") from error
    if not isinstance(data, dict):
        raise UserError(f"{refs.CATALOG_PATH}: root must be an object")
    default_prefix = refs.require_string(
        data.get("defaultUrlPrefix"), "defaultUrlPrefix"
    )
    raw_catalog = data.get("catalog")
    if not isinstance(raw_catalog, dict):
        raise UserError(f"{refs.CATALOG_PATH}: catalog must be an object")

    ordered: dict[str, list[str]] = {}
    for catalog, raw_keys in raw_catalog.items():
        if not isinstance(catalog, str) or not isinstance(raw_keys, list):
            raise UserError(f"{refs.CATALOG_PATH}: invalid catalog entry")
        jars: list[str] = []
        for position, raw_key in enumerate(raw_keys):
            key = refs.require_string(raw_key, f"catalog.{catalog}[{position}]")
            jars.append(
                refs.file_name_from_url(refs.expand_url(default_prefix, key))
            )
        ordered[catalog] = jars
    return ordered


def match_catalogs(
    candidates: Sequence[RobotCandidate],
    catalog_queries: Sequence[str],
) -> list[RobotCandidate]:
    if not catalog_queries:
        return []

    configured_order = configured_catalog_jars()
    catalog_names = list(
        dict.fromkeys(
            [*configured_order]
            + [catalog for candidate in candidates for catalog in candidate.catalogs]
        )
    )
    selected_catalogs: list[str] = []
    for query in catalog_queries:
        found = [catalog for catalog in catalog_names if matches(catalog, query)]
        if not found:
            raise UserError(f"no catalog matches '{query}'")
        for catalog in found:
            if catalog not in selected_catalogs:
                selected_catalogs.append(catalog)

    candidates_by_jar = {
        candidate.jar: candidate for candidate in candidates if candidate.catalogs
    }
    matched: dict[str, RobotCandidate] = {}
    for catalog in selected_catalogs:
        for jar in configured_order.get(catalog, ()):  # Preserve refs.jsonc order.
            candidate = candidates_by_jar.get(jar)
            if candidate is not None and catalog in candidate.catalogs:
                matched.setdefault(candidate.selector, candidate)
        # Keep working with an older index or a catalog that is absent from the
        # current config, while appending those fallback members deterministically.
        for candidate in candidates:
            if catalog in candidate.catalogs:
                matched.setdefault(candidate.selector, candidate)
    return list(matched.values())


def selected_enemies(
    candidates: Sequence[RobotCandidate],
    enemy_queries: Sequence[str],
    catalog_queries: Sequence[str],
) -> list[RobotCandidate]:
    enemies: dict[str, RobotCandidate] = {}
    for query in enemy_queries:
        enemy = resolve_one(candidates, query, "enemy")
        enemies[enemy.selector] = enemy
    for enemy in match_catalogs(candidates, catalog_queries):
        enemies[enemy.selector] = enemy
    return list(enemies.values())


def link_selected_sources(robot_path: Path, robots: Sequence[RobotCandidate]) -> None:
    robot_path.mkdir(parents=True, exist_ok=True)
    linked: dict[Path, Path] = {}
    used_names: set[str] = set()
    for robot in robots:
        source = robot.source.resolve()
        if source in linked:
            continue
        name = source.name
        if name in used_names:
            name = f"{len(used_names)}-{name}"
        used_names.add(name)
        destination = robot_path / name
        os.symlink(source, destination)
        linked[source] = destination


def write_battle_file(path: Path, rounds: int, robot: str, enemy: str) -> None:
    path.write_text(
        "\n".join(
            (
                "robocode.battleField.width=800",
                "robocode.battleField.height=600",
                f"robocode.battle.numRounds={rounds}",
                "robocode.battle.gunCoolingRate=0.1",
                "robocode.battle.rules.inactivityTime=450",
                f"robocode.battle.selectedRobots={robot},{enemy}",
                "",
            )
        )
    )


def run_robocode(
    robot_path: Path,
    battle: Path,
    results: Path,
    on_round: Callable[[int], None] | None = None,
    seed: int | None = None,
) -> subprocess.CompletedProcess[str]:
    command = [
        "java",
        "-cp",
        "libs/*",
        "-Xmx512M",
        f"-DROBOTPATH={robot_path}",
        "-Djava.awt.headless=true",
        "-Djava.security.manager=allow",
        "-XX:+IgnoreUnrecognizedVMOptions",
        "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "robocode.Robocode",
        "-battle",
        str(battle),
        "-nodisplay",
        "-nosound",
        "-results",
        str(results),
    ]
    if seed is not None:
        # The engine's BattleManager reads RANDOMSEED at battle start and reseeds
        # Robocode's deterministic RNG, so initial positions, headings, and the
        # fair-play robot/bullet ordering are reproducible run-to-run.
        command.insert(command.index("robocode.Robocode"), f"-DRANDOMSEED={seed}")
    sys.stdout.flush()
    sys.stderr.flush()
    process: subprocess.Popen[str] | None = None
    stdout: list[str] = []
    stderr: list[str] = []

    def read_stdout() -> None:
        if process is None or process.stdout is None:
            return
        for line in process.stdout:
            stdout.append(line)
            match = ROUND_INITIALIZING.match(line.strip())
            if match is not None and on_round is not None:
                on_round(int(match.group(1)))

    def read_stderr() -> None:
        if process is None or process.stderr is None:
            return
        for line in process.stderr:
            stderr.append(line)

    process = subprocess.Popen(
        command,
        cwd=ROBOCODE_DIR,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=1,
    )
    threads = [
        threading.Thread(target=read_stdout, daemon=True),
        threading.Thread(target=read_stderr, daemon=True),
    ]
    for thread in threads:
        thread.start()

    returncode = process.wait()
    for thread in threads:
        thread.join()
    return subprocess.CompletedProcess(
        args=command,
        returncode=returncode,
        stdout="".join(stdout),
        stderr="".join(stderr),
    )


def row_for(rows: dict[str, ScoreRow], candidate: RobotCandidate) -> ScoreRow | None:
    """Look up a result row by selector, then class name. The in-process runner may
    report team-leader names without a version suffix, so fall back to the class."""
    return rows.get(candidate.selector) or rows.get(candidate.class_name)


def ensure_battle_runner() -> Path:
    """Compile scripts/BattleRunner.java into the cache dir when stale or missing."""
    classfile = BATTLE_RUNNER_CLASSDIR / "BattleRunner.class"
    source_mtime = BATTLE_RUNNER_SOURCE.stat().st_mtime
    if classfile.is_file() and classfile.stat().st_mtime >= source_mtime:
        return BATTLE_RUNNER_CLASSDIR
    BATTLE_RUNNER_CLASSDIR.mkdir(parents=True, exist_ok=True)
    compiled = subprocess.run(
        [
            "javac",
            "-cp",
            str(ROBOCODE_DIR / "libs" / "*"),
            "-d",
            str(BATTLE_RUNNER_CLASSDIR),
            str(BATTLE_RUNNER_SOURCE),
        ],
        cwd=ROBOCODE_DIR,
        capture_output=True,
        text=True,
    )
    if compiled.returncode != 0:
        raise UserError(f"failed to compile BattleRunner:\n{compiled.stderr}")
    return BATTLE_RUNNER_CLASSDIR


def run_robocode_engine(
    robot_path: Path,
    robots: str,
    rounds: int,
    results: Path,
    watch: str,
    seed: int | None = None,
) -> subprocess.CompletedProcess[str]:
    """Run a duel in-process via BattleRunner, streaming the watched robot's console
    output to stdout and round-init markers to stderr. Results are written to
    [results] in the CLI TSV format so parse_results works unchanged."""
    classdir = ensure_battle_runner()
    command = [
        "java",
        "-Xmx512M",
        f"-DROBOTPATH={robot_path}",
        f"-Drobocode.home={ROBOCODE_DIR}",
        "-DTESTING=true",
        "-DEXPERIMENTAL=true",
        "-DNOSECURITY=false",
        "-Drobocode.security.adapter=true",
        "-Drobocode.options.battle.desiredTPS=10000",
        "-Djava.awt.headless=true",
        "-Djava.security.manager=allow",
        "-XX:+IgnoreUnrecognizedVMOptions",
        "--add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "-cp",
        f"{classdir}:libs/*",
        "BattleRunner",
        "--robots",
        robots,
        "--rounds",
        str(rounds),
        "--results",
        str(results),
        "--watch",
        watch,
    ]
    if seed is not None:
        # BattleManager reseeds from -DRANDOMSEED at battle start; BattleRunner
        # also calls RandomFactory.resetDeterministic explicitly for the control
        # API (mirroring Robocode's own RobotTestBed).
        command.insert(command.index("-cp"), f"-DRANDOMSEED={seed}")
        command.extend(["--seed", str(seed)])
    sys.stdout.flush()
    sys.stderr.flush()
    process = subprocess.Popen(
        command,
        cwd=ROBOCODE_DIR,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=1,
    )
    stdout: list[str] = []
    stderr: list[str] = []

    def read_stdout() -> None:
        assert process.stdout is not None
        for line in process.stdout:
            stdout.append(line)
            sys.stdout.write(line)
            sys.stdout.flush()

    def read_stderr() -> None:
        assert process.stderr is not None
        for line in process.stderr:
            stderr.append(line)
            sys.stderr.write(line)
            sys.stderr.flush()

    threads = [
        threading.Thread(target=read_stdout, daemon=True),
        threading.Thread(target=read_stderr, daemon=True),
    ]
    for thread in threads:
        thread.start()
    returncode = process.wait()
    for thread in threads:
        thread.join()
    return subprocess.CompletedProcess(
        args=command,
        returncode=returncode,
        stdout="".join(stdout),
        stderr="".join(stderr),
    )


def parse_score(value: str) -> float:
    return float(value.split(" ", 1)[0])


def parse_results(path: Path) -> dict[str, ScoreRow]:
    rows: dict[str, ScoreRow] = {}
    if not path.is_file():
        return rows
    for line in path.read_text(errors="replace").splitlines():
        columns = line.split("\t")
        if len(columns) < 6 or ":" not in columns[0]:
            continue
        robot_name = columns[0].split(":", 1)[1].strip()
        try:
            rows[robot_name] = ScoreRow(
                score=parse_score(columns[1]),
                survival=parse_score(columns[2]),
                bullet_damage=parse_score(columns[4]),
                bullet_bonus=parse_score(columns[5]),
            )
        except ValueError:
            continue
    return rows


def compute_result(
    enemy: RobotCandidate,
    rounds: int,
    robot_row: ScoreRow | None,
    enemy_row: ScoreRow | None,
) -> DuelResult:
    if robot_row is None or enemy_row is None:
        return DuelResult(enemy, None, None, None, None, None, None)

    total_score = robot_row.score + enemy_row.score
    total_survival = robot_row.survival + enemy_row.survival
    return DuelResult(
        enemy=enemy,
        aps=(100 * robot_row.score / total_score) if total_score > 0 else 0.0,
        survival=(100 * robot_row.survival / total_survival) if total_survival > 0 else 0.0,
        dealt_per_round=robot_row.bullet_damage / rounds if rounds > 0 else 0.0,
        taken_per_round=enemy_row.bullet_damage / rounds if rounds > 0 else 0.0,
        bonus_per_round=robot_row.bullet_bonus / rounds if rounds > 0 else 0.0,
        enemy_bonus_per_round=enemy_row.bullet_bonus / rounds if rounds > 0 else 0.0,
    )


def format_float(value: float | None, digits: int = 2) -> str:
    if value is None:
        return "n/a"
    return f"{value:.{digits}f}"


class ProgressLine:
    def __init__(self, interactive: bool) -> None:
        self.interactive = interactive
        self.active = False
        self.lock = threading.Lock()
        self.width = 0

    def start(self, message: str) -> None:
        if self.interactive:
            self._write(message, newline=False)
            self.active = True
        else:
            print(message, flush=True)

    def update(self, message: str) -> None:
        if self.interactive:
            self._write(message, newline=False)

    def finish(self, message: str) -> None:
        if self.interactive:
            self._write(message, newline=True)
            self.active = False
        else:
            print(message, flush=True)

    def break_line(self) -> None:
        with self.lock:
            if self.interactive and self.active:
                print()
                self.active = False
                self.width = 0

    def _write(self, message: str, newline: bool) -> None:
        with self.lock:
            padding = " " * max(0, self.width - len(message))
            sys.stdout.write(f"\r{message}{padding}")
            if newline:
                sys.stdout.write("\n")
                self.width = 0
            else:
                self.width = len(message)
            sys.stdout.flush()


def print_summary(robot: RobotCandidate, rounds: int, results: Sequence[DuelResult]) -> None:
    print(f"Duel: {describe_candidate(robot)}")
    print(f"Rounds: {rounds}")
    print()

    rows = [
        (
            result.enemy.name,
            result.enemy.display_version,
            format_float(result.aps),
            format_float(result.survival),
            format_float(result.dealt_per_round, 1),
            format_float(result.taken_per_round, 1),
            format_float(result.bonus_per_round, 1),
            format_float(result.enemy_bonus_per_round, 1),
        )
        for result in results
    ]
    headers = (
        "ENEMY",
        "VERSION",
        "APS%",
        "SURV%",
        "DEALT/r",
        "TAKEN/r",
        "BONUS/r",
        "OPP-BONUS/r",
    )
    widths = [
        max(len(headers[column]), *(len(row[column]) for row in rows))
        for column in range(len(headers))
    ]
    print("  ".join(value.ljust(width) for value, width in zip(headers, widths)))
    for row in rows:
        print("  ".join(value.ljust(width) for value, width in zip(row, widths)))

    scored = [
        result
        for result in results
        if result.aps is not None
        and result.survival is not None
        and result.dealt_per_round is not None
        and result.taken_per_round is not None
        and result.bonus_per_round is not None
        and result.enemy_bonus_per_round is not None
    ]
    if scored:
        aps_values = [result.aps for result in scored if result.aps is not None]
        survival_values = [
            result.survival for result in scored if result.survival is not None
        ]
        dealt_values = [
            result.dealt_per_round
            for result in scored
            if result.dealt_per_round is not None
        ]
        taken_values = [
            result.taken_per_round
            for result in scored
            if result.taken_per_round is not None
        ]
        bonus_values = [
            result.bonus_per_round
            for result in scored
            if result.bonus_per_round is not None
        ]
        enemy_bonus_values = [
            result.enemy_bonus_per_round
            for result in scored
            if result.enemy_bonus_per_round is not None
        ]
        pwin = sum(1.0 if aps > 50 else 0.5 if aps == 50 else 0.0 for aps in aps_values)
        mean_aps = sum(aps_values) / len(aps_values)
        mean_survival = sum(survival_values) / len(survival_values)
        mean_dealt = sum(dealt_values) / len(dealt_values)
        mean_taken = sum(taken_values) / len(taken_values)
        mean_bonus = sum(bonus_values) / len(bonus_values)
        mean_enemy_bonus = sum(enemy_bonus_values) / len(enemy_bonus_values)
        print()
        print(
            f"APS: {mean_aps:.2f}%  |  PWIN: {100 * pwin / len(scored):.2f}%  |  "
            f"Survival: {mean_survival:.2f}%  |  pairings: {len(scored)} "
            f"({rounds} rounds each)  |  dealt/r: {mean_dealt:.1f}  |  "
            f"taken/r: {mean_taken:.1f}  |  bonus/r: {mean_bonus:.1f}  |  "
            f"opp-bonus/r: {mean_enemy_bonus:.1f}"
        )
    sys.stdout.flush()


def write_output(path: Path, robot: RobotCandidate, results: Sequence[DuelResult]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "robot\tenemy\taps\tsurvival\tdealt_per_round\ttaken_per_round\t"
        "bonus_per_round\tenemy_bonus_per_round"
    ]
    for result in results:
        lines.append(
            "\t".join(
                (
                    robot.selector,
                    result.enemy.selector,
                    "" if result.aps is None else f"{result.aps:.4f}",
                    "" if result.survival is None else f"{result.survival:.4f}",
                    ""
                    if result.dealt_per_round is None
                    else f"{result.dealt_per_round:.4f}",
                    ""
                    if result.taken_per_round is None
                    else f"{result.taken_per_round:.4f}",
                    ""
                    if result.bonus_per_round is None
                    else f"{result.bonus_per_round:.4f}",
                    ""
                    if result.enemy_bonus_per_round is None
                    else f"{result.enemy_bonus_per_round:.4f}",
                )
            )
        )
    path.write_text("\n".join(lines) + "\n")


def positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be an integer") from error
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="duel",
        description="Run headless 1v1 Robocode duels.",
    )
    parser.add_argument(
        "-r",
        "--robot",
        required=True,
        metavar="QUERY",
        help="robot to evaluate; fuzzy matched against available robots",
    )
    parser.add_argument(
        "-e",
        "--enemy",
        action="append",
        default=[],
        metavar="QUERY",
        help="enemy robot; can be repeated; matches built, downloaded, or installed robots",
    )
    parser.add_argument(
        "-c",
        "--catalog",
        action="append",
        default=[],
        metavar="QUERY",
        help="select enemies from refs catalog; can be repeated",
    )
    parser.add_argument(
        "-n",
        "--rounds",
        type=positive_int,
        default=35,
        help="rounds per duel (default: 35)",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        help="write TSV summary to this path",
    )
    parser.add_argument(
        "--allow-self",
        action="store_true",
        help="allow a robot to duel itself",
    )
    parser.add_argument(
        "--show-output",
        action="store_true",
        help="stream the -r robot's console output (out.println) during the duel; "
        "runs the battle in-process via RobocodeEngine",
    )
    parser.add_argument(
        "--seed",
        type=int,
        metavar="N",
        default=None,
        help="seed Robocode's deterministic RNG (RANDOMSEED) so initial positions, "
        "headings, and the engine's fair-play ordering are reproducible run-to-run. "
        "A duel is fully reproducible only when both robots pull randomness through "
        "Math.random() (Robocode's controlled RNG); a robot that constructs its own "
        "java.util.Random() stays stochastic (e.g. the frozen Ronin).",
    )
    return parser


def command_duel(arguments: argparse.Namespace) -> int:
    if not (ROBOCODE_DIR / "libs" / "robocode.jar").is_file():
        raise UserError("Robocode is not installed; run 'just robocode install' first")
    if not arguments.enemy and not arguments.catalog:
        raise UserError("provide at least one --enemy or --catalog")

    candidates = load_candidates()
    if not candidates:
        raise UserError("no robots available; run 'just build' or 'just refs download'")

    robot = resolve_one(candidates, arguments.robot, "robot")
    enemies = selected_enemies(candidates, arguments.enemy, arguments.catalog)
    if not enemies:
        raise UserError("no enemies selected")

    runnable_enemies: list[RobotCandidate] = []
    for enemy in enemies:
        if enemy.selector == robot.selector and not arguments.allow_self:
            print(
                f"warning: skipping self duel against {enemy.selector}",
                file=sys.stderr,
                flush=True,
            )
            continue
        runnable_enemies.append(enemy)
    if not runnable_enemies:
        raise UserError("no enemies left after skipping self duels")

    all_robots = [robot, *runnable_enemies]
    results: list[DuelResult] = []
    with tempfile.TemporaryDirectory(prefix="mechgarden-duel-") as tmp:
        temp_dir = Path(tmp)
        robot_path = temp_dir / "robots"
        link_selected_sources(robot_path, all_robots)
        total_pairings = len(runnable_enemies)
        for pairing_index, enemy in enumerate(runnable_enemies, start=1):
            raw_results = temp_dir / f"{enemy.class_name.replace('.', '_')}.txt"
            progress_prefix = f"[{pairing_index}/{total_pairings}] {progress_name(enemy)}"

            if arguments.show_output:
                print(
                    f"=== {progress_prefix}: {robot.selector} vs {enemy.selector} "
                    f"({arguments.rounds} rounds) ===",
                    file=sys.stderr,
                    flush=True,
                )
                completed = run_robocode_engine(
                    robot_path,
                    f"{robot.class_name},{enemy.class_name}",
                    arguments.rounds,
                    raw_results,
                    robot.class_name,
                    arguments.seed,
                )
                if completed.returncode != 0:
                    print(
                        f"warning: BattleRunner exited {completed.returncode} "
                        f"for {enemy.selector}",
                        file=sys.stderr,
                        flush=True,
                    )
            else:
                battle = temp_dir / f"{enemy.class_name.replace('.', '_')}.battle"
                write_battle_file(
                    battle, arguments.rounds, robot.selector, enemy.selector
                )
                progress = ProgressLine(interactive=sys.stdout.isatty())
                progress.start(
                    f"{progress_prefix}: starting ({arguments.rounds} rounds)"
                )
                round_step = max(1, arguments.rounds // 10)
                reported_rounds: set[int] = set()

                def report_round(round_number: int) -> None:
                    if round_number in reported_rounds:
                        return
                    if (
                        round_number == 1
                        or round_number == arguments.rounds
                        or round_number % round_step == 0
                    ):
                        reported_rounds.add(round_number)
                        progress.update(
                            f"{progress_prefix}: round {round_number}/{arguments.rounds}",
                        )

                completed = run_robocode(
                    robot_path,
                    battle,
                    raw_results,
                    on_round=report_round,
                    seed=arguments.seed,
                )
                if completed.returncode != 0:
                    progress.break_line()
                    print(
                        f"warning: Robocode exited with {completed.returncode} "
                        f"for {enemy.selector}",
                        file=sys.stderr,
                        flush=True,
                    )
                    if completed.stderr.strip():
                        print(
                            completed.stderr.strip().splitlines()[-1],
                            file=sys.stderr,
                            flush=True,
                        )

            rows = parse_results(raw_results)
            result = compute_result(
                enemy,
                arguments.rounds,
                row_for(rows, robot),
                row_for(rows, enemy),
            )
            results.append(result)
            if arguments.show_output:
                print(
                    f"--- {progress_prefix}: finished ---\n",
                    file=sys.stderr,
                    flush=True,
                )
            else:
                progress.finish(
                    f"{progress_prefix}: finished, APS {format_float(result.aps)}%, "
                    f"survival {format_float(result.survival)}%",
                )

    print_summary(robot, arguments.rounds, results)
    if arguments.output:
        write_output(arguments.output, robot, results)
        print()
        print(f"Wrote {arguments.output}", flush=True)
    return 1 if any(result.aps is None for result in results) else 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    arguments = parser.parse_args(argv)
    try:
        return command_duel(arguments)
    except UserError as error:
        parser.error(str(error))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
