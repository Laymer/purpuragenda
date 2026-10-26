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

    def test_customer_query_requires_complete_authority_and_snapshot_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresCustomerBookingQuery.java'
        source.parent.mkdir(parents=True)
        source.write_text('synthetic implemented query')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'):
            GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.CustomerBookingQueryPostgresTest.xml')
        for count, actual, nested in ((18, 18, ''), (19, 18, ''), (19, 19, '<failure/>'), (19, 19, '<skipped/>'), (19, 19, '<error/>')):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.CustomerBookingQueryPostgresTest" tests="{count}">' +
                              '<testcase/>' * (actual - 1) + f'<testcase>{nested}</testcase></testsuite>')
            with self.assertRaises(ValueError):
                GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.CustomerBookingQueryPostgresTest" tests="19">' + '<testcase/>' * 19 + '</testsuite>')
        GATE.validate(self.root)

    def test_customer_http_requires_real_guarded_transaction_and_race_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/CustomerBookingController.java'
        source.parent.mkdir(parents=True)
        source.write_text('synthetic implemented query')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'):
            GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.CustomerBookingHttpPostgresTest.xml')
        for count, actual, nested in ((4, 4, ''), (5, 4, ''), (5, 5, '<failure/>'), (5, 5, '<skipped/>'), (5, 5, '<error/>')):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.CustomerBookingHttpPostgresTest" tests="{count}">' +
                              '<testcase/>' * (actual - 1) + f'<testcase>{nested}</testcase></testsuite>')
            with self.assertRaises(ValueError):
                GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.CustomerBookingHttpPostgresTest" tests="5">' + '<testcase/>' * 5 + '</testsuite>')
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
        for tests, skipped in ((0, 0), (11, 0), (12, 1)):
            core.write_text(f'<testsuite name="com.purpuragenda.adapters.TransactionCoreTest" tests="{tests}" skipped="{skipped}"/>')
            with self.assertRaises(ValueError):
                GATE.validate(self.root)
        core.write_text('<testsuite name="com.purpuragenda.adapters.TransactionCoreTest" tests="12"/>')
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
        for cases, nested in ((2, ''), (3, '<failure/>'), (3, '<error/>'), (3, '<skipped/>')):
            report.write_text('<testsuite name="com.purpuragenda.adapters.PublicSchedulingPostgresTest" tests="3">' +
                              '<testcase/>' * (cases - 1) + f'<testcase>{nested}</testcase></testsuite>')
            with self.assertRaises(ValueError):
                GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.PublicSchedulingPostgresTest" tests="3">' + '<testcase/>' * 3 + '</testsuite>')
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
        for cases, attributes in (('', ''), ('<testcase/>', ''), ('<testcase/>' * 13, ''), ('<testcase/>' * 14, 'errors="1"'),
                                  ('<testcase><skipped/></testcase>' * 14, ''), ('<testcase><error/></testcase>' * 14, '')):
            report.write_text(f'<testsuite name="{qualified}" tests="14" {attributes}>{cases}</testsuite>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text(f'<testsuite name="{qualified}" tests="14">' + '<testcase/>' * 14 + '</testsuite>')
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

    def test_customer_creation_requires_authority_replay_rollback_and_twenty_way_races(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresCustomerBooking.java'
        source.parent.mkdir(parents=True); source.write_text('synthetic authorized creation adapter')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.CustomerCreationPostgresTest.xml')
        for count, cases, nested in ((0,0,''), (42,42,''), (43,42,''), (44,43,''),
                                     (43,43,'<failure/>'), (43,43,'<error/>'), (43,43,'<skipped/>')):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.CustomerCreationPostgresTest" tests="{count}">'
                              + f'<testcase>{nested}</testcase>' * cases + '</testsuite>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.CustomerCreationPostgresTest" tests="43">'
                          + '<testcase/>' * 43 + '</testsuite>')
        GATE.validate(self.root)

    def test_sessions_require_atomic_handoff_expiry_rollback_and_revocation_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresSessions.java'
        source.parent.mkdir(parents=True); source.write_text('synthetic session adapter')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.SessionDatabaseTest.xml')
        for count, cases, nested in ((0,0,''), (34,34,''), (35,34,''), (36,35,''),
                                     (35,35,'<failure/>'), (35,35,'<error/>'), (35,35,'<skipped/>')):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.SessionDatabaseTest" tests="{count}">'
                              + f'<testcase>{nested}</testcase>' * cases + '</testsuite>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.SessionDatabaseTest" tests="35">'
                          + '<testcase/>' * 35 + '</testsuite>')
        GATE.validate(self.root)

    def test_throttles_and_cleanup_require_complete_boundary_race_rollback_and_lock_order_cases(self):
        self.write()
        for adapter, suite, minimum in (('PostgresRateLimits', 'RateLimitDatabaseTest', 17), ('PostgresSecurityCleanup', 'SecurityCleanupDatabaseTest', 18)):
            source = self.root / f'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/{adapter}.java'
            source.parent.mkdir(parents=True, exist_ok=True); source.write_text('synthetic adapter')
            with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
            report = self.report.with_name(f'TEST-com.purpuragenda.adapters.{suite}.xml')
            for count, cases, nested in ((0,0,''), (minimum-1,minimum-1,''), (minimum,minimum-1,''), (minimum,minimum,'<failure/>'),
                                         (minimum,minimum,'<error/>'), (minimum,minimum,'<skipped/>')):
                report.write_text(f'<testsuite name="com.purpuragenda.adapters.{suite}" tests="{count}">'
                                  + f'<testcase>{nested}</testcase>' * cases + '</testsuite>')
                with self.assertRaises(ValueError): GATE.validate(self.root)
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.{suite}" tests="{minimum}">' + '<testcase/>' * minimum + '</testsuite>')
            GATE.validate(self.root)

    def test_guest_http_requires_real_postgres_proof_binding_expiry_and_throttle_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/GuestAuthController.java'
        source.parent.mkdir(parents=True, exist_ok=True); source.write_text('synthetic controller')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.GuestAuthPostgresTest.xml')
        for count, cases, nested in ((0,0,''), (6,6,''), (7,6,''), (8,7,''), (7,7,'<failure/>'), (7,7,'<error/>'), (7,7,'<skipped/>')):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.GuestAuthPostgresTest" tests="{count}">'
                              + f'<testcase>{nested}</testcase>' * cases + '</testsuite>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.GuestAuthPostgresTest" tests="7">' + '<testcase/>' * 7 + '</testsuite>')
        GATE.validate(self.root)

    def test_admin_login_requires_actual_mfa_session_rotation_revocation_and_capacity_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AdministratorLoginController.java'
        source.parent.mkdir(parents=True, exist_ok=True); source.write_text('synthetic administrator controller')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.AdministratorLoginPostgresTest.xml')
        for count, cases, nested in ((0,0,''), (11,11,''), (12,11,''), (13,12,''), (12,12,'<failure/>'), (12,12,'<error/>'), (12,12,'<skipped/>')):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.AdministratorLoginPostgresTest" tests="{count}">'
                              + f'<testcase>{nested}</testcase>' * cases + '</testsuite>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.AdministratorLoginPostgresTest" tests="12">' + '<testcase/>' * 12 + '</testsuite>')
        GATE.validate(self.root)

    def test_admin_recovery_requires_actual_restricted_reenrollment_and_binding_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AdministratorRecoveryController.java'
        source.parent.mkdir(parents=True, exist_ok=True); source.write_text('synthetic administrator controller')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.AdministratorRecoveryPostgresTest.xml')
        for count, cases, nested in ((0,0,''), (10,10,''), (11,10,''), (12,11,''), (11,11,'<failure/>'), (11,11,'<error/>'), (11,11,'<skipped/>')):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.AdministratorRecoveryPostgresTest" tests="{count}">'
                              + f'<testcase>{nested}</testcase>' * cases + '</testsuite>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.AdministratorRecoveryPostgresTest" tests="11">' + '<testcase/>' * 11 + '</testsuite>')
        GATE.validate(self.root)

    def test_admin_read_http_requires_actual_mfa_privacy_revocation_and_conditional_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AdministratorBookingController.java'
        source.parent.mkdir(parents=True, exist_ok=True); source.write_text('synthetic administrator controller')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.AdministratorBookingHttpPostgresTest.xml')
        for count, cases, nested in ((0,0,''), (10,10,''), (11,10,''), (12,11,''), (11,11,'<failure/>'), (11,11,'<error/>'), (11,11,'<skipped/>')):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.AdministratorBookingHttpPostgresTest" tests="{count}">'
                              + f'<testcase>{nested}</testcase>' * cases + '</testsuite>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.AdministratorBookingHttpPostgresTest" tests="11">' + '<testcase/>' * 11 + '</testsuite>')
        GATE.validate(self.root)

    def test_admin_booking_query_requires_actual_authority_snapshot_and_pagination_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresAdministratorBookingQuery.java'
        source.parent.mkdir(parents=True, exist_ok=True); source.write_text('synthetic administrator reader')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.AdministratorBookingQueryPostgresTest.xml')
        for count, cases, nested in ((0,0,''), (19,19,''), (20,19,''), (21,20,''), (20,20,'<failure/>'), (20,20,'<error/>'), (20,20,'<skipped/>')):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.AdministratorBookingQueryPostgresTest" tests="{count}">'
                              + f'<testcase>{nested}</testcase>' * cases + '</testsuite>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.AdministratorBookingQueryPostgresTest" tests="20">' + '<testcase/>' * 20 + '</testsuite>')
        GATE.validate(self.root)

    def test_guest_recovery_requires_exact_lookup_contact_race_and_queue_rollback_cases(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresGuestRecoveryLookup.java'
        source.parent.mkdir(parents=True); source.write_text('synthetic management recovery lookup')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.GuestRecoveryDatabaseTest.xml')
        for count, cases, nested in ((0,0,''), (5,5,''), (6,5,''), (7,6,''),
                                     (6,6,'<failure/>'), (6,6,'<error/>'), (6,6,'<skipped/>')):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.GuestRecoveryDatabaseTest" tests="{count}">'
                              + f'<testcase>{nested}</testcase>' * cases + '</testsuite>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.GuestRecoveryDatabaseTest" tests="6">'
                          + '<testcase/>' * 6 + '</testsuite>')
        GATE.validate(self.root)

    def test_account_enrollment_requires_complete_proof_race_rollback_and_upgrade_evidence(self):
        self.write()
        source = self.root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresAccountEnrollment.java'
        source.parent.mkdir(parents=True); source.write_text('synthetic account enrollment adapter')
        with self.assertRaisesRegex(ValueError, 'Missing mandatory'): GATE.validate(self.root)
        report = self.report.with_name('TEST-com.purpuragenda.adapters.AccountEnrollmentDatabaseTest.xml')
        for count, cases, nested in ((0, 0, ''), (19, 19, ''), (20, 19, ''), (21, 20, ''),
                                     (20, 20, '<failure/>'), (20, 20, '<error/>'), (20, 20, '<skipped/>')):
            report.write_text(f'<testsuite name="com.purpuragenda.adapters.AccountEnrollmentDatabaseTest" tests="{count}">'
                              + f'<testcase>{nested}</testcase>' * cases + '</testsuite>')
            with self.assertRaises(ValueError): GATE.validate(self.root)
        report.write_text('<testsuite name="com.purpuragenda.adapters.AccountEnrollmentDatabaseTest" tests="20">'
                          + '<testcase/>' * 20 + '</testsuite>')
        GATE.validate(self.root)


if __name__ == '__main__':
    unittest.main()
