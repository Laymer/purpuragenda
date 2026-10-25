import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('migration_report', Path(__file__).with_name('check-migration-report.py'))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class MigrationReportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.report = self.root / 'backend/adapters/target/surefire-reports/TEST-com.purpuragenda.adapters.MigrationTest.xml'
        self.report.parent.mkdir(parents=True)

    def write(self, tests=11, **counts):
        attrs = ' '.join(f'{key}="{value}"' for key, value in counts.items())
        self.report.write_text(f'<testsuite name="com.purpuragenda.adapters.MigrationTest" tests="{tests}" {attrs}/>')

    def test_real_complete_suite_passes(self):
        self.write()
        GATE.validate(self.root)

    def test_unrelated_passing_suite_cannot_replace_missing_migrations(self):
        self.report.with_name('TEST-example.OtherTest.xml').write_text('<testsuite tests="100"/>')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'):
            GATE.validate(self.root)

    def test_empty_partial_failed_errored_or_skipped_suite_fails(self):
        for tests, counts in ((0, {}), (10, {}), (11, {'failures': 1}), (11, {'errors': 1}), (11, {'skipped': 1})):
            with self.subTest(tests=tests, counts=counts):
                self.write(tests, **counts)
                with self.assertRaises(ValueError):
                    GATE.validate(self.root)

    def test_transaction_core_requires_its_own_complete_report(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresScheduling.java'
        source.parent.mkdir(parents=True)
        source.write_text('synthetic implemented adapter')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'):
            GATE.validate(self.root)
        core = self.report.with_name('TEST-com.purpuragenda.adapters.TransactionCoreTest.xml')
        for tests, skipped in ((0, 0), (10, 0), (11, 1)):
            core.write_text(f'<testsuite name="com.purpuragenda.adapters.TransactionCoreTest" tests="{tests}" skipped="{skipped}"/>')
            with self.assertRaises(ValueError):
                GATE.validate(self.root)
        core.write_text('<testsuite name="com.purpuragenda.adapters.TransactionCoreTest" tests="11"/>')
        GATE.validate(self.root)

    def test_booking_persistence_requires_real_roundtrip_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresBookingStore.java'
        source.parent.mkdir(parents=True)
        source.write_text('synthetic implemented persistence adapter')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'):
            GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.BookingPersistenceTest.xml')
        for count, skipped in ((0, 0), (9, 0), (10, 1)):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.BookingPersistenceTest" tests="{count}" skipped="{skipped}"/>')
            with self.assertRaises(ValueError):
                GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.BookingPersistenceTest" tests="10"/>')
        GATE.validate(self.root)

    def test_public_availability_requires_database_backed_evidence(self):
        self.write()
        source = self.root / 'backend/application/src/main/java/com/purpuragenda/application/scheduling/PublicScheduling.java'
        source.parent.mkdir(parents=True)
        source.write_text('synthetic implemented query')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'):
            GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.PublicSchedulingPostgresTest.xml')
        for count, skipped in ((0, 0), (1, 0), (2, 1)):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.PublicSchedulingPostgresTest" tests="{count}" skipped="{skipped}"/>')
            with self.assertRaises(ValueError):
                GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.PublicSchedulingPostgresTest" tests="2"/>')
        GATE.validate(self.root)

    def test_expiry_requires_its_own_complete_unskipped_database_suite(self):
        self.write()
        source = self.root / 'backend/application/src/main/java/com/purpuragenda/application/scheduling/ExpiryReclamation.java'
        source.parent.mkdir(parents=True)
        source.write_text('synthetic implemented reclamation')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'):
            GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.ExpiryReclamationPostgresTest.xml')
        for count, skipped in ((0, 0), (5, 0), (6, 1)):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.ExpiryReclamationPostgresTest" tests="{count}" skipped="{skipped}"/>')
            with self.assertRaises(ValueError):
                GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.ExpiryReclamationPostgresTest" tests="6"/>')
        GATE.validate(self.root)

    def test_quota_requires_migration_integrity_and_race_suite(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresQuotaStore.java'
        source.parent.mkdir(parents=True)
        source.write_text('synthetic quota adapter')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'):
            GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.CustomerQuotaPostgresTest.xml')
        for count, skipped in ((0, 0), (9, 0), (10, 1)):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.CustomerQuotaPostgresTest" tests="{count}" skipped="{skipped}"/>')
            with self.assertRaises(ValueError):
                GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.CustomerQuotaPostgresTest" tests="10"/>')
        GATE.validate(self.root)

    def test_scheduling_commands_require_replay_and_rollback_database_evidence(self):
        self.write()
        source = self.root / 'backend/application/src/main/java/com/purpuragenda/application/scheduling/SchedulingCommands.java'
        source.parent.mkdir(parents=True); source.write_text('synthetic command boundary')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.SchedulingCommandsPostgresTest.xml')
        for count, status in ((0, {}), (4, {}), (5, {'failures': 1}), (5, {'errors': 1}), (5, {'skipped': 1})):
            attrs = ' '.join(f'{key}="{value}"' for key, value in status.items())
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.SchedulingCommandsPostgresTest" tests="{count}" {attrs}/>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.SchedulingCommandsPostgresTest" tests="5"/>')
        GATE.validate(self.root)


    def test_executable_runtime_requires_actual_nonempty_database_cases(self):
        self.write()
        source = self.root / 'backend/bootstrap/src/main/java/com/purpuragenda/bootstrap/Purpuragenda.java'
        source.parent.mkdir(parents=True); source.write_text('synthetic runtime')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory runtime'): GATE.validate(self.root)
        report = self.root / 'backend/bootstrap/target/surefire-reports/TEST-com.purpuragenda.bootstrap.RuntimeDatabaseTest.xml'
        report.parent.mkdir(parents=True)
        qualified = 'com.purpuragenda.bootstrap.RuntimeDatabaseTest'
        for cases, attributes in (('', ''), ('<testcase/>', ''), ('<testcase/>' * 5, 'errors="1"'),
                                  ('<testcase><skipped/></testcase>' * 5, ''), ('<testcase><error/></testcase>' * 5, '')):
            report.write_text(f'<testsuite name="{qualified}" tests="5" {attributes}>{cases}</testsuite>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text(f'<testsuite name="{qualified}" tests="5">' + '<testcase/>' * 5 + '</testsuite>')
        GATE.validate(self.root)

    def test_operator_migration_requires_its_own_database_report(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/DatabaseMigration.java'
        source.parent.mkdir(parents=True); source.write_text('synthetic migration adapter')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.DatabaseMigrationTest.xml')
        report.write_text('<testsuite name="com.purpuragenda.adapters.DatabaseMigrationTest" tests="4"/>')
        with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.DatabaseMigrationTest" tests="5"/>')
        GATE.validate(self.root)

    def test_private_bootstrap_requires_its_own_real_database_report(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresPrivateAdmin.java'
        source.parent.mkdir(parents=True); source.write_text('synthetic bootstrap adapter')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'):
            GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.PrivateAdminDatabaseTest.xml')
        for count, skipped in ((0, 0), (7, 0), (8, 1)):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.PrivateAdminDatabaseTest" tests="{count}" skipped="{skipped}"/>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.PrivateAdminDatabaseTest" tests="8"/>')
        GATE.validate(self.root)

    def test_admin_mfa_requires_complete_atomic_factor_database_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresAdminMfa.java'
        source.parent.mkdir(parents=True); source.write_text('synthetic MFA adapter')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.AdminMfaDatabaseTest.xml')
        for count, skipped in ((0, 0), (11, 0), (12, 1)):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.AdminMfaDatabaseTest" tests="{count}" skipped="{skipped}"/>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.AdminMfaDatabaseTest" tests="12"/>')
        GATE.validate(self.root)

    def test_deferred_booking_fix_requires_real_final_state_and_upgrade_cases(self):
        self.write()
        migration = self.root / 'backend/adapters/src/main/resources/db/changelog/009-deferred-booking-invariants.sql'
        migration.parent.mkdir(parents=True); migration.write_text('synthetic additive migration')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.DeferredBookingIntegrityTest.xml')
        for count, status in ((0, {}), (7, {}), (8, {'failures': 1}), (8, {'errors': 1}), (8, {'skipped': 1})):
            attrs = ' '.join(f'{key}="{value}"' for key, value in status.items())
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.DeferredBookingIntegrityTest" tests="{count}" {attrs}/>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.DeferredBookingIntegrityTest" tests="8"/>')
        GATE.validate(self.root)

    def test_guest_access_requires_executed_redemption_binding_replay_race_and_upgrade_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresGuestAccess.java'
        source.parent.mkdir(parents=True); source.write_text('synthetic guest proof adapter')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.GuestAccessDatabaseTest.xml')
        for count, cases, nested in ((0,0,''), (13,13,''), (14,13,''), (15,14,''),
                                     (14,14,'<failure/>'), (14,14,'<error/>'), (14,14,'<skipped/>')):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.GuestAccessDatabaseTest" tests="{count}">'
                              + f'<testcase>{nested}</testcase>' * cases + '</testsuite>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.GuestAccessDatabaseTest" tests="14">'
                          + '<testcase/>' * 14 + '</testsuite>')
        GATE.validate(self.root)


    def test_recovery_requires_real_atomic_revocation_and_reenrollment_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresAdminRecovery.java'
        source.parent.mkdir(parents=True); source.write_text('synthetic recovery adapter')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.AdminRecoveryDatabaseTest.xml')
        for count, status in ((0, {}), (12, {}), (13, {'failures': 1}), (13, {'errors': 1}), (13, {'skipped': 1})):
            attrs = ' '.join(f'{key}="{value}"' for key, value in status.items())
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.AdminRecoveryDatabaseTest" tests="{count}" {attrs}/>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.AdminRecoveryDatabaseTest" tests="13"/>')
        GATE.validate(self.root)


if __name__ == '__main__':
    unittest.main()
