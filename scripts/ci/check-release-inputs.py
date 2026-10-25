#!/usr/bin/env python3
"""Input-register validation does not certify live-use readiness."""
import argparse
from pathlib import Path
import subprocess
from release_inputs import validate

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', action='store_true')
    parser.add_argument('--owner-evidence', type=Path)
    args = parser.parse_args()
    try:
        validate(ROOT, args.release, args.owner_evidence)
    except (OSError, ValueError, TypeError, KeyError, AttributeError, subprocess.SubprocessError):
        raise SystemExit('Owner input gate failed: approved content, complete requirements and exact-revision acceptance are required.')
    if args.release:
        print('Owner acceptance receipts match this revision; retain actual check evidence and the separate deployment decision.')
    else:
        print('Owner input register valid; full notice, external/live-use checks and deployment acceptance remain separately required.')


if __name__ == '__main__':
    main()
