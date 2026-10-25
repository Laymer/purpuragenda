#!/usr/bin/env python3
"""A passing unrelated adapter suite cannot replace real PostgreSQL migration evidence."""
from pathlib import Path
import xml.etree.ElementTree as ET


def validate(root):
    suites = [('MigrationTest', 11)]
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresScheduling.java').is_file():
        suites.append(('TransactionCoreTest', 11))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/postgres/PostgresBookingStore.java').is_file():
        suites.append(('BookingPersistenceTest', 10))
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresPrivateAdmin.java').is_file():
        suites.append(('PrivateAdminDatabaseTest', 8))

    if (root / 'backend/application/src/main/java/com/purpuragenda/application/scheduling/PublicScheduling.java').is_file():
        suites.append(('PublicSchedulingPostgresTest', 2))
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
    if (root / 'backend/adapters/src/main/java/com/purpuragenda/adapters/out/security/PostgresAdminRecovery.java').is_file():
        suites.append(('AdminRecoveryDatabaseTest', 13))
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


if __name__ == '__main__':
    try:
        validate(Path(__file__).resolve().parents[2])
    except (ValueError, OSError, ET.ParseError):
        raise SystemExit('Mandatory PostgreSQL migration report missing, invalid or failing')
    print('Mandatory PostgreSQL migration suite report verified')
