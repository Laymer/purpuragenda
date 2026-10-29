#!/usr/bin/env python3
"""A passing unrelated adapter suite cannot replace real PostgreSQL migration evidence."""
from pathlib import Path
import xml.etree.ElementTree as ET


def validate(root):
    suites = [('MigrationTest', 11)]
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresCustomerExports.java').is_file():
        suites.append(('CustomerExportsPostgresTest', 14))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AdministratorExportController.java').is_file():
        suites.append(('AdministratorExportHttpPostgresTest', 4))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresScheduling.java').is_file():
        suites.append(('TransactionCoreTest', 12))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresBookingStore.java').is_file():
        suites.append(('BookingPersistenceTest', 10))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresPrivateAdmin.java').is_file():
        suites.append(('PrivateAdminDatabaseTest', 8))

    if (root / 'backend/application/src/main/java/com/purpuragenda/application/scheduling/PublicScheduling.java').is_file():
        suites.append(('PublicSchedulingPostgresTest', 3))
    if (root / 'backend/application/src/main/java/com/purpuragenda/application/scheduling/DueBookingExpiry.java').is_file():
        suites.append(('SchedulingMaintenancePostgresTest', 6))
    if (root / 'backend/application/src/main/java/com/purpuragenda/application/scheduling/ExpiryReclamation.java').is_file():
        suites.append(('ExpiryReclamationPostgresTest', 6))
    if (root / 'backend/application/src/main/java/com/purpuragenda/application/scheduling/SchedulingCommands.java').is_file():
        suites.append(('SchedulingCommandsPostgresTest', 5))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresQuotaStore.java').is_file():
        suites.append(('CustomerQuotaPostgresTest', 10))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresAdminMfa.java').is_file():
        suites.append(('AdminMfaDatabaseTest', 12))
    if (root / 'backend/adapters/src/main/resources/db/changelog/009-deferred-booking-invariants.sql').is_file():
        suites.append(('DeferredBookingIntegrityTest', 8))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/DatabaseMigration.java').is_file():
        suites.append(('DatabaseMigrationTest', 5))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresGuestAccess.java').is_file():
        suites.append(('GuestAccessDatabaseTest', 14))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresGuestRecoveryLookup.java').is_file():
        suites.append(('GuestRecoveryDatabaseTest', 6))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresAdminRecovery.java').is_file():
        suites.append(('AdminRecoveryDatabaseTest', 13))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresSessions.java').is_file():
        suites.append(('SessionDatabaseTest', 35))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresCustomerBooking.java').is_file():
        suites.append(('CustomerCreationPostgresTest', 43))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresCustomerBookingQuery.java').is_file():
        suites.append(('CustomerBookingQueryPostgresTest', 25))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresRateLimits.java').is_file():
        suites.append(('RateLimitDatabaseTest', 17))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresSecurityCleanup.java').is_file():
        suites.append(('SecurityCleanupDatabaseTest', 18))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresAccountEnrollment.java').is_file():
        suites.append(('AccountEnrollmentDatabaseTest', 20))
    if (root / 'backend/application/src/main/java/com/purpuragenda/application/AccountEnrollmentRequest.java').is_file():
        suites.append(('AccountEnrollmentBudgetDatabaseTest', 6))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresAccountAuthentication.java').is_file():
        suites.append(('AccountAuthenticationDatabaseTest', 26))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresAccountPassword.java').is_file():
        suites.append(('AccountPasswordDatabaseTest', 28))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AccountController.java').is_file():
        suites.append(('AccountHttpPostgresTest', 19))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/GuestAuthController.java').is_file():
        suites.append(('GuestAuthPostgresTest', 7))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/CustomerBookingController.java').is_file():
        suites.append(('CustomerBookingHttpPostgresTest', 10))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AdministratorLoginController.java').is_file():
        suites.append(('AdministratorLoginPostgresTest', 12))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AdministratorRecoveryController.java').is_file():
        suites.append(('AdministratorRecoveryPostgresTest', 11))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresAdministratorBookingQuery.java').is_file():
        suites.append(('AdministratorBookingQueryPostgresTest', 21))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AdministratorBookingController.java').is_file():
        suites.append(('AdministratorBookingHttpPostgresTest', 11))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AdministratorFinanceController.java').is_file():
        suites.append(('AdministratorFinanceHttpPostgresTest', 17))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresConfiguration.java').is_file():
        suites.extend((('AdministratorConfigurationPostgresTest', 19), ('ConfigurationAuthorityPostgresTest', 9)))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AdministratorServiceController.java').is_file():
        suites.append(('AdministratorConfigurationHttpPostgresTest', 5))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresAccountData.java').is_file():
        suites.append(('AccountDataPostgresTest', 14))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AccountDataController.java').is_file():
        suites.append(('AccountDataHttpPostgresTest', 5))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AccountEnrollmentController.java').is_file():
        suites.append(('AccountEnrollmentHttpPostgresTest', 6))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresContactChanges.java').is_file():
        suites.append(('ContactChangesPostgresTest', 13))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/CustomerContactController.java').is_file():
        suites.append(('ContactHttpPostgresTest', 7))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresNotes.java').is_file():
        suites.append(('NotesPostgresTest', 11))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresAdministratorHistory.java').is_file():
        suites.append(('AdministratorHistoryPostgresTest', 12))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AdministratorHistoryController.java').is_file():
        suites.append(('AdministratorHistoryHttpPostgresTest', 5))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AdministratorCustomerController.java').is_file():
        suites.append(('AdministratorCustomerHttpPostgresTest', 4))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/in/rest/AdministratorOperationsController.java').is_file():
        suites.append(('AdministratorOperationsHttpPostgresTest', 4))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresNotificationRetry.java').is_file():
        suites.append(('NotificationRetryPostgresTest', 18))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresAdministratorOperations.java').is_file():
        suites.append(('AdministratorOperationsPostgresTest', 14))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresAdministratorCustomers.java').is_file():
        suites.append(('AdministratorCustomersPostgresTest', 13))
    for name, minimum in suites:
        qualified = 'com.purpuragenda.adapters.' + name
        report = root / ('backend/adapters/target/surefire-reports/TEST-' + qualified + '.xml')
        if not report.is_file():
            raise ValueError('Missing mandatory PostgreSQL report: ' + name)
        suite = ET.parse(report).getroot()
        if suite.get('name') != qualified or int(suite.get('tests', '0')) < minimum:
            raise ValueError('Incomplete mandatory PostgreSQL suite: ' + name)
        if any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
            raise ValueError('Failed or skipped PostgreSQL checks: ' + name)
        if name in ('CustomerExportsPostgresTest', 'AdministratorExportHttpPostgresTest', 'AdministratorOperationsHttpPostgresTest', 'NotificationRetryPostgresTest', 'AdministratorOperationsPostgresTest', 'AdministratorCustomerHttpPostgresTest', 'AdministratorCustomersPostgresTest', 'AdministratorHistoryHttpPostgresTest', 'AdministratorHistoryPostgresTest', 'NotesPostgresTest', 'ContactHttpPostgresTest', 'ContactChangesPostgresTest', 'AccountEnrollmentHttpPostgresTest', 'AccountDataHttpPostgresTest', 'AccountDataPostgresTest', 'AdministratorConfigurationHttpPostgresTest', 'SchedulingMaintenancePostgresTest', 'AdministratorConfigurationPostgresTest', 'ConfigurationAuthorityPostgresTest', 'PublicSchedulingPostgresTest', 'GuestAccessDatabaseTest', 'GuestRecoveryDatabaseTest', 'SessionDatabaseTest', 'CustomerCreationPostgresTest', 'CustomerBookingQueryPostgresTest', 'CustomerBookingHttpPostgresTest', 'RateLimitDatabaseTest', 'SecurityCleanupDatabaseTest', 'GuestAuthPostgresTest', 'AdministratorLoginPostgresTest', 'AdministratorRecoveryPostgresTest', 'AdministratorBookingQueryPostgresTest', 'AdministratorBookingHttpPostgresTest', 'AdministratorFinanceHttpPostgresTest', 'AccountEnrollmentDatabaseTest', 'AccountEnrollmentBudgetDatabaseTest', 'AccountAuthenticationDatabaseTest', 'AccountPasswordDatabaseTest', 'AccountHttpPostgresTest') and (int(suite.get('tests')) != len(list(suite.iter('testcase')))
                or any(list(suite.iter(tag)) for tag in ('failure', 'error', 'skipped'))):
            raise ValueError('Incomplete or failed authority cases')

    if (root / 'backend/bootstrap/src/main/java/com/purpuragenda/bootstrap/Purpuragenda.java').is_file():
        qualified = 'com.purpuragenda.bootstrap.RuntimeDatabaseTest'
        report = root / ('backend/bootstrap/target/surefire-reports/TEST-' + qualified + '.xml')
        if not report.is_file():
            raise ValueError('Missing mandatory runtime PostgreSQL report')
        suite = ET.parse(report).getroot()
        cases = suite.findall('testcase')
        if (suite.get('name') != qualified or len(cases) < 18 or int(suite.get('tests', '0')) != len(cases)
                or any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped'))
                or any(suite.findall('.//' + key) for key in ('failure', 'error', 'skipped'))):
            raise ValueError('Incomplete or failed runtime PostgreSQL suite')


if __name__ == '__main__':
    try:
        validate(Path(__file__).resolve().parents[2])
    except (ValueError, OSError, ET.ParseError):
        raise SystemExit('Mandatory PostgreSQL migration report missing, invalid or failing')
    print('Mandatory PostgreSQL migration suite report verified')
