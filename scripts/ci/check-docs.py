#!/usr/bin/env python3
"""Fail closed when the shared ledger or required handoff records are incomplete."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STATES = ('Not started', 'Recovered WIP', 'In progress', 'Draft PR', 'In review', 'Blocked', 'Merged')


HANDOFF_FIELDS = ('Agent and UTC checkpoint:', 'Current task:', 'branch:', 'base:', 'PR URL:',
                  'Last pushed implementation commit:', 'uncommitted changes:', 'What works:',
                  'What remains:', 'Decisions:', 'Verification:', 'Next concrete action:',
                  'Recovery:', 'stacked PR dependencies')


def validate_handoff(text, label):
    errors = [f'{label}: missing handoff field: {field}'
              for field in HANDOFF_FIELDS if field not in text]
    if not re.search(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z', text):
        errors.append(f'{label}: handoff requires a UTC checkpoint timestamp')
    return errors


def validate_claims(root, ledger):
    errors = []
    claims = ledger.partition('## Active task claims\n')[2].split('\n## ', 1)[0]
    if claims.strip() == 'None.':
        return []
    if not claims.strip():
        return ['missing active task claims']
    seen_tasks, seen_branches = set(), set()
    pattern = (r'- (P\d{2}/[a-z0-9-]+): agent=([^;\n]+); '
               r'branch=([^;\n]+); base=([^;\n]+); handoff=([^;\n]+)')
    for line in claims.splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(pattern, line)
        if not match:
            errors.append('invalid active task claim')
            continue
        task, agent, branch, base, reference = match.groups()
        task_id = task.split('/')[0]
        if task_id not in {f'P{i:02}' for i in range(1, 51)}:
            errors.append(f'unknown claimed task: {task}')
        if task_id in seen_tasks or branch in seen_branches:
            errors.append(f'overlapping task or branch claim: {task}')
        state = re.search(r'\| ' + re.escape(task_id) + r' — [^|]+\| ([^;|]+)', ledger)
        if not state or state.group(1).strip() not in ('In progress', 'Draft PR', 'In review', 'Blocked'):
            errors.append(f'claimed task is not active in ledger: {task}')
        seen_tasks.add(task_id)
        seen_branches.add(branch)
        relative = Path(reference)
        if (relative.is_absolute() or '..' in relative.parts
                or relative.parts[:2] != ('docs', 'handoffs') or relative.suffix != '.md'):
            errors.append(f'invalid handoff path: {task}')
            continue
        path = root / relative
        if not path.is_file() or path.is_symlink():
            errors.append(f'missing handoff: {task}')
            continue
        text = path.read_text()
        errors.extend(validate_handoff(text, task))
        for field, value in (('Current task:', task), ('branch:', branch), ('base:', base)):
            if not re.search(re.escape(field) + r'\s*' + re.escape(value) + r'(?=[;,.\s]|$)', text):
                errors.append(f'handoff claim mismatch: {task} {field}')
        if agent not in text:
            errors.append(f'handoff agent mismatch: {task}')
    return errors


def validate(root):
    errors = []
    required = ('AGENTS.md', 'CLAUDE.md', 'STATUS.md', 'docs/implementation-plan.md',
                'docs/decisions.md', 'docs/status.md', 'docs/delivery.md')
    for name in required:
        if not (root / name).is_file() or not (root / name).read_text().strip():
            errors.append(f'missing or empty required document: {name}')
    if errors:
        return errors
    ledger = (root / 'STATUS.md').read_text()
    rows = [line for line in ledger.splitlines() if line.startswith('|')]
    if rows[:2] != ['| Task | Advancement |', '| --- | --- |']:
        errors.append('ledger must have exactly Task and Advancement columns')
    ids = []
    for row in rows[2:]:
        cells = row.split('|')
        if len(cells) != 4:
            errors.append('ledger row must contain exactly two cells')
            continue
        match = re.fullmatch(r'P(\d{2}) — .+', cells[1].strip())
        if not match:
            errors.append('task must have a stable PNN ID and title')
            continue
        ids.append(int(match[1]))
        advancement = cells[2].strip()
        state = advancement.split(';')[0]
        if state not in STATES:
            errors.append(f'P{match[1]} has invalid advancement state')
        if state in ('Draft PR', 'In review', 'Merged') and not re.search(
                r'https://github\.com/Laymer/purpuragenda/pull/\d+', advancement):
            errors.append(f'P{match[1]} requires a PR link')
        if state == 'Blocked' and not re.search(r'depends on|requires|blocked by', advancement):
            errors.append(f'P{match[1]} requires a blocking dependency')
    if ids != list(range(1, 51)):
        errors.append('ledger must contain P01 through P50 exactly once and in order')
    if '## Active handoff\n' not in ledger:
        errors.append('missing active handoff')
    handoff = ledger.partition('## Active handoff\n')[2]
    errors.extend(validate_handoff(handoff, 'coordinator'))
    errors.extend(validate_claims(root, ledger))
    for name in ('AGENTS.md', 'CLAUDE.md'):
        text = (root / name).read_text()
        for reference in ('STATUS.md', 'docs/status.md'):
            if reference not in text:
                errors.append(f'{name} must reference {reference}')
    return errors


if __name__ == '__main__':
    failures = validate(ROOT)
    for failure in failures:
        print(f'ERROR: {failure}', file=sys.stderr)
    if failures:
        sys.exit(1)
    print('Documentation checks passed: 50 tasks, valid states, required handoff and instructions.')
