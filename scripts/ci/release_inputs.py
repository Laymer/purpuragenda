"""Safe public input definitions and separate, exact-revision owner acceptance receipts."""
from datetime import date, datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess

GATES = frozenset(('full-privacy-notice', 'retention-schedule', 'booking-cancellation-refund-terms',
    'public-ingress', 'dns-https', 'smtp-sender-delivery', 'zoom-entitlement-links',
    'off-host-backup-restore', 'host-restart-recovery', 'real-client-owner-acceptance', 'deployment-decision'))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_safe(root, relative):
    """Bounded regular files through anchored, no-follow directory handles; never log contents."""
    require(os.name == 'posix' and isinstance(relative, str), 'POSIX verification path required')
    path = PurePosixPath(relative)
    require(not path.is_absolute() and path.as_posix() == relative and path.parts
            and all(part not in ('.', '..') for part in path.parts), 'Unsafe evidence path')
    parent = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in path.parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            os.close(parent); parent = child
        descriptor = os.open(path.parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
        try:
            require(stat.S_ISREG(os.fstat(descriptor).st_mode), 'Evidence must be a regular file')
            data = bytearray()
            while len(data) < 65537:
                chunk = os.read(descriptor, 65537 - len(data))
                if not chunk:
                    break
                data.extend(chunk)
            require(len(data) <= 65536, 'Evidence file exceeds bound')
            return bytes(data)
        finally:
            os.close(descriptor)
    finally:
        os.close(parent)


def decode(data):
    def unique(pairs):
        value = dict(pairs)
        require(len(value) == len(pairs), 'Duplicate evidence fields are forbidden')
        return value
    return json.loads(data, object_pairs_hook=unique)


def register(root):
    document = decode(read_safe(root, 'ops/release-inputs.json'))
    require(isinstance(document, dict) and set(document) == {'schemaVersion', 'recordedOn', 'privacyCore', 'fullNotice', 'facts', 'launchGates'}
            and type(document['schemaVersion']) is int and document['schemaVersion'] == 1,
            'Invalid owner input register')
    require(isinstance(document['recordedOn'], str), 'Owner fact date is missing')
    require(date.fromisoformat(document['recordedOn']).isoformat() == document['recordedOn'], 'Owner fact date is invalid')
    core = document['privacyCore']
    require(isinstance(core, dict) and set(core) == {'version', 'approvedOn', 'path', 'sha256'}
            and isinstance(core['version'], str) and re.fullmatch(r'core-[0-9]{4}-[0-9]{2}-[0-9]{2}', core['version'])
            and core['path'] == 'docs/policies/privacy-core.en.txt'
            and isinstance(core['sha256'], str) and re.fullmatch(r'[a-f0-9]{64}', core['sha256']),
            'Approved core definition is incomplete')
    require(isinstance(core['approvedOn'], str), 'Core approval date is missing')
    require(date.fromisoformat(core['approvedOn']).isoformat() == core['version'][5:], 'Core approval date disagrees')
    text = read_safe(root, core['path'])
    require(hashlib.sha256(text).hexdigest() == core['sha256'] and text.decode('utf-8').strip(),
            'Approved core content changed or is missing')
    notice = document['fullNotice']
    require(isinstance(notice, dict) and set(notice) == {'version', 'path', 'sha256'}, 'Full notice definition is missing')
    if any(value is not None for value in notice.values()):
        require(isinstance(notice['version'], str) and re.fullmatch(r'notice-[a-z0-9][a-z0-9.-]{0,79}', notice['version'])
                and notice['path'] == 'docs/policies/privacy-' + notice['version'] + '.en.txt'
                and isinstance(notice['sha256'], str) and re.fullmatch(r'[a-f0-9]{64}', notice['sha256'])
                and notice['sha256'] != core['sha256'], 'Core approval is not full-notice acceptance')
        content = read_safe(root, notice['path'])
        require(hashlib.sha256(content).hexdigest() == notice['sha256'] and content.decode('utf-8').strip(), 'Full notice content changed or is empty')
    facts = document['facts']
    required = {'domain', 'host', 'publicIngressExists', 'backupDestination', 'backupVerified',
        'smtpExistsOwnerReported', 'smtpSettingsReady', 'zoomExistsOwnerReported', 'zoomSettingsReady',
        'reviewedPoliciesOwnerReported'}
    require(isinstance(facts, dict) and set(facts) == required, 'Unknown or missing public owner facts')
    require(all(type(facts[key]) is bool for key in required - {'domain', 'host', 'backupDestination'}),
            'Owner facts must not imply verification from arbitrary text')
    require(facts['backupDestination'] == 'owner-linux-computer', 'Backup destination needs owner reconciliation')
    domain = facts['domain']
    require(isinstance(domain, dict) and set(domain) == {'name', 'registrar', 'nameservers'} and domain['name'] == 'medinavie.com'
            and domain['registrar'] == 'Squarespace' and isinstance(domain['nameservers'], list)
            and len(domain['nameservers']) == 4
            and set(domain['nameservers']) == {f'ns-cloud-a{i}.googledomains.com' for i in range(1, 5)},
            'Domain facts need public-record reconciliation')
    host = facts['host']
    require(isinstance(host, dict) and set(host) == {'platform', 'version', 'prerequisitesVerified'} and host['platform'] == 'macos-intel'
            and isinstance(host['version'], str) and re.fullmatch(r'[0-9]{2}\.[0-9]{1,2}\.[0-9]{1,2}', host['version'])
            and type(host['prerequisitesVerified']) is bool, 'Invalid host prerequisite facts')
    gates = document['launchGates']
    require(isinstance(gates, list) and all(isinstance(item, str) for item in gates)
            and len(gates) == len(GATES) and set(gates) == GATES, 'Launch requirements cannot be omitted or duplicated')
    return document


def revision(root):
    value = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'],
                                    stderr=subprocess.DEVNULL, timeout=10).decode().strip()
    require(re.fullmatch(r'[a-f0-9]{40}', value), 'Exact implementation revision required')
    return value


