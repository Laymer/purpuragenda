#!/usr/bin/env python3
"""Require a substantive test-source change in each new PR/main commit."""
import argparse
from pathlib import Path
import re
import subprocess

# The exact history already published before this policy was approved is immutable.
GRANDFATHERED = 'dd482e3f30f883b0e7c1a3cecd459d2afcc087aa'
TEST_PATH = re.compile(
    r'(?:^|/)(?:src/test/.*\.(?:java|feature)|tests?/.*\.(?:py|js|mjs|ts|feature)|'
    r'[^/]+(?:\.spec|\.test)\.(?:ts|js|mjs)|test_[^/]+\.py)$')


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True,
                                   stderr=subprocess.DEVNULL)


def substantive(before, after):
    def content(text):
        return [line.strip() for line in text.splitlines()
                if line.strip() and not line.lstrip().startswith(
                    ('#', '//', '/*', '*', '*/', 'import ', 'from ', 'package '))]
    return content(before) != content(after)


def validate(root, base, head, grandfathered=GRANDFATHERED):
    # Resolve and verify all supplied objects even when the range is empty.
    for ref in (base, head, grandfathered):
        git(root, 'rev-parse', '--verify', ref + '^{commit}')
    if git(root, 'rev-parse', '--is-shallow-repository').strip() != 'false':
        raise ValueError('Commit policy requires complete Git history')
    historical = set(git(root, 'rev-list', grandfathered).splitlines())
    commits = git(root, 'rev-list', '--reverse', base + '..' + head).splitlines()
    errors = []
    for commit in commits:
        if commit in historical:
            continue
        parents = git(root, 'rev-list', '--parents', '-n', '1', commit).split()[1:]
        if not parents:
            errors.append(commit[:12] + ': new root commits are not permitted')
            continue
        parent = parents[0]
        entries = iter(git(root, 'diff', '--name-status', '--find-renames',
                           '--diff-filter=AMR', '-z', parent, commit).rstrip('\0').split('\0'))
        paths = []
        for status in entries:
            if not status:
                continue
            old_path = next(entries)
            new_path = next(entries) if status.startswith('R') else old_path
            paths.append((old_path, new_path))
        has_test = False
        for old_path, path in paths:
            if not TEST_PATH.search(path):
                continue
            after = git(root, 'show', commit + ':' + path)
            try:
                before = git(root, 'show', parent + ':' + old_path)
            except subprocess.CalledProcessError:
                before = ''
            if substantive(before, after):
                has_test = True
                break
        if not has_test:
            errors.append(commit[:12] + ': requires a substantive test-source addition/update')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    try:
        errors = validate(Path.cwd(), args.base, args.head)
    except (OSError, ValueError, subprocess.CalledProcessError):
        raise SystemExit('Commit policy failed: unavailable or incomplete Git history')
    if errors:
        raise SystemExit('\n'.join(errors))
    print('Every new commit contains a test-source change; relevance requires independent review.')


if __name__ == '__main__':
    main()
