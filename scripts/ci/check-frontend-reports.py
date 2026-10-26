#!/usr/bin/env python3
"""Require real Angular tests, production output and source-complete coverage."""
import argparse
import importlib.util
from html.parser import HTMLParser
import os
import shutil
import stat
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def cleanup(root):
    # Directory descriptors anchor deletion even if a parent path is renamed.
    # Refuse frontend/target symlinks before removing either stale artifact tree.
    root_fd = os.open(root.resolve(), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        frontend_fd = os.open('frontend', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=root_fd)
        try:
            present = []
            for name in ('coverage', 'dist'):
                try:
                    mode = os.stat(name, dir_fd=frontend_fd, follow_symlinks=False).st_mode
                except FileNotFoundError:
                    continue
                if not stat.S_ISDIR(mode):
                    raise ValueError('Unsafe frontend cleanup path')
                present.append(name)
            for name in present:
                shutil.rmtree(name, dir_fd=frontend_fd)
        finally:
            os.close(frontend_fd)
    finally:
        os.close(root_fd)


class ProductionHtml(HTMLParser):
    def __init__(self):
        super().__init__()
        self.inline_script = False
        self.stylesheet = False

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "script" and "src" not in attributes:
            self.inline_script = True
        if tag == "link" and attributes.get("rel") == "stylesheet" and attributes.get("media", "all") in ("all", "screen"):
            self.stylesheet = True


def validate(root):
    report = root / 'frontend/coverage/junit.xml'
    if not report.is_file():
        raise ValueError('Missing Angular JUnit report')
    tree = ET.parse(report).getroot()
    suites = list(tree.iter('testsuite'))
    if not suites or sum(int(suite.get('tests', '0')) for suite in suites) < 1:
        raise ValueError('Empty Angular test suite')
    if any(int(suite.get(field, '0')) for suite in [tree, *suites] for field in ('failures', 'errors', 'skipped')):
        raise ValueError('Failed or skipped Angular tests')
    if not list(tree.iter('testcase')):
        raise ValueError('Missing Angular test cases')
    count = len(list(tree.iter('testcase')))
    if any(int(suite.get('tests', '0')) != len(suite.findall('testcase')) for suite in suites):
        raise ValueError('Angular test count mismatch')
    if tree.tag == 'testsuites' and tree.get('tests') is not None and int(tree.get('tests')) != count:
        raise ValueError('Angular summary count mismatch')
    if any(list(tree.iter(tag)) for tag in ('failure', 'error', 'skipped')):
        raise ValueError('Failed or skipped Angular test cases')
    output = root / 'frontend/dist/purpuragenda/browser'
    if not (output / 'index.html').is_file() or not list(output.glob('main-*.js')):
        raise ValueError('Missing Angular production build')
    html = ProductionHtml()
    html.feed((output / 'index.html').read_text())
    if html.inline_script or not html.stylesheet:
        raise ValueError('Production styles must load without inline scripts under CSP')
    spec = importlib.util.spec_from_file_location('coverage_gate', ROOT / 'scripts/ci/check-coverage.py')
    gate = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gate)
    return gate.frontend(root)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--clean', action='store_true', help='safely remove stale frontend artifacts')
    args = parser.parse_args()
    try:
        if args.clean:
            cleanup(ROOT)
        else:
            for result in validate(ROOT):
                print(result)
    except (OSError, ValueError, TypeError, KeyError, ET.ParseError) as error:
        sys.exit('Frontend verification failed: ' + str(error))
