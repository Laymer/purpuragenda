"""Synthetic ledger cases: claims must be complete, nonoverlapping and recoverable."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('check_docs', Path(__file__).with_name('check-docs.py'))
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class LedgerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'prompt.txt').write_text('Synthetic continuation handoff; reconcile the ledger before work.\n')
        for name in ('AGENTS.md', 'CLAUDE.md'):
            (self.root / name).write_text('Read STATUS.md, docs/status.md, docs/decisions.md and docs/delivery.md.\n')
        (self.root / 'docs/handoffs').mkdir(parents=True)
        for name in ('implementation-plan.md', 'decisions.md', 'status.md', 'delivery.md'):
            (self.root / 'docs' / name).write_text('Synthetic evidence\n')
        self.handoff = '\n'.join(CHECK.HANDOFF_FIELDS) + '\n2026-10-25T08:00:00Z\n'
        self.handoff = self.handoff.replace('Current task:', 'Current task: P01/concurrency;')
        self.handoff = self.handoff.replace('branch:', 'branch: task/p01;').replace('base:', 'base: main;')
        self.handoff += '\nSynthetic-agent\n'
        (self.root / 'docs/handoffs/p01.md').write_text(self.handoff)
        self.claim = '- P01/concurrency: agent=Synthetic-agent; branch=task/p01; base=main; handoff=docs/handoffs/p01.md'
        self.ledger = '# Task ledger\n\n| Task | Advancement |\n| --- | --- |\n'
        self.ledger += '\n'.join(f'| P{i:02} — Task | Not started |' for i in range(1, 51))
        self.ledger = self.ledger.replace('| P01 — Task | Not started |', '| P01 — Task | In progress |')
        self.ledger += '\n\n## Active task claims\n\n' + self.claim
        self.ledger += '\n\n## Active handoff\n\n' + self.handoff

    def validate(self, text=None):
        (self.root / 'STATUS.md').write_text(self.ledger if text is None else text)
        return CHECK.validate(self.root)

    def test_valid_claim(self):
        self.assertEqual([], self.validate())

    def test_concurrent_distinct_claims(self):
        second = self.handoff.replace('P01/concurrency', 'P40/coverage').replace('task/p01', 'task/p40')
        (self.root / 'docs/handoffs/p40.md').write_text(second)
        claim = self.claim.replace('P01/concurrency', 'P40/coverage').replace('task/p01', 'task/p40').replace('p01.md', 'p40.md')
        self.assertEqual([], self.validate(self.ledger.replace('| P40 — Task | Not started |', '| P40 — Task | In progress |').replace(self.claim, self.claim + '\n' + claim)))

    def test_claim_must_match_active_ledger_state(self):
        for state in ('Not started', 'Merged', 'Recovered WIP'):
            with self.subTest(state=state):
                errors = self.validate(self.ledger.replace('| In progress |', '| ' + state + ' |'))
                self.assertTrue(any('not active in ledger' in e for e in errors))

    def test_no_active_work(self):
        self.assertEqual([], self.validate(self.ledger.replace(self.claim, 'None.')))

    def test_partial_parent_keeps_merged_slice_evidence_after_claim_release(self):
        # Completing a foundation releases its owner without completing the parent task.
        for state in ('In progress', 'Recovered WIP'):
            with self.subTest(state=state):
                ledger = self.ledger.replace(
                    '| P01 — Task | In progress |',
                    '| P01 — Task | ' + state + '; foundation merged '
                    'https://github.com/Laymer/purpuragenda/pull/32; worker remains |')
                ledger = ledger.replace(self.claim, 'None.')
                self.assertEqual([], self.validate(ledger))
                self.assertTrue((self.root / 'docs/handoffs/p01.md').is_file())

    def test_merged_slice_cannot_keep_a_released_task_claim(self):
        ledger = self.ledger.replace(
            '| P01 — Task | In progress |',
            '| P01 — Task | Merged; https://github.com/Laymer/purpuragenda/pull/32 |')
        self.assertTrue(any('not active in ledger' in error for error in self.validate(ledger)))
        self.assertEqual([], self.validate(ledger.replace(self.claim, 'None.')))

    def test_delivery_document_required(self):
        path = self.root / 'docs/delivery.md'
        path.write_text('')
        self.assertTrue(self.validate())
        path.unlink()
        self.assertTrue(self.validate())

    def test_duplicate_task_or_branch(self):
        for claim in (self.claim, self.claim.replace('P01/concurrency', 'P40/coverage')):
            with self.subTest(claim=claim):
                self.assertTrue(any('overlapping' in e for e in self.validate(self.ledger.replace(self.claim, self.claim + '\n' + claim))))

    def test_unknown_task(self):
        self.assertTrue(any('unknown claimed task' in e for e in self.validate(self.ledger.replace('P01/concurrency', 'P99/concurrency'))))

    def test_missing_claims_or_malformed_claim(self):
        for replacement in ('', 'unstructured claim', self.claim.replace('agent=', 'owner=')):
            with self.subTest(replacement=replacement):
                self.assertTrue(self.validate(self.ledger.replace(self.claim, replacement)))

    def test_unsafe_handoff_path(self):
        for path in ('/tmp/p01.md', '../p01.md', 'docs/handoffs/../p01.md', 'docs/elsewhere.md'):
            with self.subTest(path=path):
                self.assertTrue(any('invalid handoff path' in e for e in self.validate(self.ledger.replace('docs/handoffs/p01.md', path))))

    def test_missing_handoff(self):
        (self.root / 'docs/handoffs/p01.md').unlink()
        self.assertTrue(any('missing handoff' in e for e in self.validate()))

    def test_symlink_handoff_rejected(self):
        path = self.root / 'docs/handoffs/p01.md'
        path.unlink()
        path.symlink_to(self.root / 'docs/status.md')
        self.assertTrue(any('missing handoff' in e for e in self.validate()))

    def test_symlinked_handoff_ancestors_are_rejected_without_reading_target(self):
        for ancestor in ('docs', 'docs/handoffs'):
            with self.subTest(ancestor=ancestor), tempfile.TemporaryDirectory() as outside:
                source = self.root / ancestor
                destination = Path(outside) / source.name
                source.rename(destination)
                source.symlink_to(destination, target_is_directory=True)
                try:
                    original_read = Path.read_text
                    def guarded_read(path, *args, **kwargs):
                        if path == self.root / 'docs/handoffs/p01.md':
                            raise AssertionError('Linked handoff must not be opened')
                        return original_read(path, *args, **kwargs)
                    with patch.object(Path, 'read_text', guarded_read):
                        errors = self.validate()
                    self.assertTrue(any('missing handoff' in error for error in errors))
                    self.assertEqual(self.handoff, (destination / ('handoffs/p01.md' if ancestor == 'docs' else 'p01.md')).read_text())
                finally:
                    source.unlink()
                    destination.rename(source)

    def test_handoff_mismatches(self):
        path = self.root / 'docs/handoffs/p01.md'
        for old, new in (('task/p01', 'task/p010'), ('main;', 'other;'), ('P01/concurrency', 'P01/elsewhere'), ('Synthetic-agent', 'Other-agent')):
            with self.subTest(old=old):
                path.write_text(self.handoff.replace(old, new))
                self.assertTrue(any('mismatch' in e for e in self.validate()))

    def test_handoff_fields_and_timestamp_required(self):
        path = self.root / 'docs/handoffs/p01.md'
        for field in (*CHECK.HANDOFF_FIELDS, '2026-10-25T08:00:00Z'):
            with self.subTest(field=field):
                path.write_text(self.handoff.replace(field, ''))
                self.assertTrue(self.validate())

    def test_historical_ledger_guards(self):
        for old, new in (('| Not started |', '| Unknown |'), ('P02 — Task', 'P01 — Task'), ('| Task | Advancement |', '| Task | Advancement | Extra |'), ('| Not started |', '| Merged |')):
            with self.subTest(old=old):
                self.assertTrue(self.validate(self.ledger.replace(old, new, 1)))

    def test_required_documents(self):
        (self.root / 'docs/decisions.md').unlink()
        self.assertTrue(self.validate())

    def test_handoff_prompt_missing_empty_or_directory_fails(self):
        path = self.root / 'prompt.txt'
        for text in ('', ' \n\t'):
            path.write_text(text)
            self.assertIn('missing or empty required document: prompt.txt', self.validate())
        path.unlink()
        self.assertIn('missing or empty required document: prompt.txt', self.validate())
        path.mkdir()
        self.assertIn('missing or empty required document: prompt.txt', self.validate())

    def test_handoff_prompt_symlink_never_reads_external_contents(self):
        path = self.root / 'prompt.txt'
        path.unlink()
        with tempfile.TemporaryDirectory() as outside:
            target = Path(outside) / 'private.txt'
            target.write_text('Synthetic external file must remain unread and unchanged.')
            path.symlink_to(target)
            original_read = Path.read_text
            def guarded_read(candidate, *args, **kwargs):
                if candidate in (path, target):
                    raise AssertionError('External prompt target must not be read')
                return original_read(candidate, *args, **kwargs)
            with patch.object(Path, 'read_text', guarded_read):
                self.assertIn('handoff prompt must be a regular repository file: prompt.txt', self.validate())
            self.assertEqual('Synthetic external file must remain unread and unchanged.', target.read_text())
            target.unlink()
            self.assertIn('handoff prompt must be a regular repository file: prompt.txt', self.validate())

    def test_frozen_drafts_survive_claim_release_without_claiming_completion(self):
        ledger = self.ledger.replace(self.claim, 'None.').replace(
            '| P10 — Task | Not started |',
            '| P10 — Task | Draft PR; https://github.com/Laymer/purpuragenda/pull/47; '
            'frozen checkpoint, integration pending |').replace(
            '| P45 — Task | Not started |',
            '| P45 — Task | Draft PR; https://github.com/Laymer/purpuragenda/pull/48; '
            'frozen checkpoint, fresh persistence pending |')
        self.assertEqual([], self.validate(ledger))
        self.assertTrue((self.root / 'docs/handoffs/p01.md').is_file())


    def test_published_handoff_branch_and_pr_agree_between_ledger_and_record(self):
        root = Path(__file__).resolve().parents[2]
        line = 'branch: task/p01-claude-handoff-v2; base: main; PR URL: https://github.com/Laymer/purpuragenda/pull/50'
        self.assertIn(line, (root / 'docs/handoffs/p01-claude-handoff.md').read_text())
        # The archived handoff remains immutable; the live coordinator must be free to resume development.
        row = next(line for line in (root / 'STATUS.md').read_text().splitlines() if line.startswith('| P01 — '))
        self.assertIn('https://github.com/Laymer/purpuragenda/pull/50', row)

    def test_coordinator_can_resume_another_task_while_preserving_the_archived_handoff(self):
        resumed = self.handoff.replace('P01/concurrency', 'P45/durable-workers').replace('task/p01;', 'task/p45;')
        (self.root / 'docs/handoffs/p45.md').write_text(resumed)
        claim = self.claim.replace('P01/concurrency', 'P45/durable-workers').replace('task/p01;', 'task/p45;').replace('p01.md', 'p45.md')
        ledger = self.ledger.replace('| P01 — Task | In progress |',
                                    '| P01 — Task | Merged; https://github.com/Laymer/purpuragenda/pull/50 |')
        ledger = ledger.replace('| P45 — Task | Not started |', '| P45 — Task | In progress |').replace(self.claim, claim)
        ledger = ledger.split('## Active handoff\n')[0] + '## Active handoff\n\n' + resumed
        self.assertEqual([], self.validate(ledger))
        self.assertEqual(self.handoff, (self.root / 'docs/handoffs/p01.md').read_text())

    def test_local_testing_guide_names_real_commands_and_forbids_volume_deletion(self):
        text = (Path(__file__).resolve().parents[2] / 'docs/local-testing.md').read_text()
        for command in ('./scripts/dev/local.sh up', './scripts/dev/local.sh smoke', './scripts/dev/local.sh admin-setup',
                        'scripts/ci/verify-build.sh', 'scripts/ci/verify-compose.py'):
            self.assertIn(command, text)
        self.assertIn('Never** run `docker compose down -v`', text)
        self.assertNotIn('./verify.sh', text)  # the root aggregate command does not exist yet

    def test_agent_instructions_must_reference_owner_decisions_and_delivery(self):
        for name in ('AGENTS.md', 'CLAUDE.md'):
            path = self.root / name
            original = path.read_text()
            for reference in ('docs/decisions.md', 'docs/delivery.md'):
                with self.subTest(name=name, reference=reference):
                    path.write_text(original.replace(reference, ''))
                    self.assertTrue(any(name + ' must reference ' + reference in error
                                        for error in self.validate()))
            path.write_text(original)


if __name__ == '__main__':
    unittest.main()
