#!/usr/bin/env python3
"""Scan full Git history and the tracked working tree without publishing sensitive output."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
from artifact_paths import remove_artifact, write_artifact

ROOT = Path(__file__).resolve().parents[2]
BINARY = ROOT / '.cache/security/gitleaks'
LOCK = ROOT / 'ops/security-tools.lock.json'


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args],
                                   stderr=subprocess.DEVNULL)


def verify_binary(binary, pin):
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise ValueError('Required secret scanner is unavailable')
    if hashlib.sha256(binary.read_bytes()).hexdigest() != pin['binarySha256']:
        raise ValueError('Secret scanner executable checksum mismatch')
    version = subprocess.check_output([str(binary), 'version'], stderr=subprocess.DEVNULL,
                                      timeout=15, text=True).strip()
    if version != pin['version']:
        raise ValueError('Secret scanner version mismatch')


def scan(binary, mode, target, report, config, ignore, runner=subprocess.run):
    if report.exists():
        report.unlink()
    template = report.parent / 'count-only.tmpl'
    template.write_text('[{{range $i, $f := .}}{{if $i}},{{end}}{"finding":true}{{end}}]')
    command = [str(binary), mode, str(target), '--config', str(config),
               '--gitleaks-ignore-path', str(ignore), '--ignore-gitleaks-allow',
               '--redact=100', '--no-banner', '--no-color', '--exit-code=1',
               '--report-format=template', '--report-template', str(template),
               '--report-path', str(report), '--timeout=180']
    if mode == 'git':
        command.append('--log-opts=--all')
    result = runner(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    timeout=195, check=False)
    if result.returncode not in (0, 1):
        raise ValueError('Secret scanner execution failed')
    if not report.is_file():
        raise ValueError('Required secret scan report is missing')
    findings = json.loads(report.read_text())
    if not isinstance(findings, list) or any(not isinstance(f, dict) or set(f) != {'finding'} or f['finding'] is not True
                                            for f in findings):
        raise ValueError('Invalid secret scan report')
    if (result.returncode == 0) != (len(findings) == 0):
        raise ValueError('Secret scanner result/report disagree')
    # Never copy findings, source matches, paths or commit/author metadata to receipts.
    return len(findings)


def tracked_copy(root, destination):
    paths = git(root, 'ls-files', '-z').decode().split('\0')
    count = 0
    for name in paths:
        if not name:
            continue
        relative = Path(name)
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('Invalid tracked source path')
        ancestor = root
        for part in relative.parts[:-1]:
            ancestor = ancestor / part
            if ancestor.is_symlink():
                raise ValueError('Tracked source ancestors must not be symlinks')
        source, target = root / relative, destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        # Scan the link text, never external private contents or ignored local secrets.
        data = os.readlink(source).encode() if source.is_symlink() else source.read_bytes()
        target.write_bytes(data)
        count += 1
    if not count:
        raise ValueError('No tracked sources found for secret scan')
    return count


def run(root, binary, pin, receipt):
    remove_artifact(root, receipt)  # No stale receipt or link-following cleanup.
    verify_binary(binary, pin)
    if git(root, 'rev-parse', '--is-shallow-repository').strip() != b'false':
        raise ValueError('Secret scan requires complete Git history')
    head = git(root, 'rev-parse', 'HEAD').decode().strip()
    with tempfile.TemporaryDirectory(prefix='purpuragenda-secrets-') as temp:
        scratch = Path(temp)
        config = scratch / 'default.toml'
        config.write_text('[extend]\nuseDefault = true\n')
        ignore = scratch / 'no-ignores'
        ignore.write_text('')
        clean, positive = scratch / 'clean', scratch / 'positive'
        clean.mkdir(); positive.mkdir()
        (clean / 'sample.txt').write_text('Synthetic clean source.\n')
        # Construct a fake GitLab-shaped fixture only in temporary, redacted scan input.
        (positive / 'fixture.txt').write_text('gitlab_token = "' + 'glpat-' + secrets.token_hex(10) + '"\n')
        if scan(binary, 'dir', clean, scratch / 'clean.json', config, ignore):
            raise ValueError('Secret scanner clean canary failed')
        if not scan(binary, 'dir', positive, scratch / 'positive.json', config, ignore):
            raise ValueError('Secret scanner detection canary failed')
        copied = scratch / 'tracked'
        copied.mkdir()
        files = tracked_copy(root, copied)
        history = scan(binary, 'git', root, scratch / 'history.json', config, ignore)
        working = scan(binary, 'dir', copied, scratch / 'tracked.json', config, ignore)
        summary = {'schemaVersion': 1, 'tool': 'gitleaks', 'version': pin['version'],
                   'commit': head, 'trackedFiles': files,
                   'historyFindings': history, 'trackedTreeFindings': working,
                   'passed': not (history or working)}
        write_artifact(root, receipt, json.dumps(summary, indent=2) + '\n')
        if not summary['passed']:
            raise ValueError('Secret findings block this revision; review privately with redaction')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    try:
        result = run(ROOT, BINARY, json.loads(LOCK.read_text())['gitleaks'],
                     ROOT / 'artifacts/security/secrets.json')
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        raise SystemExit('Secret scan failed; tool, history, canaries, reports and zero findings are required.')
    print('Secret scan passed: complete history and ' + str(result['trackedFiles']) + ' tracked files; zero findings.')


if __name__ == '__main__':
    main()
