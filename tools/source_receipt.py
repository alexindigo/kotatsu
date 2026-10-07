#!/usr/bin/env python3
"""Portable source/index receipts, independently pinned by the build consumer."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess


class ReceiptError(ValueError):
    pass


def require(value, message):
    if not value:
        raise ReceiptError(message)


def git(root, *args):
    result = subprocess.run(['git', '--no-optional-locks', '-C', str(root), *args],
                            stdin=subprocess.DEVNULL, capture_output=True)
    require(result.returncode == 0, 'Git receipt command failed: ' + result.stderr.decode(errors='replace'))
    return result.stdout


def digest(data):
    return hashlib.sha256(data).hexdigest()


def snapshot(root):
    root = Path(root).absolute()
    paths = set(filter(None, git(root, 'ls-files', '-c', '-o', '--exclude-standard', '-z').split(b'\0')))
    paths.update(filter(None, git(root, 'ls-tree', '-r', '--name-only', '-z', 'HEAD').split(b'\0')))
    # Generated Python bytecode is never an input, on either side of transport.
    paths = {p for p in paths if b'__pycache__' not in p.split(b'/') and not p.endswith(b'.pyc')}
    records = []
    for raw in sorted(paths):
        name = os.fsdecode(raw)
        require(not name.startswith('/') and all(p not in ('', '.', '..') for p in name.split('/')), 'Unsafe source path')
        path = root / name
        require(all(not p.is_symlink() for p in path.parents if p != root and p.is_relative_to(root)), 'Symlink source ancestor: ' + name)
        if not path.exists() and not path.is_symlink():
            records.append({'path': name, 'kind': 'deleted'})
            continue
        mode = path.lstat().st_mode
        if stat.S_ISLNK(mode):
            content = os.fsencode(os.readlink(path))
            kind = 'symlink'
        else:
            require(stat.S_ISREG(mode), 'Nonregular source input: ' + name)
            content = path.read_bytes()
            kind = 'file'
        records.append({'path': name, 'kind': kind, 'mode': stat.S_IMODE(mode),
                        'sha256': digest(content), 'bytes': len(content)})
    status_rows = git(root, 'status', '--porcelain=v1', '--no-renames', '--untracked-files=all', '-z').split(b'\0')
    status_rows = [row for row in status_rows if row and b'__pycache__' not in row[3:].split(b'/') and not row[3:].endswith(b'.pyc')]
    return {'schemaVersion': 1, 'head': git(root, 'rev-parse', 'HEAD').decode().strip(),
            'headTree': git(root, 'rev-parse', 'HEAD^{tree}').decode().strip(),
            'stagedTree': git(root, 'write-tree').decode().strip(),
            'indexSha256': digest(git(root, 'ls-files', '--stage', '-z')),
            'dirtyStatus': (b'\0'.join(status_rows) + (b'\0' if status_rows else b'')).decode(),
            'inputs': records}


def encode(value):
    return (json.dumps(value, sort_keys=True, indent=2, ensure_ascii=True) + '\n').encode()


def verify(root, baseline, expected_head, expected_tree, receipt, expected_digest):
    require(re.fullmatch(r'[0-9a-f]{40}', expected_head or ''), 'Independent full source HEAD required')
    require(re.fullmatch(r'[0-9a-f]{40}', expected_tree or ''), 'Independent full source tree required')
    require(re.fullmatch(r'[0-9a-f]{64}', expected_digest or ''), 'Independent source receipt SHA256 required')
    path = Path(receipt)
    require(path.is_file() and not path.is_symlink(), 'Missing/nonregular source receipt')
    data = path.read_bytes()
    require(digest(data) == expected_digest, 'Source receipt digest mismatch')
    pinned = json.loads(data)
    current = snapshot(root)
    require(current['head'] == pinned.get('head') == expected_head, 'Source execution HEAD mismatch')
    require(current['headTree'] == pinned.get('headTree') == expected_tree, 'Source execution tree mismatch')
    result = subprocess.run(['git', '--no-optional-locks', '-C', str(root), 'merge-base', '--is-ancestor', baseline, expected_head],
                            stdin=subprocess.DEVNULL, capture_output=True)
    require(result.returncode == 0, 'Source baseline is not an ancestor (or ancestry command failed)')
    require(current == pinned, 'Source inputs/index/dirty status differ from independently pinned receipt')
    return {'head': expected_head, 'headTree': expected_tree, 'stagedTree': current['stagedTree'],
            'sourceReceiptSha256': expected_digest, 'inputCount': len(current['inputs'])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    output = Path(args.output)
    require(output.parent.is_dir() and not output.exists(), 'Receipt collision/missing parent')
    with output.open('xb') as handle:
        handle.write(encode(snapshot(args.root)))
    print(digest(output.read_bytes()))


if __name__ == '__main__':
    main()
