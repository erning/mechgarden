#!/usr/bin/env python3
"""Download, install, and uninstall the Robocode engine."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import stat
import sys
import tempfile
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path, PurePosixPath
from typing import Sequence


ROOT = Path(__file__).resolve().parent.parent
ROBOCODE_VERSION = "1.10.3"
SETUP_JAR = f"robocode-{ROBOCODE_VERSION}-setup.jar"
DOWNLOAD_URL = (
    "https://github.com/robo-code/robocode/releases/download/"
    f"v{ROBOCODE_VERSION}/{SETUP_JAR}"
)
SETUP_SHA256 = "fe8e4fcabac058d579e89a02b3696c50ddb562b941f64d3cc45603fdf28aa445"

CACHE_DIR = ROOT / ".cache"
SETUP_PATH = CACHE_DIR / SETUP_JAR
INSTALL_DIR = ROOT / "robocode"
INSTALL_MARKER = Path("libs") / "robocode.jar"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def valid_download() -> bool:
    return SETUP_PATH.is_file() and sha256(SETUP_PATH) == SETUP_SHA256


def download_setup() -> None:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    request = urllib.request.Request(
        DOWNLOAD_URL,
        headers={"User-Agent": "MechGarden robocode.py"},
    )

    print(f"Downloading {SETUP_JAR}")
    try:
        with tempfile.NamedTemporaryFile(
            prefix=f".{SETUP_JAR}.",
            suffix=".tmp",
            dir=CACHE_DIR,
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
            with urllib.request.urlopen(request, timeout=120) as response:
                shutil.copyfileobj(response, temporary_file)

        actual_sha256 = sha256(temporary_path)
        if actual_sha256 != SETUP_SHA256:
            raise ValueError(
                f"checksum mismatch: expected {SETUP_SHA256}, got {actual_sha256}"
            )
        os.replace(temporary_path, SETUP_PATH)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)

    print(f"Downloaded and verified {SETUP_JAR} ({SETUP_PATH})")


def ensure_downloaded() -> None:
    if valid_download():
        print(f"Download already present and verified ({SETUP_PATH})")
        return

    if os.path.lexists(SETUP_PATH):
        print(f"Cached setup is invalid; replacing it ({SETUP_PATH})")
    download_setup()


def is_installed() -> bool:
    return (INSTALL_DIR / INSTALL_MARKER).is_file()


def included_member(info: zipfile.ZipInfo) -> bool:
    path = PurePosixPath(info.filename)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError(f"unsafe archive member: {info.filename}")
    return not (
        info.filename == "net/"
        or info.filename.startswith("net/")
        or info.filename == "META-INF/"
        or info.filename.startswith("META-INF/")
    )


def extract_setup(destination: Path) -> None:
    with zipfile.ZipFile(SETUP_PATH) as archive:
        for info in archive.infolist():
            if not included_member(info):
                continue
            archive.extract(info, destination)

    marker = destination / INSTALL_MARKER
    launcher = destination / "robocode.sh"
    if not marker.is_file() or not launcher.is_file():
        raise ValueError("setup archive does not contain a complete Robocode install")

    for pattern in ("*.sh", "*.command"):
        for script in destination.glob(pattern):
            executable_mode = (
                script.stat().st_mode
                | stat.S_IXUSR
                | stat.S_IXGRP
                | stat.S_IXOTH
            )
            script.chmod(executable_mode)


def remove_path(path: Path) -> None:
    if path.is_symlink() or path.is_file():
        path.unlink()
    elif path.is_dir():
        shutil.rmtree(path)


def replace_install(staging: Path) -> None:
    backup = ROOT / f".robocode-backup-{uuid.uuid4().hex}"
    had_existing = os.path.lexists(INSTALL_DIR)

    if had_existing:
        os.replace(INSTALL_DIR, backup)

    try:
        os.replace(staging, INSTALL_DIR)
    except BaseException:
        if had_existing and os.path.lexists(backup):
            os.replace(backup, INSTALL_DIR)
        raise

    if had_existing:
        remove_path(backup)


def command_download() -> int:
    ensure_downloaded()
    return 0


def command_install(force: bool) -> int:
    if is_installed() and not force:
        print(f"Robocode is already installed at {INSTALL_DIR}")
        print("Use 'just robocode install --force' to replace it.")
        return 0

    ensure_downloaded()
    staging = Path(tempfile.mkdtemp(prefix=".robocode-install-", dir=ROOT))
    try:
        print(f"Installing Robocode {ROBOCODE_VERSION} into {INSTALL_DIR}")
        extract_setup(staging)
        replace_install(staging)
    finally:
        if os.path.lexists(staging):
            remove_path(staging)

    print("Installed Robocode. Launch the UI with 'just run'.")
    return 0


def command_uninstall() -> int:
    if not os.path.lexists(INSTALL_DIR):
        print(f"Robocode is not installed at {INSTALL_DIR}")
        return 0

    remove_path(INSTALL_DIR)
    print(f"Uninstalled Robocode from {INSTALL_DIR}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="robocode",
        description=f"Manage the Robocode {ROBOCODE_VERSION} engine.",
    )
    subparsers = parser.add_subparsers(dest="command")
    subparsers.add_parser("download", help="download and verify the setup archive")

    install_parser = subparsers.add_parser(
        "install",
        help="download if needed and install the engine",
    )
    install_parser.add_argument(
        "-f",
        "--force",
        action="store_true",
        help="replace an existing installation",
    )

    subparsers.add_parser(
        "uninstall",
        help="remove the installed engine but keep cached downloads",
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    arguments = parser.parse_args(argv)
    if arguments.command is None:
        parser.print_help()
        return 0

    try:
        if arguments.command == "download":
            return command_download()
        if arguments.command == "install":
            return command_install(arguments.force)
        if arguments.command == "uninstall":
            return command_uninstall()
    except (OSError, ValueError, urllib.error.URLError, zipfile.BadZipFile) as error:
        print(f"robocode: error: {error}", file=sys.stderr)
        return 1

    parser.error(f"unknown command: {arguments.command}")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
