#!/usr/bin/env python3
"""Manage reference robot downloads and local metadata index."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


ROOT = Path(__file__).resolve().parent.parent
CACHE_DIR = ROOT / ".cache"
CATALOG_PATH = ROOT / "refs.jsonc"
REFS_DIR = CACHE_DIR / "refs"
INDEX_PATH = REFS_DIR / "index.json"
ROBOTS_DIR = ROOT / "robocode" / "robots"
LEGACY_REFS_DIRS = (ROOT / "refs", ROOT / "downloads" / "refs")


@dataclass(frozen=True)
class CatalogEntry:
    key: str
    url: str
    file_name: str
    catalogs: tuple[str, ...]


@dataclass(frozen=True)
class IndexedRobot:
    name: str
    version: str | None
    class_name: str
    jar: str
    catalogs: tuple[str, ...]


@dataclass(frozen=True)
class ListRow:
    installed: bool
    jar: str
    catalogs: tuple[str, ...]
    name: str
    version: str
    class_name: str
    url: str


class UserError(ValueError):
    pass


def strip_jsonc_comments(text: str) -> str:
    """Remove line and block comments while preserving JSON string contents."""
    output: list[str] = []
    index = 0
    in_string = False
    escaped = False
    line_comment = False
    block_comment = False

    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""

        if line_comment:
            if char in "\r\n":
                line_comment = False
                output.append(char)
            else:
                output.append(" ")
        elif block_comment:
            if char == "*" and following == "/":
                output.extend((" ", " "))
                block_comment = False
                index += 1
            elif char in "\r\n":
                output.append(char)
            else:
                output.append(" ")
        elif in_string:
            output.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
        elif char == '"':
            in_string = True
            output.append(char)
        elif char == "/" and following == "/":
            output.extend((" ", " "))
            line_comment = True
            index += 1
        elif char == "/" and following == "*":
            output.extend((" ", " "))
            block_comment = True
            index += 1
        else:
            output.append(char)

        index += 1

    if block_comment:
        raise UserError("unterminated block comment in refs.jsonc")
    return "".join(output)


def is_http_url(value: str) -> bool:
    return value.lower().startswith(("http://", "https://"))


def expand_url(default_prefix: str, key: str) -> str:
    return key if is_http_url(key) else f"{default_prefix}{key}"


def file_name_from_url(url: str) -> str:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        raise UserError(f"URL must use http or https: {url}")

    file_name = Path(urllib.parse.unquote(parsed.path)).name
    if not file_name.endswith(".jar"):
        raise UserError(f"URL does not end with a jar filename: {url}")
    return file_name


def require_string(value: object, path: str) -> str:
    if not isinstance(value, str) or not value:
        raise UserError(f"{path} must be a non-empty string")
    return value


def load_catalog() -> list[CatalogEntry]:
    try:
        data = json.loads(strip_jsonc_comments(CATALOG_PATH.read_text()))
    except (OSError, json.JSONDecodeError, UserError) as error:
        raise UserError(f"cannot read {CATALOG_PATH}: {error}") from error

    if not isinstance(data, dict):
        raise UserError(f"{CATALOG_PATH}: root must be an object")
    if data.get("schemaVersion") != 7:
        raise UserError(f"{CATALOG_PATH}: schemaVersion must be 7")

    default_prefix = require_string(
        data.get("defaultUrlPrefix"), "defaultUrlPrefix"
    )
    if not default_prefix.endswith("/"):
        raise UserError("defaultUrlPrefix must end with '/'")

    raw_catalog = data.get("catalog")
    if not isinstance(raw_catalog, dict) or not raw_catalog:
        raise UserError("catalog must be a non-empty object")

    entries_by_file_name: dict[str, CatalogEntry] = {}
    catalog_order: dict[str, list[str]] = {}
    for catalog_name, raw_keys in raw_catalog.items():
        if not isinstance(catalog_name, str) or not catalog_name:
            raise UserError("catalog names must be non-empty strings")
        if not isinstance(raw_keys, list) or not raw_keys:
            raise UserError(f"catalog.{catalog_name} must be a non-empty array")

        for position, raw_key in enumerate(raw_keys):
            key = require_string(raw_key, f"catalog.{catalog_name}[{position}]")
            url = expand_url(default_prefix, key)
            file_name = file_name_from_url(url)

            existing = entries_by_file_name.get(file_name)
            if existing is None:
                entries_by_file_name[file_name] = CatalogEntry(
                    key=key,
                    url=url,
                    file_name=file_name,
                    catalogs=(catalog_name,),
                )
                catalog_order[file_name] = [catalog_name]
            else:
                if existing.url != url:
                    raise UserError(
                        f"duplicate local jar filename maps to multiple URLs: {file_name}"
                    )
                if catalog_name not in catalog_order[file_name]:
                    catalog_order[file_name].append(catalog_name)
                    entries_by_file_name[file_name] = CatalogEntry(
                        key=existing.key,
                        url=existing.url,
                        file_name=existing.file_name,
                        catalogs=tuple(catalog_order[file_name]),
                    )

    return list(entries_by_file_name.values())


def matches_fuzzy(value: str, query: str) -> bool:
    return query.casefold() in value.casefold()


def select_entries(
    entries: Sequence[CatalogEntry],
    jar_queries: Sequence[str],
    catalog_queries: Sequence[str],
) -> list[CatalogEntry]:
    if not jar_queries and not catalog_queries:
        return list(entries)

    selected: dict[str, CatalogEntry] = {}
    for query in jar_queries:
        matches = [
            entry
            for entry in entries
            if matches_fuzzy(entry.file_name, query)
            or matches_fuzzy(entry.key, query)
            or matches_fuzzy(entry.url, query)
        ]
        if not matches:
            raise UserError(f"no jar matches '{query}'")
        selected.update((entry.file_name, entry) for entry in matches)

    catalog_names = list(dict.fromkeys(catalog for entry in entries for catalog in entry.catalogs))
    for query in catalog_queries:
        matched_catalogs = [
            catalog for catalog in catalog_names if matches_fuzzy(catalog, query)
        ]
        if not matched_catalogs:
            raise UserError(f"no catalog matches '{query}'")
        for entry in entries:
            if any(catalog in matched_catalogs for catalog in entry.catalogs):
                selected[entry.file_name] = entry

    return list(selected.values())


def download_jar(entry: CatalogEntry, destination: Path) -> None:
    REFS_DIR.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(
        entry.url,
        headers={"User-Agent": "MechGarden refs.py"},
    )
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix=f".{entry.file_name}.",
            suffix=".tmp",
            dir=REFS_DIR,
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
            with urllib.request.urlopen(request, timeout=60) as response:
                shutil.copyfileobj(response, temporary_file)

        with zipfile.ZipFile(temporary_path):
            pass
        os.replace(temporary_path, destination)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def count_trailing_backslashes(value: str) -> int:
    count = 0
    for char in reversed(value):
        if char != "\\":
            break
        count += 1
    return count


def logical_property_lines(text: str) -> Iterable[str]:
    pending = ""
    continuing = False
    for physical in text.splitlines():
        line = physical.lstrip(" \t\f") if continuing else physical
        if pending:
            pending += line
        else:
            pending = line

        if count_trailing_backslashes(pending) % 2 == 1:
            pending = pending[:-1]
            continuing = True
            continue

        yield pending
        pending = ""
        continuing = False

    if pending:
        yield pending


def find_property_separator(line: str) -> int | None:
    escaped = False
    for index, char in enumerate(line):
        if escaped:
            escaped = False
            continue
        if char == "\\":
            escaped = True
            continue
        if char in "=:" or char.isspace():
            return index
    return None


def split_property_line(line: str) -> tuple[str, str]:
    separator = find_property_separator(line)
    if separator is None:
        return line, ""

    key = line[:separator]
    index = separator
    if line[index].isspace():
        while index < len(line) and line[index].isspace():
            index += 1
        if index < len(line) and line[index] in "=:":
            index += 1
    else:
        index += 1
    while index < len(line) and line[index].isspace():
        index += 1
    return key, line[index:]


def unescape_property(value: str) -> str:
    output: list[str] = []
    index = 0
    while index < len(value):
        char = value[index]
        if char != "\\":
            output.append(char)
            index += 1
            continue

        index += 1
        if index >= len(value):
            output.append("\\")
            break

        escaped = value[index]
        index += 1
        if escaped == "t":
            output.append("\t")
        elif escaped == "n":
            output.append("\n")
        elif escaped == "r":
            output.append("\r")
        elif escaped == "f":
            output.append("\f")
        elif escaped == "u" and index + 4 <= len(value):
            hex_digits = value[index : index + 4]
            try:
                output.append(chr(int(hex_digits, 16)))
                index += 4
            except ValueError:
                output.append("u")
                output.append(hex_digits)
                index += 4
        else:
            output.append(escaped)
    return "".join(output)


def load_java_properties(data: bytes) -> dict[str, str]:
    text = data.decode("iso-8859-1")
    properties: dict[str, str] = {}
    for line in logical_property_lines(text):
        stripped = line.lstrip(" \t\f")
        if not stripped or stripped[0] in "#!":
            continue
        raw_key, raw_value = split_property_line(stripped)
        properties[unescape_property(raw_key)] = unescape_property(raw_value)
    return properties


def robot_name_from_class(class_name: str) -> str:
    return class_name.rsplit(".", 1)[-1]


def parse_jar_robots(jar_path: Path) -> list[dict[str, object]]:
    robots: list[dict[str, object]] = []
    with zipfile.ZipFile(jar_path) as jar:
        for name in sorted(jar.namelist()):
            if not name.endswith(".properties") or name.endswith("/"):
                continue
            properties = load_java_properties(jar.read(name))
            class_name = properties.get("robot.classname", "").strip()
            if not class_name:
                continue
            robot_name = properties.get("robot.name", "").strip() or robot_name_from_class(
                class_name
            )
            version = properties.get("robot.version", "").strip() or None
            robots.append(
                {
                    "class": class_name,
                    "name": robot_name,
                    "version": version,
                    "properties": name,
                }
            )
    return robots


def write_index(entries: Sequence[CatalogEntry]) -> None:
    REFS_DIR.mkdir(parents=True, exist_ok=True)
    indexed_jars: list[dict[str, object]] = []
    for entry in entries:
        jar_path = REFS_DIR / entry.file_name
        if not jar_path.is_file():
            continue
        try:
            robots = parse_jar_robots(jar_path)
        except (OSError, zipfile.BadZipFile, UnicodeDecodeError) as error:
            print(f"Cannot index {entry.file_name}: {error}", file=sys.stderr)
            continue

        indexed_jars.append(
            {
                "jar": entry.file_name,
                "url": entry.url,
                "catalogs": list(entry.catalogs),
                "robots": robots,
            }
        )

    INDEX_PATH.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "source": "refs.jsonc",
                "jars": indexed_jars,
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n"
    )
    suffix = "" if len(indexed_jars) == 1 else "s"
    print(f"Indexed {len(indexed_jars)} downloaded jar{suffix} ({INDEX_PATH})")


def load_index_by_jar(required: bool = True) -> dict[str, list[IndexedRobot]]:
    if not INDEX_PATH.is_file():
        if required:
            raise UserError(
                f"{INDEX_PATH} does not exist; run 'just refs index' first"
            )
        return {}

    try:
        data = json.loads(INDEX_PATH.read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise UserError(f"cannot read {INDEX_PATH}: {error}") from error

    if not isinstance(data, dict) or data.get("schemaVersion") != 1:
        raise UserError(f"{INDEX_PATH}: schemaVersion must be 1")
    jars = data.get("jars")
    if not isinstance(jars, list):
        raise UserError(f"{INDEX_PATH}: jars must be an array")

    robots_by_jar: dict[str, list[IndexedRobot]] = {}
    for jar_position, raw_jar in enumerate(jars):
        if not isinstance(raw_jar, dict):
            raise UserError(f"jars[{jar_position}] must be an object")
        jar = require_string(raw_jar.get("jar"), f"jars[{jar_position}].jar")
        raw_catalogs = raw_jar.get("catalogs")
        if not isinstance(raw_catalogs, list) or any(
            not isinstance(catalog, str) or not catalog for catalog in raw_catalogs
        ):
            raise UserError(f"jars[{jar_position}].catalogs must be a string array")

        raw_robots = raw_jar.get("robots")
        if not isinstance(raw_robots, list):
            raise UserError(f"jars[{jar_position}].robots must be an array")
        for robot_position, raw_robot in enumerate(raw_robots):
            if not isinstance(raw_robot, dict):
                raise UserError(
                    f"jars[{jar_position}].robots[{robot_position}] must be an object"
                )
            class_name = require_string(
                raw_robot.get("class"),
                f"jars[{jar_position}].robots[{robot_position}].class",
            )
            name = require_string(
                raw_robot.get("name"),
                f"jars[{jar_position}].robots[{robot_position}].name",
            )
            raw_version = raw_robot.get("version")
            if raw_version is not None and not isinstance(raw_version, str):
                raise UserError(
                    f"jars[{jar_position}].robots[{robot_position}].version "
                    "must be a string or null"
                )
            robots_by_jar.setdefault(jar, []).append(
                IndexedRobot(
                    name=name,
                    version=raw_version,
                    class_name=class_name,
                    jar=jar,
                    catalogs=tuple(raw_catalogs),
                )
            )

    return robots_by_jar


def match_catalog_queries(
    entries: Sequence[CatalogEntry], catalog_queries: Sequence[str]
) -> set[str]:
    catalog_names = list(
        dict.fromkeys(catalog for entry in entries for catalog in entry.catalogs)
    )
    matched_catalogs: set[str] = set()
    for query in catalog_queries:
        matches = [catalog for catalog in catalog_names if matches_fuzzy(catalog, query)]
        if not matches:
            raise UserError(f"no catalog matches '{query}'")
        matched_catalogs.update(matches)
    return matched_catalogs


def list_row_matches(row: ListRow, query: str) -> bool:
    return matches_fuzzy(
        " ".join(
            (
                row.jar,
                ",".join(row.catalogs),
                row.name,
                row.version,
                row.class_name,
                row.url,
            )
        ),
        query,
    )


def print_list_table(rows: Sequence[ListRow]) -> None:
    grouped: dict[str, list[tuple[str, str, str]]] = {}
    for row in rows:
        installed = "yes" if row.installed else "no"
        name = row.name or Path(row.jar).stem
        rendered = (installed, name, row.jar)
        for catalog in row.catalogs:
            grouped.setdefault(catalog, []).append(rendered)

    name_width = max(len(name) for group in grouped.values() for _, name, _ in group)
    first = True
    for catalog, group in grouped.items():
        if not first:
            print()
        first = False
        print(f"== {catalog.upper()} ==")
        for installed, name, jar in group:
            print(f"{installed:<3}  {name:<{name_width}}  {jar}")


def command_download(arguments: argparse.Namespace) -> int:
    entries = load_catalog()
    selected = select_entries(entries, arguments.jar, arguments.catalog)
    REFS_DIR.mkdir(parents=True, exist_ok=True)

    downloaded = 0
    present = 0
    failed = 0
    for entry in selected:
        destination = REFS_DIR / entry.file_name
        if destination.exists():
            if destination.is_file():
                present += 1
                continue
            print(
                f"Cannot download {entry.file_name}: destination exists and is not a file",
                file=sys.stderr,
            )
            failed += 1
            continue

        print(f"Downloading {entry.file_name}")
        try:
            download_jar(entry, destination)
            downloaded += 1
        except (OSError, ValueError, urllib.error.URLError, zipfile.BadZipFile) as error:
            print(f"Failed to download {entry.file_name}: {error}", file=sys.stderr)
            failed += 1

    write_index(entries)
    print(
        f"Download: {downloaded} downloaded, {present} present, "
        f"{failed} failed ({REFS_DIR})"
    )
    return 1 if failed else 0


def require_robots_dir() -> None:
    if not ROBOTS_DIR.is_dir():
        raise UserError(
            f"{ROBOTS_DIR} does not exist; run 'just robocode install' first"
        )


def relative_symlink_target(source: Path, destination: Path) -> str:
    return os.path.relpath(source, destination.parent)


def resolved_link_target(path: Path) -> Path:
    target = Path(os.readlink(path))
    if not target.is_absolute():
        target = path.parent / target
    return target.resolve(strict=False)


def is_managed_ref_link(path: Path, entry: CatalogEntry) -> bool:
    if not path.is_symlink():
        return False
    target = resolved_link_target(path)
    managed_targets = [
        (REFS_DIR / entry.file_name).resolve(strict=False),
        *(
            (legacy_dir / entry.file_name).resolve(strict=False)
            for legacy_dir in LEGACY_REFS_DIRS
        ),
    ]
    return target in managed_targets


def points_to_current_ref(path: Path, entry: CatalogEntry) -> bool:
    return resolved_link_target(path) == (REFS_DIR / entry.file_name).resolve(
        strict=False
    )


def command_deploy(arguments: argparse.Namespace) -> int:
    require_robots_dir()
    entries = load_catalog()
    selected = select_entries(entries, arguments.jar, arguments.catalog)

    linked = 0
    updated = 0
    present = 0
    failed = 0
    for entry in selected:
        source = REFS_DIR / entry.file_name
        destination = ROBOTS_DIR / entry.file_name

        if not source.is_file():
            print(
                f"Cannot deploy {entry.file_name}: not downloaded "
                f"(run 'just refs download --jar {entry.file_name}')",
                file=sys.stderr,
            )
            failed += 1
            continue

        if destination.is_symlink():
            if points_to_current_ref(destination, entry):
                present += 1
            elif is_managed_ref_link(destination, entry):
                destination.unlink()
                os.symlink(relative_symlink_target(source, destination), destination)
                updated += 1
            else:
                print(
                    f"Cannot deploy {entry.file_name}: symlink already points elsewhere",
                    file=sys.stderr,
                )
                failed += 1
            continue

        if destination.exists():
            print(
                f"Cannot deploy {entry.file_name}: destination already exists",
                file=sys.stderr,
            )
            failed += 1
            continue

        os.symlink(relative_symlink_target(source, destination), destination)
        linked += 1

    print(
        f"Deploy: {linked} linked, {updated} updated, {present} present, "
        f"{failed} failed ({ROBOTS_DIR})"
    )
    return 1 if failed else 0


def command_undeploy(arguments: argparse.Namespace) -> int:
    require_robots_dir()
    entries = load_catalog()
    selected = select_entries(entries, arguments.jar, arguments.catalog)

    removed = 0
    missing = 0
    failed = 0
    for entry in selected:
        destination = ROBOTS_DIR / entry.file_name
        if not destination.exists() and not destination.is_symlink():
            missing += 1
            continue

        if not is_managed_ref_link(destination, entry):
            print(
                f"Cannot undeploy {entry.file_name}: destination is not a managed refs symlink",
                file=sys.stderr,
            )
            failed += 1
            continue

        destination.unlink()
        removed += 1

    print(
        f"Undeploy: {removed} removed, {missing} missing, "
        f"{failed} failed ({ROBOTS_DIR})"
    )
    return 1 if failed else 0


def command_index(arguments: argparse.Namespace) -> int:
    write_index(load_catalog())
    return 0


def command_list(arguments: argparse.Namespace) -> int:
    entries = load_catalog()
    indexed_by_jar = load_index_by_jar(required=False)
    matched_catalogs = (
        match_catalog_queries(entries, arguments.catalog)
        if arguments.catalog
        else None
    )

    mode = "all"
    if arguments.installed:
        mode = "installed"
    elif arguments.uninstalled:
        mode = "uninstalled"

    rows: list[ListRow] = []
    for entry in entries:
        if matched_catalogs is not None and not any(
            catalog in matched_catalogs for catalog in entry.catalogs
        ):
            continue

        catalogs = tuple(
            catalog
            for catalog in entry.catalogs
            if matched_catalogs is None or catalog in matched_catalogs
        )
        installed = (REFS_DIR / entry.file_name).is_file()
        if mode == "installed" and not installed:
            continue
        if mode == "uninstalled" and installed:
            continue

        indexed_robots = indexed_by_jar.get(entry.file_name, []) if installed else []
        if indexed_robots:
            for robot in indexed_robots:
                rows.append(
                    ListRow(
                        installed=installed,
                        jar=entry.file_name,
                        catalogs=catalogs,
                        name=robot.name,
                        version=robot.version or "",
                        class_name=robot.class_name,
                        url=entry.url,
                    )
                )
        else:
            rows.append(
                ListRow(
                    installed=installed,
                    jar=entry.file_name,
                    catalogs=catalogs,
                    name="",
                    version="",
                    class_name="",
                    url=entry.url,
                )
            )

    if arguments.query:
        rows = [row for row in rows if list_row_matches(row, arguments.query)]

    if not rows:
        print("No robots found.")
        return 0

    print_list_table(rows)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="refs",
        description="Manage reference robots declared in refs.jsonc.",
    )
    subparsers = parser.add_subparsers(dest="command")

    list_parser = subparsers.add_parser(
        "list",
        help="list reference robots registered in refs.jsonc",
    )
    list_parser.add_argument(
        "-q",
        "--query",
        help="case-insensitive search over name, version, class, jar, and catalog",
    )
    list_parser.add_argument(
        "-c",
        "--catalog",
        action="append",
        default=[],
        metavar="QUERY",
        help="filter by catalog using fuzzy match; can be repeated",
    )
    list_mode = list_parser.add_mutually_exclusive_group()
    list_mode.add_argument(
        "-i",
        "--installed",
        action="store_true",
        help="show downloaded registered jars only",
    )
    list_mode.add_argument(
        "-u",
        "--uninstalled",
        action="store_true",
        help="show registered jars that are not downloaded",
    )
    list_mode.add_argument(
        "-a",
        "--all",
        action="store_true",
        help="show all registered jars and mark installation status (default)",
    )

    subparsers.add_parser(
        "index",
        help="rebuild .cache/refs/index.json from downloaded jars",
    )

    def add_selection_arguments(subparser: argparse.ArgumentParser) -> None:
        subparser.add_argument(
            "-j",
            "--jar",
            action="append",
            default=[],
            metavar="QUERY",
            help="select jars by fuzzy match over file name or URL; can be repeated",
        )
        subparser.add_argument(
            "-c",
            "--catalog",
            action="append",
            default=[],
            metavar="QUERY",
            help="select catalog groups by fuzzy match; can be repeated",
        )

    download_parser = subparsers.add_parser(
        "download",
        help="download reference robot jars into .cache/refs",
    )
    add_selection_arguments(download_parser)

    deploy_parser = subparsers.add_parser(
        "deploy",
        help="symlink downloaded reference jars into robocode/robots",
    )
    add_selection_arguments(deploy_parser)

    undeploy_parser = subparsers.add_parser(
        "undeploy",
        help="remove managed reference jar symlinks from robocode/robots",
    )
    add_selection_arguments(undeploy_parser)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    arguments = parser.parse_args(argv)
    if arguments.command is None:
        parser.print_help()
        return 0

    try:
        if arguments.command == "list":
            return command_list(arguments)
        if arguments.command == "index":
            return command_index(arguments)
        if arguments.command == "download":
            return command_download(arguments)
        if arguments.command == "deploy":
            return command_deploy(arguments)
        if arguments.command == "undeploy":
            return command_undeploy(arguments)
    except UserError as error:
        parser.error(str(error))

    parser.error(f"unknown command: {arguments.command}")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
