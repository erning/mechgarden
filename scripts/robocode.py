#!/usr/bin/env python3
"""Download, install, and uninstall the Robocode engine."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import stat
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path, PurePosixPath
from typing import Sequence


ROOT = Path(__file__).resolve().parent.parent
ROBOCODE_VERSION = "1.11.0"
RELEASE_TAG = "VER_1_11_0"
SETUP_JAR = f"robocode-{ROBOCODE_VERSION}-setup.jar"
DOWNLOAD_URL = (
    "https://github.com/robo-code/robocode/releases/download/"
    f"{RELEASE_TAG}/{SETUP_JAR}"
)
SETUP_SHA256 = "52b6fd8f775f43eb24a59c9aceee258bf7fb09b77273012717d8b5f838d10fc0"

# Engine sources are not bundled in the setup jar; pull the tagged source tree
# from GitHub so coding agents and IDEs can read it without decompiling.
SOURCE_ARCHIVE = f"robocode-{ROBOCODE_VERSION}-src.tar.gz"
SOURCE_URL = (
    "https://github.com/robo-code/robocode/archive/refs/tags/"
    f"{RELEASE_TAG}.tar.gz"
)
SOURCE_SHA256 = "e218ca79c7a3d112796daa17b4bebedcd65d2eb14042470e8cec1cfb4b43816f"
# Top-level directory GitHub wraps the source tree in; stripped on extraction.
SOURCE_ROOT_PREFIX = f"robocode-{RELEASE_TAG}/"
SOURCE_DIR_NAME = "source"
SOURCE_MARKER = Path(SOURCE_DIR_NAME) / "build.gradle.kts"

CACHE_DIR = ROOT / ".cache"
SETUP_PATH = CACHE_DIR / SETUP_JAR
SOURCE_PATH = CACHE_DIR / SOURCE_ARCHIVE
INSTALL_DIR = ROOT / "robocode"
INSTALL_MARKER = Path("libs") / "robocode.jar"
# Version-aware marker so a plain `install` upgrades an older engine in place.
VERSION_MARKER = Path("libs") / f"robocode.core-{ROBOCODE_VERSION}.jar"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def valid_download(path: Path, expected_sha256: str) -> bool:
    return path.is_file() and sha256(path) == expected_sha256


def download_file(url: str, destination: Path, expected_sha256: str) -> None:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "MechGarden robocode.py"},
    )

    print(f"Downloading {destination.name}")
    try:
        with tempfile.NamedTemporaryFile(
            prefix=f".{destination.name}.",
            suffix=".tmp",
            dir=CACHE_DIR,
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
            with urllib.request.urlopen(request, timeout=120) as response:
                shutil.copyfileobj(response, temporary_file)

        actual_sha256 = sha256(temporary_path)
        if actual_sha256 != expected_sha256:
            raise ValueError(
                f"checksum mismatch for {destination.name}: "
                f"expected {expected_sha256}, got {actual_sha256}"
            )
        os.replace(temporary_path, destination)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)

    print(f"Downloaded and verified {destination.name} ({destination})")


def ensure_file(url: str, destination: Path, expected_sha256: str) -> None:
    if valid_download(destination, expected_sha256):
        print(f"Download already present and verified ({destination})")
        return

    if os.path.lexists(destination):
        print(f"Cached download is invalid; replacing it ({destination})")
    download_file(url, destination, expected_sha256)


def ensure_setup_downloaded() -> None:
    ensure_file(DOWNLOAD_URL, SETUP_PATH, SETUP_SHA256)


def ensure_source_downloaded() -> None:
    ensure_file(SOURCE_URL, SOURCE_PATH, SOURCE_SHA256)


def is_installed() -> bool:
    return (INSTALL_DIR / VERSION_MARKER).is_file()


def source_installed() -> bool:
    return (INSTALL_DIR / SOURCE_MARKER).is_file()


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


def extract_source(destination: Path) -> None:
    """Extract the tagged source tree into ``destination / source``.

    The GitHub archive wraps everything in a single top-level directory; that
    prefix is stripped so sources land directly under ``source/``.
    """
    target = destination / SOURCE_DIR_NAME
    extract_kwargs = {"filter": "data"} if sys.version_info >= (3, 12) else {}
    with tarfile.open(SOURCE_PATH, "r:gz") as archive:
        for member in archive.getmembers():
            if not member.name.startswith(SOURCE_ROOT_PREFIX):
                continue
            relative = member.name[len(SOURCE_ROOT_PREFIX):]
            if not relative:
                continue
            path = PurePosixPath(relative)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError(f"unsafe archive member: {member.name}")
            member.name = relative
            archive.extract(member, target, **extract_kwargs)

    if not (target / "build.gradle.kts").is_file():
        raise ValueError("source archive does not contain the Robocode sources")


def deploy_source(destination_parent: Path) -> None:
    """Atomically (re)place ``destination_parent / source`` with fresh sources."""
    staging = Path(tempfile.mkdtemp(prefix=".robocode-source-", dir=ROOT))
    try:
        extract_source(staging)
        final = destination_parent / SOURCE_DIR_NAME
        if os.path.lexists(final):
            remove_path(final)
        os.replace(staging / SOURCE_DIR_NAME, final)
    finally:
        if os.path.lexists(staging):
            remove_path(staging)


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


def command_download(with_source: bool) -> int:
    ensure_setup_downloaded()
    if with_source:
        ensure_source_downloaded()
    return 0


def command_install(force: bool, with_source: bool) -> int:
    if is_installed() and not force:
        print(f"Robocode {ROBOCODE_VERSION} is already installed at {INSTALL_DIR}")
        if with_source and not source_installed():
            print("Adding Robocode sources to the existing install.")
            ensure_source_downloaded()
            deploy_source(INSTALL_DIR)
            print(f"Installed Robocode sources into {INSTALL_DIR / SOURCE_DIR_NAME}")
        elif with_source:
            print(f"Sources already present at {INSTALL_DIR / SOURCE_DIR_NAME}")
        print("Use 'just robocode install --force' to replace the engine.")
        return 0

    ensure_setup_downloaded()
    if with_source:
        ensure_source_downloaded()
    staging = Path(tempfile.mkdtemp(prefix=".robocode-install-", dir=ROOT))
    try:
        print(f"Installing Robocode {ROBOCODE_VERSION} into {INSTALL_DIR}")
        extract_setup(staging)
        if with_source:
            extract_source(staging)
        replace_install(staging)
    finally:
        if os.path.lexists(staging):
            remove_path(staging)

    if with_source:
        print(f"Installed sources into {INSTALL_DIR / SOURCE_DIR_NAME}")
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
    download_parser = subparsers.add_parser(
        "download", help="download and verify the setup and source archives"
    )
    download_parser.add_argument(
        "--no-source",
        action="store_true",
        help="skip downloading the engine source archive",
    )

    install_parser = subparsers.add_parser(
        "install",
        help="download if needed and install the engine (with sources)",
    )
    install_parser.add_argument(
        "-f",
        "--force",
        action="store_true",
        help="replace an existing installation",
    )
    install_parser.add_argument(
        "--no-source",
        action="store_true",
        help="skip downloading and installing the engine sources",
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
            return command_download(with_source=not arguments.no_source)
        if arguments.command == "install":
            return command_install(
                arguments.force, with_source=not arguments.no_source
            )
        if arguments.command == "uninstall":
            return command_uninstall()
    except (
        OSError,
        ValueError,
        urllib.error.URLError,
        zipfile.BadZipFile,
        tarfile.TarError,
    ) as error:
        print(f"robocode: error: {error}", file=sys.stderr)
        return 1

    parser.error(f"unknown command: {arguments.command}")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
