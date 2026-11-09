#!/usr/bin/env python3
"""Prepare the pinned official ARM64 Codex App Server as an installed native executable.
No credentials; downloads only when the verified build cache is absent.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request

VERSION = '0.167.0'
ASSET = 'codex-app-server-aarch64-unknown-linux-musl.tar.gz'
ARCHIVE_SHA256 = 'e7c3b654941033e715084f5ac6a0fe275e95b9942611b4b73c5e8743cee1f6a4'
URL = 'https://github.com/openai/codex/releases/download/rust-v' + VERSION + '/' + ASSET
MAX_ARCHIVE_BYTES = 100_000_000
MAX_BINARY_BYTES = 240_000_000

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--cache', required=True, type=Path)
    parser.add_argument('--archive', type=Path)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    archive = args.archive or args.cache / ASSET
    if not archive.exists():
        fd, temporary = tempfile.mkstemp(prefix='codex-download-', dir=args.cache)
        try:
            with os.fdopen(fd, 'wb') as target, urllib.request.urlopen(URL, timeout=60) as response:
                received = 0
                while chunk := response.read(1024 * 1024):
                    received += len(chunk)
                    if received > MAX_ARCHIVE_BYTES:
                        raise RuntimeError('Official archive exceeds the pinned size limit')
                    target.write(chunk)
            if digest(Path(temporary)) != ARCHIVE_SHA256:
                raise RuntimeError('Official Codex archive checksum mismatch')
            os.replace(temporary, archive)
        finally:
            Path(temporary).unlink(missing_ok=True)
    if digest(archive) != ARCHIVE_SHA256:
        raise RuntimeError('Refusing an unverified Codex archive')
    destination = args.output / 'arm64-v8a' / 'libmika_codex.so'
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, 'r:gz') as bundle:
        members = [m for m in bundle.getmembers() if m.isfile() and Path(m.name).name in
                   ('codex-app-server', 'codex-app-server-aarch64-unknown-linux-musl')]
        if len(members) != 1 or not 0 < members[0].size <= MAX_BINARY_BYTES:
            raise RuntimeError('Unexpected official Codex archive layout')
        source = bundle.extractfile(members[0])
        if source is None:
            raise RuntimeError('Official executable is missing')
        temporary = destination.with_suffix('.partial')
        try:
            with source, temporary.open('wb') as target:
                shutil.copyfileobj(source, target, 1024 * 1024)
            if temporary.stat().st_size != members[0].size:
                raise RuntimeError('Truncated official executable')
            with temporary.open('rb') as binary:
                header = binary.read(20)
            if header[:5] != b'\x7fELF\x02' or header[18:20] != b'\xb7\x00':
                raise RuntimeError('Official executable is not ARM64 ELF64')
            temporary.chmod(0o755)
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)
    receipt = {'version': VERSION, 'source': URL, 'archive_sha256': ARCHIVE_SHA256,
               'binary_sha256': digest(destination), 'binary_bytes': destination.stat().st_size}
    (args.output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('Verified Codex App Server ' + VERSION + ' prepared for arm64-v8a')

if __name__ == '__main__':
    main()
