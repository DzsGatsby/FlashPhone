#!/usr/bin/env python3
"""Validate and publish one APK. Dry run by default."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.parse import quote


ROOT = Path(__file__).resolve().parents[2]
REPO = "DzsGatsby/FlashPhone"
GITHUB = f"github.com/{REPO}"
LEGACY_REPO = "DzsGatsby/Mika"
BRIDGE_VERSION = "1.0.8"
PACKAGE = "com.wangzan101.mika.dev"
PRODUCT = "Flash phone"
ASSET_PREFIX = "FlashPhone"
CANONICAL_APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
ABI_SET = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
GRADLE = (
    "./gradlew", "-PdevInstall", "-PphoneCodex=true", ":core:test", ":app:testDebugUnitTest",
    ":app:assembleDebug", ":app:assembleDebugAndroidTest",
)


class ReleaseError(Exception):
    pass


def command(*args, timeout=60, allow_missing=False):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=timeout, check=False)
    if result.returncode == 0:
        return result.stdout.strip()
    if allow_missing and re.search(r"\(HTTP 404\)\s*$", result.stderr):
        return None
    # Keep child stderr, which may contain local configuration, out of release logs.
    raise ReleaseError(f"{Path(args[0]).name} command failed (exit {result.returncode}): {args[1]}")


def github(route, *, repo=REPO, paginate=False, allow_missing=False):
    flags = ("--paginate", "--slurp") if paginate else ()
    path = f"repos/{repo}" + (f"/{route}" if route else "")
    value = command("gh", "api", "--hostname", "github.com", path, *flags,
                    allow_missing=allow_missing)
    if value is None:
        return None
    try:
        return json.loads(value)
    except json.JSONDecodeError as e:
        raise ReleaseError("GitHub returned invalid JSON") from e


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def sdk_tools(explicit):
    if explicit:
        directories = [explicit.resolve()]
    else:
        roots = [Path(os.environ[name]).expanduser() for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if os.environ.get(name)]
        local = ROOT / "local.properties"
        if local.is_file():
            entries = re.findall(r"(?m)^sdk\.dir\s*=\s*(.+)$", local.read_text())
            roots.extend(Path(entry.strip().replace(r"\ ", " ")) for entry in entries)
        directories = []
        for root in roots:
            stable = [p for p in (root / "build-tools").glob("*") if re.fullmatch(r"\d+\.\d+\.\d+", p.name)]
            directories.extend(sorted(stable, key=lambda p: tuple(map(int, p.name.split("."))), reverse=True))
    for directory in directories:
        aapt, signer = directory / "aapt", directory / "apksigner"
        if aapt.is_file() and signer.is_file() and os.access(aapt, os.X_OK) and os.access(signer, os.X_OK):
            return str(aapt), str(signer)
    raise ReleaseError("Android SDK Build Tools with aapt and apksigner are required; use --build-tools if needed")


def manifest(aapt, path):
    if not path.is_file() or not path.stat().st_size:
        raise ReleaseError("APK file missing or empty")
    metadata = command(aapt, "dump", "badging", str(path))
    package = re.findall(r"(?m)^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'([^\n]*)$", metadata)
    if len(package) != 1 or re.search(r"\bsplit=", package[0][3]):
        raise ReleaseError("APK manifest is missing unique package metadata or describes a split")
    application, code, version, _ = package[0]
    native = re.findall(r"(?m)^native-code:(.*)$", metadata)
    abis = set(re.findall(r"'([^']+)'", native[0])) if len(native) == 1 else set()
    return application, int(code), version, abis


def signer_sha(apksigner, path):
    proof = command(apksigner, "verify", "--verbose", "--print-certs", str(path))
    matches = re.findall(r"(?m)^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$", proof)
    if len(matches) != 1:
        raise ReleaseError("APK must have exactly one valid signer")
    return matches[0].lower()


def verify_apk(aapt, apksigner, path, version, code, expected_signer):
    if manifest(aapt, path) != (PACKAGE, code, version, ABI_SET):
        raise ReleaseError(f"APK must be the universal {PACKAGE} {version} (code {code}) with all four ABIs")
    if signer_sha(apksigner, path) != expected_signer:
        raise ReleaseError("APK signer does not match the previously installed app")
    return path.stat().st_size, sha256(path)


def clean_commit(commit):
    if command("git", "rev-parse", "HEAD") != commit:
        raise ReleaseError("Current HEAD does not equal the supplied full commit SHA")
    if command("git", "status", "--porcelain=v1", "--untracked-files=all", "--ignore-submodules=none"):
        raise ReleaseError("Git checkout must be clean, including untracked files and submodules")


def source_code(commit, version):
    source = command("git", "show", f"{commit}:app/build.gradle.kts")
    names = re.findall(r'^\s*versionName\s*=\s*"([0-9.]+)"\s*$', source, re.M)
    codes = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", source, re.M)
    if names != [version] or len(codes) != 1 or int(codes[0]) <= 0:
        raise ReleaseError("Requested version does not match the versionName/versionCode in the exact source commit")
    return int(codes[0])


def remote_tip(branch, commit):
    info = github(f"git/ref/heads/{quote(branch, safe='/')}")
    if (not isinstance(info, dict) or info.get("ref") != f"refs/heads/{branch}" or
            info.get("object", {}).get("sha") != commit):
        raise ReleaseError(f"GitHub {REPO} branch {branch} does not point to the supplied commit")


def standalone_repo():
    info = github("")
    if (not isinstance(info, dict) or info.get("full_name") != REPO or info.get("fork") is not False or
            info.get("private") is not False or info.get("default_branch") != "main"):
        raise ReleaseError("The Flash phone update source must be the public independent repository on main")


def tag_commit(tag, *, repo=REPO):
    info = github(f"git/ref/tags/{tag}", repo=repo, allow_missing=True)
    if info is None:
        return None
    if not isinstance(info, dict) or info.get("ref") != f"refs/tags/{tag}":
        raise ReleaseError("GitHub returned an unexpected tag reference")
    obj = info.get("object", {})
    for _ in range(5):
        kind, sha = obj.get("type"), obj.get("sha")
        if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
            break
        if kind == "commit":
            return sha
        if kind != "tag":
            break
        obj = github(f"git/tags/{sha}", repo=repo).get("object", {})
    raise ReleaseError("Could not resolve the remote tag to a commit")


def find_release(tag, *, repo=REPO):
    pages = github("releases?per_page=100", repo=repo, paginate=True)
    if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
        raise ReleaseError("GitHub returned an unexpected release listing")
    matches = [release for page in pages for release in page if release.get("tag_name") == tag]
    if len(matches) > 1:
        raise ReleaseError("More than one GitHub release has the requested tag")
    return matches[0] if matches else None


def available(tag, commit, *, repo=REPO):
    if find_release(tag, repo=repo) is not None:
        raise ReleaseError("Release already exists (including drafts); refusing to replace or add to any asset")
    existing = tag_commit(tag, repo=repo)
    if existing is not None and existing != commit:
        raise ReleaseError("Existing GitHub tag points to a different commit; refusing to move it")
    return existing is not None


def verify_release(release, tag, commit, name, size, digest, *, draft, repo=REPO):
    if (not isinstance(release, dict) or release.get("tag_name") != tag or release.get("draft") is not draft or
            release.get("prerelease") is not False or
            (release.get("target_commitish") != commit and tag_commit(tag, repo=repo) != commit)):
        raise ReleaseError("GitHub release state or target commit did not match")
    assets = release.get("assets")
    if not isinstance(assets, list) or len(assets) != 1:
        raise ReleaseError("GitHub release does not contain exactly one APK asset")
    asset = assets[0]
    prefix = f"https://github.com/{repo}/releases/download/"
    expected_url = f"{prefix}{tag}/{name}"
    # GitHub assigns a temporary untagged URL before a new draft is published.
    # The asset bytes, owner/repo, filename and draft target still have to match exactly.
    url = asset.get("browser_download_url", "")
    url_matches = url == expected_url or (draft and isinstance(url, str) and re.fullmatch(
        re.escape(prefix) + r"untagged-[0-9a-f]+/" + re.escape(name), url) is not None)
    if (asset.get("name") != name or asset.get("size") != size or asset.get("state") != "uploaded" or
            asset.get("digest") != f"sha256:{digest}" or not url_matches):
        raise ReleaseError("GitHub APK name, size, SHA-256, or download URL did not match the checked build")


def verify_latest(repo, tag, commit, name, size, digest):
    verify_release(github("releases/latest", repo=repo), tag, commit, name, size, digest, draft=False, repo=repo)
    if tag_commit(tag, repo=repo) != commit:
        raise ReleaseError("Published tag did not resolve to the verified commit")


def publish_release(repo, tag, commit, upload, size, digest, notes, title):
    remote = f"github.com/{repo}"
    name = upload.name
    verified_tag = ("--verify-tag",) if available(tag, commit, repo=repo) else ()
    command("gh", "release", "create", tag, str(upload), "--repo", remote,
            "--draft", "--target", commit, "--title", title, "--notes-file", str(notes),
            *verified_tag, timeout=900)
    verify_release(find_release(tag, repo=repo), tag, commit, name, size, digest, draft=True, repo=repo)
    if tag_commit(tag, repo=repo) not in {None, commit}:
        raise ReleaseError("Tag changed after draft creation; draft left unpublished")
    command("gh", "release", "edit", tag, "--repo", remote, "--draft=false", "--latest")
    for attempt in range(3):
        try:
            verify_latest(repo, tag, commit, name, size, digest)
            break
        except ReleaseError:
            if attempt == 2:
                raise
            time.sleep(2)
    print(f"Published and verified: https://{remote}/releases/tag/{tag}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Candidate universal APK; normally app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("version", help="Exact versionName, such as 1.0.0 (no v prefix)")
    parser.add_argument("commit", help="Full 40-character SHA of the checked-out source commit")
    signer = parser.add_mutually_exclusive_group(required=True)
    signer.add_argument("--reference-apk", type=Path, help="An older APK already verified on the intended device")
    signer.add_argument("--signer-sha256", help="Previously verified certificate SHA-256, not the APK checksum")
    parser.add_argument("--branch", default="main", help="Branch pushed to the standalone repository (default: main)")
    parser.add_argument("--build-tools", type=Path, help="Android SDK Build Tools directory containing aapt and apksigner")
    parser.add_argument("--notes-file", type=Path, help="Reviewed public Markdown release notes")
    parser.add_argument("--publish", action="store_true", help="Run Gradle checks, upload to a draft, then publish after verification")
    parser.add_argument("--legacy-bridge", action="store_true",
                        help="For 1.0.8 only: publish the exact same APK on the old update source after verifying the new release")
    args = parser.parse_args()
    commit, version = args.commit.lower(), args.version
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", version):
        raise ReleaseError("Supply a full commit SHA and a numeric major.minor.patch version")
    if args.legacy_bridge and version != BRIDGE_VERSION:
        raise ReleaseError(f"The previous update source only accepts the one-time {BRIDGE_VERSION} migration release")
    clean_commit(commit)
    branch = args.branch
    command("git", "check-ref-format", f"refs/heads/{branch}")
    code = source_code(commit, version)
    apk = args.apk.resolve()
    aapt, apksigner = sdk_tools(args.build_tools)
    if args.reference_apk:
        reference = args.reference_apk.resolve()
        reference_package, reference_code, _, _ = manifest(aapt, reference)
        if reference == apk or reference_package != PACKAGE or reference_code >= code:
            raise ReleaseError("The signer reference must be an older APK for the same application ID")
        expected_signer = signer_sha(apksigner, reference)
    else:
        expected_signer = args.signer_sha256.replace(":", "").lower()
        if not re.fullmatch(r"[0-9a-f]{64}", expected_signer):
            raise ReleaseError("Invalid expected certificate SHA-256")
    size, digest = verify_apk(aapt, apksigner, apk, version, code, expected_signer)
    tag = f"v{version}"
    name = f"{ASSET_PREFIX}-v{version}.apk"
    standalone_repo()
    remote_tip(branch, commit)
    canonical_already_published = args.legacy_bridge and find_release(tag) is not None
    if canonical_already_published:
        verify_latest(REPO, tag, commit, name, size, digest)
    else:
        available(tag, commit)
    if args.legacy_bridge:
        available(tag, commit, repo=LEGACY_REPO)
    print(f"{REPO} {branch}@{commit}\n{tag}: {name}, {size} bytes, sha256:{digest}\n"
          f"{PACKAGE}, version code {code}, certificate sha256:{expected_signer}", flush=True)
    if not args.publish:
        print("Dry run: no build, tag, release, or upload was performed.")
        return

    subprocess.run([sys.executable, "tools/i18n/translations.py", "check"], cwd=ROOT, check=True, timeout=60)
    subprocess.run(GRADLE, cwd=ROOT, check=True, timeout=1800)
    clean_commit(commit)
    size, digest = verify_apk(aapt, apksigner, apk, version, code, expected_signer)
    if not CANONICAL_APK.is_file() or sha256(CANONICAL_APK) != digest:
        raise ReleaseError("Candidate APK is not byte-identical to the canonical output of the checked Gradle build")
    with tempfile.TemporaryDirectory(prefix="flash-phone-release-") as folder:
        upload = Path(folder) / name
        shutil.copyfile(apk, upload)
        if sha256(upload) != digest:
            raise ReleaseError("APK changed while staging the upload")
        notes = Path(folder) / "notes.md"
        public_notes = (args.notes_file.read_text() if args.notes_file else
                        f"{PRODUCT} {version}。安装需要 Android 确认；同签名覆盖升级可保留已有设置与记录。")
        notes.write_text(f"{public_notes.rstrip()}\n\nSource: `{commit}`\n\nAPK SHA-256: `{digest}`\n")
        remote_tip(branch, commit)
        if not canonical_already_published:
            publish_release(REPO, tag, commit, upload, size, digest, notes, f"{PRODUCT} {version}")
        if args.legacy_bridge:
            verify_latest(REPO, tag, commit, name, size, digest)
            # A single tag makes this exact source available to the old repo without
            # updating its branches or establishing a second active release channel.
            if not available(tag, commit, repo=LEGACY_REPO):
                command("git", "push", f"https://github.com/{LEGACY_REPO}.git", f"{commit}:refs/tags/{tag}")
            if tag_commit(tag, repo=LEGACY_REPO) != commit:
                raise ReleaseError("Could not verify the legacy migration tag")
            bridge_notes = Path(folder) / "migration.md"
            bridge_notes.write_text(
                f"Flash phone 已迁至 [独立仓库](https://{GITHUB})。本版是旧安装的一次性迁移更新；"
                f"安装后，应用内更新将直接读取[新发布源](https://{GITHUB}/releases/latest)。"
                f"本页 APK 与[正式发布](https://{GITHUB}/releases/tag/{tag})完全一致。\n\n"
                f"Source: `{commit}`\n\nAPK SHA-256: `{digest}`\n",
                encoding="utf-8",
            )
            publish_release(LEGACY_REPO, tag, commit, upload, size, digest, bridge_notes, f"{PRODUCT} {version} · 迁移更新")


if __name__ == "__main__":
    try:
        main()
    except (ReleaseError, OSError, subprocess.SubprocessError) as error:
        print(f"Release stopped: {error}", file=sys.stderr)
        sys.exit(1)