def acceptance(evidence_root, expected_revision):
    require(evidence_root is not None, 'Separate owner acceptance evidence is required')
    require(isinstance(expected_revision, str) and re.fullmatch(r'[a-f0-9]{40}', expected_revision),
            'Exact implementation revision required')
    require(set(os.listdir(evidence_root)) == {gate + '.json' for gate in GATES}, 'Only complete safe acceptance receipts are allowed')
    for gate in sorted(GATES):
        receipt = decode(read_safe(evidence_root, gate + '.json'))
        require(isinstance(receipt, dict) and set(receipt) == {'schemaVersion', 'gate', 'sourceRevision', 'verifiedAt', 'ownerApproved'}
                and type(receipt['schemaVersion']) is int and receipt['schemaVersion'] == 1
                and receipt['gate'] == gate and receipt['sourceRevision'] == expected_revision
                and receipt['ownerApproved'] is True, 'Missing or stale owner acceptance')
        require(isinstance(receipt['verifiedAt'], str) and len(receipt['verifiedAt']) <= 40, 'Acceptance time is missing')
        stamp = datetime.fromisoformat(receipt['verifiedAt'].replace('Z', '+00:00'))
        require(stamp.tzinfo is not None and stamp.utcoffset() is not None, 'Acceptance time must include UTC offset')


def validate(root, release=False, owner_evidence=None):
    document = register(root)
    if release:
        require(document['fullNotice']['version'] is not None, 'A complete separately approved privacy notice is required')
        require(owner_evidence is not None and not Path(owner_evidence).resolve().is_relative_to(Path(root).resolve()),
                'Owner acceptance must be supplied separately from reviewed source')
        acceptance(owner_evidence, revision(root))
    return document
