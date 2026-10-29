"""Contract seam and deterministic-generation regression tests; no live service required."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('contract_gate', Path(__file__).with_name('check-contract.py'))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class ContractTest(unittest.TestCase):
    def test_reviewed_corrections_require_private_exact_versioned_confirmation(self):
        paths = self.document['paths']; schemas = self.document['components']['schemas']
        target = paths['/admin/customers/{customerId}/corrections/{kind}/{targetId}']
        self.assertEqual({'get', 'post'}, set(target))
        confirm = paths['/admin/corrections/{id}/confirm']['post']
        self.assertEqual({'id', 'X-CSRF-Token', 'Idempotency-Key', 'If-Match'}, {p['name'] for p in confirm['parameters']})
        self.assertTrue({'412', '428'} <= confirm['responses'].keys())
        self.assertEqual({'digest', 'confirmed'}, set(schemas['AdministratorCorrectionReview']['required']))
        self.assertEqual({'reason', 'values'}, set(schemas['AdministratorCorrectionRequest']['required']))
        self.assertEqual({'firstName', 'lastName', 'telephone', 'birthDate', 'birthTime', 'birthplace', 'message', 'text'}, set(schemas['AdministratorCorrectionValues']['properties']))
        self.assertFalse(schemas['AdministratorCorrectionValues']['additionalProperties'])
        self.assertIn('effectiveIntake', schemas['AdministratorBookingDetail']['required'])
        for op in (target['get'], target['post'], confirm, paths['/admin/corrections/{id}']['get']):
            self.assertEqual([{'sessionCookie': []}], op['security'])
            self.assertEqual(['no-store'], op['responses']['200']['headers']['Cache-Control']['schema']['enum'])


    def test_model_normalization_is_idempotent_and_preserves_values_and_other_outputs(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'model').mkdir()
            model = root / 'model/example.ts'
            model.write_text("/**\n * \n */\nexport interface Example { \n    value?: ' keep spaces \u2028\u2029 ';\n}\n\n")
            runtime = root / 'client.ts'
            runtime.write_text('const text = `literal  \n`;\n\n')
            GATE.normalize_models(root)
            self.assertEqual("/**\n *\n */\nexport interface Example {\n    value?: ' keep spaces \u2028\u2029 ';\n}\n", model.read_text())
            self.assertEqual('const text = `literal  \n`;\n\n', runtime.read_text())
            normalized = GATE.contents(root)
            GATE.normalize_models(root)
            self.assertEqual(normalized, GATE.contents(root))

    def test_contact_confirmation_requires_booking_scope_version_and_proof_without_identity_fields(self):
        proof = self.document['paths']['/bookings/{bookingId}/contact-proofs']['post']
        confirm = self.document['paths']['/bookings/{bookingId}/contact']['post']
        self.assertEqual({'bookingId', 'X-CSRF-Token'}, {p['name'] for p in proof['parameters']})
        self.assertEqual({'bookingId', 'X-CSRF-Token', 'Idempotency-Key', 'If-Match'}, {p['name'] for p in confirm['parameters']})
        self.assertIn('429', proof['responses'])
        for operation in (proof, confirm):
            self.assertEqual([{'sessionCookie': []}], operation['security'])
            self.assertIn('404', operation['responses'])
        for code in ('200', '400', '409', '412', '428'):
            self.assertIn(code, confirm['responses'])
        schema = self.document['components']['schemas']['ContactConfirmation']
        self.assertEqual({'token'}, set(schema['properties']))
        self.assertFalse(schema['additionalProperties'])
        self.assertEqual(['token'], schema['required'])
        self.assertEqual('^[A-Za-z0-9_-]{43}$', schema['properties']['token']['pattern'])


    def test_operations_contract_preserves_money_and_separates_attempt_preconditions_from_content_revision(self):
        paths = self.document['paths']; schemas = self.document['components']['schemas']
        money = schemas['AdministratorRefundFollowUp']['properties']; notification = schemas['AdministratorNotification']['properties']
        self.assertTrue({'dueMinor', 'refundedMinor', 'outstandingMinor', 'currency'}.issubset(money))
        for private in ('recipient', 'encryptedEnvelope', 'leaseToken', 'challengeId'):
            self.assertNotIn(private, notification)
        self.assertEqual(2147483647, notification['attempts']['maximum'])
        self.assertEqual({'reason'}, set(schemas['AdministratorNotificationRetryRequest']['properties']))
        retry = paths['/admin/notifications/{jobId}/retry']['post']
        self.assertEqual({'jobId', 'X-CSRF-Token', 'Idempotency-Key', 'If-Match'}, {p['name'] for p in retry['parameters']})
        self.assertIn('attempt count', retry['parameters'][3]['description'])
        self.assertIn('NOTIFICATION_NOT_FOUND', retry['responses']['404']['description'])
        self.assertEqual({'jobId', 'auditId', 'version'}, set(schemas['AdministratorNotificationRetryReceipt']['properties']))
        self.assertNotIn('ETag', retry['responses']['202']['headers'])
        for path in ('/admin/reconciliation', '/admin/notifications'):
            self.assertEqual({'get'}, set(paths[path]))
            operation = paths[path]['get']
            self.assertEqual([{'sessionCookie': []}], operation['security'])
            self.assertEqual(['no-store'], operation['responses']['200']['headers']['Cache-Control']['schema']['enum'])
        self.assertEqual({'400', '401', '403', '404', '409', '412', '428', '503'}, {key for key in retry['responses'] if key.startswith(('4', '5'))})

    def test_administrator_customer_reads_expose_minimal_stable_identity_and_strict_pagination(self):
        paths = self.document['paths']; schemas = self.document['components']['schemas']
        search = paths['/admin/customers']['get']; history = paths['/admin/customers/{customerId}/bookings']['get']
        self.assertEqual({'query', 'limit', 'cursor'}, {p['name'] for p in search['parameters']})
        self.assertEqual({'customerId', 'limit', 'cursor'}, {p['name'] for p in history['parameters']})
        self.assertTrue(search['parameters'][0]['required'])
        self.assertEqual(254, search['parameters'][0]['schema']['maxLength'])
        self.assertEqual({'id', 'email', 'enrolled'}, set(schemas['AdministratorCustomer']['properties']))
        self.assertFalse(schemas['AdministratorCustomer']['additionalProperties'])
        self.assertEqual('#/components/schemas/AccountHistoryBooking', schemas['AdministratorCustomerBookings']['properties']['bookings']['items']['$ref'])
        self.assertIn('CUSTOMER_NOT_FOUND', history['responses']['404']['description'])
        for operation in (search, history):
            self.assertEqual([{'sessionCookie': []}], operation['security'])
            self.assertEqual(['no-store'], operation['responses']['200']['headers']['Cache-Control']['schema']['enum'])
            self.assertEqual(100, operation['parameters'][1]['schema']['maximum'])

    def test_private_note_and_audit_contracts_keep_commands_and_reads_separate(self):
        contract = json.loads((ROOT / 'contracts/openapi.yaml').read_text())
        paths = contract['paths']; schemas = contract['components']['schemas']
        notes = paths['/admin/bookings/{bookingId}/notes']
        self.assertEqual({'get', 'post'}, set(notes))
        self.assertEqual({'text'}, set(schemas['AdministratorNoteRequest']['properties']))
        self.assertEqual(4000, schemas['AdministratorNoteRequest']['properties']['text']['maxLength'])
        self.assertEqual({'bookingId', 'noteId', 'version'}, set(schemas['AdministratorNoteReceipt']['properties']))
        self.assertEqual([1], schemas['AdministratorNoteReceipt']['properties']['version']['enum'])
        self.assertNotIn('metadata', schemas['AdministratorAuditEntry']['properties'])
        self.assertNotIn('text', schemas['AdministratorAuditEntry']['properties'])
        self.assertEqual({'bookingId', 'X-CSRF-Token', 'Idempotency-Key'}, {p['name'] for p in notes['post']['parameters']})
        for operation in (notes['get'], paths['/admin/audit']['get']):
            self.assertEqual({'400', '401', '403', '404', '503'}, {key for key in operation['responses'] if key.startswith(('4', '5'))})
            self.assertEqual(['no-store'], operation['responses']['200']['headers']['Cache-Control']['schema']['enum'])

    def test_account_enrollment_is_explicit_browser_proved_and_never_returns_a_session(self):
        paths = self.document['paths']
        proof, enroll = paths['/account/enrollment-proofs']['post'], paths['/account/enrollments']['post']
        for operation in (proof, enroll):
            self.assertEqual([{'sessionCookie': []}], operation['security'])
            self.assertEqual({'X-CSRF-Token'}, {p['name'] for p in operation['parameters']})
            self.assertIn('429', operation['responses'])
        self.assertIn('202', proof['responses'])
        self.assertEqual(['no-store'], enroll['responses']['204']['headers']['Cache-Control']['schema']['enum'])
        self.assertNotIn('Set-Cookie', enroll['responses']['204']['headers'])
        self.assertNotIn('content', enroll['responses']['204'])
        schema = self.document['components']['schemas']['AccountEnrollmentRequest']
        self.assertFalse(schema['additionalProperties'])
        self.assertEqual({'token', 'password'}, set(schema['required']))
        self.assertTrue(schema['properties']['token']['writeOnly'])
        self.assertTrue(schema['properties']['password']['writeOnly'])
        self.assertEqual(15, schema['properties']['password']['minLength'])

    def test_account_profile_and_history_are_private_versioned_and_exclude_intake(self):
        profile = self.document['paths']['/account/profile']
        history = self.document['paths']['/account/history']['get']
        for operation in (profile['get'], profile['post'], history):
            self.assertEqual([{'sessionCookie': []}], operation['security'])
            self.assertEqual(['no-store'], operation['responses']['200']['headers']['Cache-Control']['schema']['enum'])
            self.assertTrue({'401', '403', '503'} <= operation['responses'].keys())
        self.assertNotIn('parameters', profile['get'])
        self.assertEqual({'X-CSRF-Token', 'Idempotency-Key', 'If-Match'}, {p['name'] for p in profile['post']['parameters']})
        self.assertTrue({'409', '412', '428'} <= profile['post']['responses'].keys())
        schemas = self.document['components']['schemas']
        self.assertEqual({'firstName', 'lastName', 'telephone'}, set(schemas['AccountProfileRequest']['properties']))
        self.assertFalse(schemas['AccountProfileRequest']['additionalProperties'])
        self.assertEqual({'limit', 'beforeStart', 'beforeId'}, {p['name'] for p in history['parameters']})
        self.assertEqual(100, schemas['AccountHistory']['properties']['bookings']['maxItems'])
        self.assertIn({'type': 'null'}, schemas['AccountHistory']['properties']['next']['anyOf'])
        self.assertFalse({'birthDate', 'telephone', 'deliveryEmail', 'meetingUrl', 'message', 'accountId'} & schemas['AccountHistoryBooking']['properties'].keys())

    def test_rescheduling_advice_is_private_and_has_no_service_or_mutation_parameters(self):
        operation = self.document['paths']['/bookings/{bookingId}/availability']['get']
        self.assertEqual([{'sessionCookie': []}], operation['security'])
        self.assertEqual({'bookingId', 'from', 'to', 'displayZone'}, {value['name'] for value in operation['parameters']})
        self.assertTrue(all(value['required'] for value in operation['parameters']))
        self.assertNotIn('requestBody', operation)
        self.assertIn('401', operation['responses'])
        self.assertIn('404', operation['responses'])
        self.assertEqual(['no-store'], operation['responses']['200']['headers']['Cache-Control']['schema']['enum'])
        self.assertEqual(self.document['paths']['/availability']['get']['responses']['200']['content'], operation['responses']['200']['content'])

    def test_guest_booking_discovery_is_scoped_and_never_accepts_a_target(self):
        operation = self.document['paths']['/guest/booking']['get']
        self.assertEqual('getGuestBooking', operation['operationId'])
        self.assertEqual([{'sessionCookie': []}], operation['security'])
        self.assertNotIn('parameters', operation)
        self.assertNotIn('requestBody', operation)
        self.assertEqual('#/components/schemas/CustomerBookingStatus', operation['responses']['200']['content']['application/json']['schema']['$ref'])
        self.assertIn('404', operation['responses'])

    def test_finance_commands_preserve_independent_money_and_versioned_confirmation(self):
        cases = [('receipts', 'AdministratorReceiptRequest', False, '201'), ('refunds', 'AdministratorRefundRequest', False, '201'),
                 ('refund-requirements', 'AdministratorRefundRequirementRequest', False, '200'),
                 ('approve', 'AdministratorConfirmationRequest', True, '200'), ('confirm-external-payment', 'AdministratorConfirmationRequest', True, '200')]
        for suffix, schema, versioned, status in cases:
            operation = self.document['paths']['/admin/bookings/{bookingId}/' + suffix]['post']
            self.assertEqual([{'sessionCookie': []}], operation['security'])
            headers = {value['name'] for value in operation['parameters']}
            self.assertTrue({'X-CSRF-Token', 'Idempotency-Key'} <= headers)
            self.assertEqual(versioned, 'If-Match' in headers)
            self.assertEqual(versioned, '428' in operation['responses'])
            self.assertEqual('#/components/schemas/' + schema, operation['requestBody']['content']['application/json']['schema']['$ref'])
            self.assertEqual(['no-store'], operation['responses'][status]['headers']['Cache-Control']['schema']['enum'])
            model = self.document['components']['schemas'][schema]
            self.assertFalse(model['additionalProperties'])
            self.assertEqual(set(model['properties']), set(model['required']))
        money = self.document['components']['schemas']['AdministratorMoneyReceipt']['properties']
        self.assertEqual({'bookingId', 'paymentEntryId', 'bookingVersion'}, set(money))
        self.assertTrue(all(value['readOnly'] for value in money.values()))

    def test_services_are_private_versioned_reasoned_and_separate_from_booking_snapshots(self):
        schemas = self.document['components']['schemas']
        self.assertIn('services', schemas['AdministratorConfiguration']['required'])
        for suffix in ('', '/{serviceId}', '/{serviceId}/archive', '/{serviceId}/restore'):
            command = self.document['paths']['/admin/configuration/services' + suffix]['post']
            self.assertEqual([{'sessionCookie': []}], command['security'])
            parameters = {p['name'] for p in command['parameters']}
            self.assertTrue({'X-CSRF-Token', 'Idempotency-Key'} <= parameters)
            self.assertEqual(bool(suffix), 'If-Match' in parameters)
            self.assertEqual(bool(suffix), '428' in command['responses'])
            model = schemas[command['requestBody']['content']['application/json']['schema']['$ref'].split('/')[-1]]
            self.assertIn('reason', model['required'])
            self.assertFalse(model['additionalProperties'])
            self.assertNotIn('id', model['properties'])
            self.assertNotIn('active', model['properties'])
            self.assertEqual(['no-store'], command['responses']['200']['headers']['Cache-Control']['schema']['enum'])

    def setUp(self):
        self.document = json.loads((ROOT / 'contracts/openapi.yaml').read_text())

    def test_configuration_commands_require_reviewed_versions_and_previews_never_mutate(self):
        paths, schemas = self.document['paths'], self.document['components']['schemas']
        commands = {'/admin/configuration/weekly': ('AdministratorWeeklyChangeRequest', 'AdministratorCalendarReceipt'),
                    '/admin/configuration/date-exceptions': ('AdministratorDateExceptionRequest', 'AdministratorCalendarReceipt'),
                    '/admin/configuration/date-exceptions/{date}/removal': ('AdministratorConfigurationReview', 'AdministratorCalendarReceipt'),
                    '/admin/configuration/blackouts': ('AdministratorBlackoutRequest', 'AdministratorCalendarReceipt'),
                    '/admin/configuration/blackouts/{blackoutId}/removal': ('AdministratorConfigurationReview', 'AdministratorCalendarReceipt'),
                    '/admin/configuration/policy': ('AdministratorPolicyChangeRequest', 'AdministratorPolicyReceipt')}
        for path, (request, receipt) in commands.items():
            operation = paths[path]['post']
            headers = {value['name']: value for value in operation['parameters'] if value['in'] == 'header'}
            self.assertEqual({'X-CSRF-Token', 'Idempotency-Key', 'If-Match'}, headers.keys())
            self.assertTrue(all(value['required'] for value in headers.values()))
            self.assertEqual('#/components/schemas/' + request, operation['requestBody']['content']['application/json']['schema']['$ref'])
            self.assertEqual('#/components/schemas/' + receipt, operation['responses']['200']['content']['application/json']['schema']['$ref'])
            self.assertTrue({'409', '412', '428'} <= operation['responses'].keys())
            self.assertTrue({'reason', 'acknowledgedBookingIds'} <= set(schemas[request]['required']))
            self.assertTrue(schemas[request]['properties']['acknowledgedBookingIds']['uniqueItems'])
        preview = paths['/admin/configuration/conflicts']['post']
        self.assertEqual(['X-CSRF-Token'], [value['name'] for value in preview['parameters']])
        self.assertFalse({'409', '412', '428'} & preview['responses'].keys())
        self.assertNotIn('parameters', paths['/admin/configuration']['get'])
        self.assertEqual(set(schemas['AdministratorWeeklySchedule']['required']), set(schemas['AdministratorWeeklySchedule']['properties']))
        self.assertEqual(7, len(schemas['AdministratorWeeklySchedule']['required']))
        self.assertFalse({'intake', 'deliveryEmail', 'customerId'} & schemas['AdministratorConfigurationConflict']['properties'].keys())
        for schema in schemas.values():
            if schema.get('properties', {}).keys() & {'calendarVersion', 'policyVersion', 'weekly', 'proposal'}:
                self.assertFalse(schema['additionalProperties'])

    def test_administrator_creation_names_versions_explicit_overrides_and_never_customer_identity(self):
        operation = self.document['paths']['/admin/bookings']['post']
        schemas = self.document['components']['schemas']
        self.assertEqual({'X-CSRF-Token', 'Idempotency-Key'}, {value['name'] for value in operation['parameters']})
        self.assertEqual('#/components/schemas/BookingReceipt', operation['responses']['201']['content']['application/json']['schema']['$ref'])
        request = schemas['AdministratorBookingCreateRequest']
        self.assertTrue({'calendarVersion', 'customerEmail', 'meetingUrl', 'override', 'noticeVersion'} <= set(request['required']))
        self.assertFalse({'customerId', 'adminId', 'origin', 'state'} & request['properties'].keys())
        override = schemas['AdministratorOverride']
        self.assertFalse(override['additionalProperties'])
        self.assertNotIn('OVERLAP', override['properties']['rules']['items']['enum'])
        self.assertTrue(override['properties']['rules']['uniqueItems'])

    def test_administrator_changes_are_versioned_reasoned_and_never_take_late_classification_from_clients(self):
        paths, schemas = self.document['paths'], self.document['components']['schemas']
        for suffix, request in (('reject', 'AdministratorRejectionRequest'), ('cancel', 'AdministratorCancellationRequest'),
                                ('reschedule', 'AdministratorRescheduleRequest'), ('outcome', 'AdministratorOutcomeRequest'),
                                ('meeting', 'AdministratorMeetingChangeRequest')):
            operation = paths['/admin/bookings/{bookingId}/' + suffix]['post']
            headers = {value['name'] for value in operation['parameters'] if value['in'] == 'header'}
            self.assertEqual({'X-CSRF-Token', 'Idempotency-Key', 'If-Match'}, headers)
            self.assertTrue({'404', '409', '412', '428'} <= operation['responses'].keys())
            self.assertEqual('#/components/schemas/' + request, operation['requestBody']['content']['application/json']['schema']['$ref'])
            self.assertIn('reason', schemas[request]['required'])
            self.assertFalse(schemas[request]['additionalProperties'])
        self.assertEqual(['PROVIDER', 'CUSTOMER'], schemas['AdministratorCancellationRequest']['properties']['classification']['enum'])
        self.assertEqual(['COMPLETED', 'NO_SHOW'], schemas['AdministratorOutcomeRequest']['properties']['outcome']['enum'])
        self.assertEqual('#/components/schemas/AdministratorOverride', schemas['AdministratorRescheduleRequest']['properties']['override']['$ref'])

    def test_privacy_exports_require_reason_review_and_private_binary_download(self):
        paths = self.document['paths']; schemas = self.document['components']['schemas']
        for path in ['/admin/customers/{customerId}/exports', '/admin/exports/{exportId}/release']:
            operation = paths[path]['post']
            self.assertEqual([{'sessionCookie': []}], operation['security'])
            self.assertTrue({'X-CSRF-Token', 'Idempotency-Key'}.issubset({p['name'] for p in operation['parameters']}))
            self.assertIn('fresh', operation['description'].lower())
            self.assertEqual(['no-store'], operation['responses']['200']['headers']['Cache-Control']['schema']['enum'])
        self.assertEqual(['reason'], schemas['AdministratorExportRequest']['required'])
        self.assertEqual({'digest', 'reviewedThirdPartyInformation'}, set(schemas['AdministratorExportReview']['required']))
        self.assertEqual('binary', paths['/admin/exports/{exportId}/download']['get']['responses']['200']['content']['application/octet-stream']['schema']['format'])
        self.assertEqual({'id', 'customerId', 'expiresAt', 'digest', 'released', 'document'}, set(schemas['AdministratorExportPreview']['required']))

    def test_public_operations_and_same_origin_base(self):
        self.assertEqual('3.1.0', self.document['openapi'])
        self.assertEqual([{'url': '/api/v1'}], self.document['servers'])
        operations = [operation for path in self.document['paths'].values() for operation in path.values()]
        self.assertEqual({'listServices', 'availability', 'bootstrapSession', 'getSession', 'logoutSession',
                          'requestCreateProof', 'requestManagementProof', 'redeemGuestProof', 'loginAdministrator', 'beginAdministratorRecovery', 'reenrollAdministrator', 'listAdministratorBookings', 'getAdministratorBooking', 'loginAccount', 'changeAccountPassword',
                          'recordAdministratorReceipt', 'approveAdministratorBooking', 'confirmAdministratorExternalPayment', 'recordAdministratorRefund', 'requireAdministratorRefund',
                          'createBooking', 'getBooking', 'getGuestBooking', 'bookingReschedulingAvailability', 'cancelBooking', 'rescheduleBooking', 'getBookingContext',
                          'getAdministratorConfiguration', 'previewAdministratorConfigurationConflicts', 'replaceAdministratorWeeklySchedule', 'setAdministratorDateException',
                          'removeAdministratorDateException', 'addAdministratorBlackout', 'removeAdministratorBlackout', 'updateAdministratorPolicy', 'createAdministratorBooking',
                          'rejectAdministratorBooking', 'cancelAdministratorBooking', 'rescheduleAdministratorBooking', 'recordAdministratorOutcome', 'changeAdministratorMeeting',
                          'createAdministratorService', 'updateAdministratorService', 'archiveAdministratorService', 'restoreAdministratorService',
                          'getAccountProfile', 'updateAccountProfile', 'getAccountHistory', 'requestAccountEnrollmentProof', 'enrollAccount', 'requestContactProof', 'confirmContactChange', 'getAdministratorNotes', 'getAdministratorAudit', 'appendAdministratorNote', 'searchAdministratorCustomers', 'getAdministratorCustomerBookings', 'getAdministratorReconciliation', 'getAdministratorNotifications', 'retryAdministratorNotification', 'prepareAdministratorExport', 'releaseAdministratorExport', 'getAdministratorExport', 'downloadAdministratorExport', 'getAdministratorCorrectionTarget', 'getAdministratorCorrection', 'prepareAdministratorCorrection', 'confirmAdministratorCorrection'},
                         {op['operationId'] for op in operations})
        public = {op['operationId'] for op in operations if op['security'] == []}
        self.assertEqual({'listServices', 'availability', 'bootstrapSession', 'getBookingContext'}, public)
        self.assertEqual(len(operations), len({op['operationId'] for op in operations}))

    def test_public_review_context_has_exact_current_versions_and_plaintext_notice(self):
        operation = self.document['paths']['/booking-context']['get']
        self.assertEqual([], operation['security'])
        self.assertEqual(['serviceId'], [value['name'] for value in operation['parameters']])
        self.assertTrue(operation['parameters'][0]['required'])
        for response in operation['responses'].values():
            self.assertEqual(['no-store'], response['headers']['Cache-Control']['schema']['enum'])
        schemas = self.document['components']['schemas']
        self.assertEqual({'serverNow', 'horizon', 'businessZone', 'reservationMinutes', 'service', 'policy', 'notice'}, set(schemas['BookingReviewContext']['required']))
        self.assertEqual('#/components/schemas/Service', schemas['BookingReviewContext']['properties']['service']['$ref'])
        self.assertEqual({'version', 'text', 'synthetic'}, set(schemas['BookingReviewNotice']['required']))
        self.assertEqual(16000, schemas['BookingReviewNotice']['properties']['text']['maxLength'])
        self.assertEqual({'type': 'boolean'}, schemas['BookingReviewNotice']['properties']['synthetic'])
        self.assertEqual({'version', 'gridMinutes', 'dailyCapacity', 'minimumNoticeSeconds', 'pendingLifetimeSeconds', 'expiryBufferSeconds', 'customerCutoffSeconds'}, set(schemas['BookingReviewPolicy']['required']))

    def test_administrator_login_requires_session_csrf_and_exact_write_only_factors(self):
        operation = self.document['paths']['/admin/login']['post']
        self.assertEqual([{'sessionCookie': []}], operation['security'])
        self.assertEqual(['X-CSRF-Token'], [header['name'] for header in operation['parameters']])
        schema = self.document['components']['schemas']['AdministratorLoginRequest']
        self.assertFalse(schema['additionalProperties'])
        self.assertEqual({'email', 'password', 'totp'}, set(schema['required']))
        self.assertEqual(set(schema['required']), set(schema['properties']))
        self.assertTrue(schema['properties']['password']['writeOnly'])
        self.assertTrue(schema['properties']['totp']['writeOnly'])
        self.assertEqual((15, 128), (schema['properties']['password']['minLength'], schema['properties']['password']['maxLength']))
        self.assertEqual('^[0-9]{6}$', schema['properties']['totp']['pattern'])
        self.assertEqual('#/components/schemas/SessionBootstrap', operation['responses']['200']['content']['application/json']['schema']['$ref'])
        self.assertIn('Set-Cookie', operation['responses']['200']['headers'])
        self.assertIn('AUTHENTICATION_FAILED', operation['responses']['401']['description'])
        self.assertIn('Retry-After', operation['responses']['429']['headers'])
        for response in operation['responses'].values():
            self.assertEqual(['no-store'], response['headers']['Cache-Control']['schema']['enum'])

    def test_customer_commands_bind_receipts_versions_and_explicit_private_projections(self):
        paths = self.document['paths']
        for path, method in (('/bookings', 'post'), ('/bookings/{bookingId}', 'get'),
                             ('/bookings/{bookingId}/cancel', 'post'), ('/bookings/{bookingId}/reschedule', 'post')):
            operation = paths[path][method]
            self.assertEqual([{'sessionCookie': []}], operation['security'])
            headers = {value['name']: value for value in operation['parameters'] if value['in'] == 'header'}
            if method == 'post':
                self.assertTrue(headers['X-CSRF-Token']['required'])
                self.assertTrue(headers['Idempotency-Key']['required'])
                self.assertTrue(operation['requestBody']['required'])
            if path.endswith(('/cancel', '/reschedule')):
                self.assertTrue(headers['If-Match']['required'])
                self.assertTrue({'404', '412', '428'} <= operation['responses'].keys())
            for response in operation['responses'].values():
                if '$ref' in response:
                    response = self.document['components']['responses'][response['$ref'].rsplit('/', 1)[1]]
                self.assertEqual(['no-store'], response['headers']['Cache-Control']['schema']['enum'])
        schemas = self.document['components']['schemas']
        request = schemas['CreateBookingRequest']
        self.assertEqual({'serviceId', 'serviceVersion', 'policyVersion', 'startInstant', 'displayZone', 'noticeVersion', 'intake'}, set(request['required']))
        self.assertEqual({'bookingId', 'version'}, set(schemas['BookingReceipt']['required']))
        self.assertEqual({}, schemas['CancelBookingRequest']['properties'])
        self.assertEqual({'policyVersion', 'startInstant'}, set(schemas['RescheduleBookingRequest']['required']))
        for value in schemas['BookingIntake']['properties'].values():
            self.assertTrue(value['writeOnly'])
        projection = schemas['CustomerBookingStatus']['properties']
        self.assertTrue({'pendingExpired', 'version', 'allowedActions', 'payment', 'serverNow', 'customerChangesUntil'} <= projection.keys())
        self.assertFalse({'intake', 'customerId', 'accountId', 'entries', 'snapshot'} & projection.keys())
        self.assertEqual(['string', 'null'], projection['meetingUrl']['type'])
        self.assertEqual(['CANCEL', 'RESCHEDULE'], projection['allowedActions']['items']['enum'])
        self.assertFalse(projection['allowedActions'].get('uniqueItems', False), 'The pinned Angular generator otherwise emits an unhydrated Set for a JSON array')
        self.assertEqual('#/components/schemas/CustomerBookingStatus', paths['/bookings/{bookingId}']['get']['responses']['200']['content']['application/json']['schema']['$ref'])
        self.assertIn('Location', paths['/bookings']['post']['responses']['201']['headers'])

    def test_recovery_contract_only_returns_restricted_setup_and_requires_fresh_mfa(self):
        schemas = self.document['components']['schemas']
        for path, request, result in (('/admin/recovery', 'AdministratorRecoveryRequest', 'AdministratorRecoverySetup'), ('/admin/recovery/reenrollment', 'AdministratorReenrollmentRequest', 'AdministratorReenrollmentResult')):
            operation = self.document['paths'][path]['post']
            self.assertEqual([{'sessionCookie': []}], operation['security'])
            self.assertTrue(operation['parameters'][0]['required'])
            self.assertEqual('#/components/schemas/' + request, operation['requestBody']['content']['application/json']['schema']['$ref'])
            self.assertEqual('#/components/schemas/' + result, operation['responses']['200']['content']['application/json']['schema']['$ref'])
            self.assertNotIn('Set-Cookie', operation['responses']['200']['headers'])
            self.assertFalse(schemas[request]['additionalProperties'])
            self.assertTrue(schemas[request]['properties']['password']['writeOnly'])
        self.assertEqual({'token', 'expiresAt', 'totpSecret', 'provisioningUri'}, set(schemas['AdministratorRecoverySetup']['required']))
        result = schemas['AdministratorReenrollmentResult']['properties']
        self.assertEqual('boolean', result['requiresFreshMfa']['type'])
        self.assertEqual(10, result['recoveryCodes']['minItems'])
        self.assertFalse(result['recoveryCodes'].get('uniqueItems', False), 'Pinned client must hydrate JSON as an array')

    def test_administrator_read_contract_has_bounded_private_explicit_snapshots(self):
        paths = self.document['paths']; schemas = self.document['components']['schemas']
        for path, name in (('/admin/bookings', 'AdministratorAgendaPage'), ('/admin/bookings/{bookingId}', 'AdministratorBookingDetail')):
            operation = paths[path]['get']
            self.assertEqual([{'sessionCookie': []}], operation['security'])
            self.assertEqual('#/components/schemas/' + name, operation['responses']['200']['content']['application/json']['schema']['$ref'])
            self.assertTrue({'400', '401', '403', '503'} <= operation['responses'].keys())
            for response in operation['responses'].values():
                if '$ref' in response: response = self.document['components']['responses'][response['$ref'].rsplit('/', 1)[1]]
                self.assertEqual(['no-store'], response['headers']['Cache-Control']['schema']['enum'])
        parameters = {value['name']: value for value in paths['/admin/bookings']['get']['parameters']}
        self.assertEqual({'from', 'through', 'limit', 'cursor'}, set(parameters))
        self.assertTrue(parameters['from']['required']); self.assertTrue(parameters['through']['required'])
        self.assertEqual(100, parameters['limit']['schema']['maximum']); self.assertEqual(200, parameters['cursor']['schema']['maxLength'])
        self.assertFalse(schemas['AdministratorAgendaPage']['properties']['entries'].get('uniqueItems', False))
        self.assertFalse({'intake', 'deliveryEmail', 'payment', 'meetingUrl'} & schemas['AdministratorAgendaEntry']['properties'].keys())
        self.assertFalse({'customerId', 'adminId'} & schemas['AdministratorBookingDetail']['properties'].keys())
        self.assertTrue({'snapshot', 'payment', 'pendingExpired', 'version'} <= schemas['AdministratorBookingDetail']['properties'].keys())
        self.assertTrue(all(not value.get('writeOnly', False) for value in schemas['AdministratorIntakeSnapshot']['properties'].values()))
        self.assertIn('ETag', paths['/admin/bookings/{bookingId}']['get']['responses']['200']['headers'])
        self.assertNotIn('ETag', paths['/admin/bookings']['get']['responses']['200']['headers'])

    def test_account_credentials_require_private_bound_session_and_never_client_supplied_factor_proofs(self):
        paths=self.document['paths']; schemas=self.document['components']['schemas']
        for path, request in (('/account/login','AccountLoginRequest'),('/account/password','AccountPasswordChangeRequest')):
            operation=paths[path]['post']; self.assertEqual([{'sessionCookie':[]}],operation['security'])
            self.assertEqual('X-CSRF-Token',operation['parameters'][0]['name']); self.assertTrue(operation['parameters'][0]['required'])
            self.assertEqual('#/components/schemas/'+request,operation['requestBody']['content']['application/json']['schema']['$ref'])
            self.assertFalse(schemas[request]['additionalProperties']); self.assertTrue({'400','401','403','429','503'} <= operation['responses'].keys())
            for status in ('400','401','403','429','503','default'):
                ref=operation['responses'][status]['$ref'];response=self.document['components']['responses'][ref.rsplit('/',1)[1]]
                self.assertEqual(['no-store'],response['headers']['Cache-Control']['schema']['enum']);self.assertIn('application/problem+json',response['content'])
        self.assertEqual({'email','password'},set(schemas['AccountLoginRequest']['required']))
        self.assertEqual({'email','currentPassword','newPassword'},set(schemas['AccountPasswordChangeRequest']['required']))
        for field in ('currentPassword','newPassword'):
            value=schemas['AccountPasswordChangeRequest']['properties'][field]; self.assertTrue(value['writeOnly']); self.assertEqual((15,128),(value['minLength'],value['maxLength']))
        self.assertTrue(schemas['AccountLoginRequest']['properties']['password']['writeOnly'])
        reply=paths['/account/login']['post']['responses']['200']; self.assertEqual('#/components/schemas/SessionBootstrap',reply['content']['application/json']['schema']['$ref'])
        changed=paths['/account/password']['post']['responses']['204']; self.assertNotIn('content',changed); self.assertIn('Set-Cookie',changed['headers']); self.assertEqual(['no-store'],changed['headers']['Cache-Control']['schema']['enum'])

    def test_all_references_resolve(self):
        def visit(value):
            if isinstance(value, dict):
                if '$ref' in value:
                    reference = value['$ref']
                    self.assertTrue(reference.startswith('#/'))
                    target = self.document
                    for key in reference[2:].split('/'):
                        target = target[key]
                    self.assertIsInstance(target, dict)
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)
        visit(self.document)

    def test_snapshot_values_and_time_labels_are_required(self):
        schemas = self.document['components']['schemas']
        for name, schema in schemas.items():
            with self.subTest(schema=name):
                self.assertFalse(schema['additionalProperties'])
                self.assertTrue(set(schema['required']) <= schema['properties'].keys())
        service = schemas['Service']['properties']
        self.assertEqual(120, service['durationMinutes']['maximum'])
        self.assertEqual(100000000, service['priceMinor']['maximum'])
        self.assertEqual(['EUR'], service['currency']['enum'])
        self.assertIn('offset', schemas['Slot']['required'])
        self.assertIn('reservedEndInstant', schemas['Slot']['required'])
        self.assertEqual(['Europe/Brussels'], schemas['Availability']['properties']['businessZone']['enum'])
        self.assertTrue({'correlationId', 'retryable', 'refreshAvailability'} <= set(schemas['Problem']['required']))

    def test_availability_inputs_and_problem_response(self):
        operation = self.document['paths']['/availability']['get']
        self.assertEqual({'serviceId', 'from', 'to', 'displayZone'}, {p['name'] for p in operation['parameters']})
        self.assertTrue(all(p['required'] for p in operation['parameters']))
        self.assertIn('31', operation['description'])
        for path in self.document['paths'].values():
            for operation in path.values():
                response=operation['responses']['default']
                if '$ref' in response:response=self.document['components']['responses'][response['$ref'].rsplit('/',1)[1]]
                self.assertIn('application/problem+json', response['content'])

    def test_session_contract_is_same_origin_csrf_protected_and_no_store(self):
        paths = self.document['paths']
        self.assertEqual({'/session/bootstrap': 'post', '/session': 'get', '/session/logout': 'post'},
                         {path: next(iter(paths[path])) for path in ('/session/bootstrap', '/session', '/session/logout')})
        logout = paths['/session/logout']['post']
        self.assertEqual([{'sessionCookie': []}], logout['security'])
        csrf = logout['parameters'][0]
        self.assertEqual(('X-CSRF-Token', 'header', True), (csrf['name'], csrf['in'], csrf['required']))
        self.assertEqual({'sessionCookie': {'type': 'apiKey', 'in': 'cookie', 'name': '__Host-purpuragenda'}},
                         self.document['components']['securitySchemes'])
        schemas = self.document['components']['schemas']
        self.assertEqual({'role', 'idleExpiresAt', 'absoluteExpiresAt'}, schemas['SessionState']['properties'].keys())
        self.assertEqual({'role', 'idleExpiresAt', 'absoluteExpiresAt', 'csrfToken'}, set(schemas['SessionBootstrap']['required']))
        self.assertEqual(['ANONYMOUS', 'GUEST', 'ACCOUNT', 'ADMIN'], schemas['SessionState']['properties']['role']['enum'])
        for path in paths.values():
            for operation in (op for key, op in path.items() if key in ('get', 'post')):
                if operation['operationId'] in ('bootstrapSession', 'getSession'):
                    self.assertEqual(['no-store'], operation['responses']['200']['headers']['Cache-Control']['schema']['enum'])
                self.assertEqual(['no-store'], operation['responses']['default']['headers']['Cache-Control']['schema']['enum']
                                 if 'headers' in operation['responses']['default'] else ['no-store'])

    def test_generator_pin_and_validation(self):
        from xml.etree import ElementTree as ET
        ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
        tree = ET.parse(ROOT / 'contracts/pom.xml')
        self.assertEqual('7.26.0', tree.findtext('.//m:plugin/m:version', namespaces=ns))
        self.assertEqual('false', tree.findtext('.//m:skipValidateSpec', namespaces=ns))
        self.assertEqual('true', tree.findtext('.//m:hideGenerationTimestamp', namespaces=ns))

    def test_generated_file_addition_removal_and_modification_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            first, second = Path(temp) / 'first', Path(temp) / 'second'
            first.mkdir(); second.mkdir()
            (first / 'client.ts').write_text('original')
            (second / 'client.ts').write_text('original')
            self.assertEqual([], GATE.compare(first, second))
            (second / 'client.ts').write_text('stale')
            self.assertEqual(['client.ts'], GATE.compare(first, second))
            (second / 'client.ts').unlink()
            (second / 'extra.ts').write_text('unexpected')
            self.assertEqual(['client.ts', 'extra.ts'], GATE.compare(first, second))

    def test_missing_empty_or_symlinked_generation_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(ValueError):
                GATE.contents(root / 'absent')
            with self.assertRaises(ValueError):
                GATE.contents(root)
            (root / 'link').symlink_to(ROOT / 'contracts/openapi.yaml')
            with self.assertRaises(ValueError):
                GATE.contents(root)


    def test_replacement_preserves_old_sdk_for_invalid_generation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            destination = root / 'frontend/generated'
            destination.mkdir(parents=True)
            (destination / 'old.ts').write_text('preserve')
            fresh = root / 'fresh'
            fresh.mkdir()
            for source in (fresh, root / 'absent'):
                with self.subTest(source=source), self.assertRaises(ValueError):
                    GATE.replace_generated(source, destination, root)
                self.assertEqual('preserve', (destination / 'old.ts').read_text())
            (fresh / 'bad.ts').symlink_to(destination / 'old.ts')
            with self.assertRaises(ValueError):
                GATE.replace_generated(fresh, destination, root)
            self.assertEqual('preserve', (destination / 'old.ts').read_text())

    def test_replacement_rejects_symlink_ancestors_and_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            root, outside, fresh = base / 'repo', base / 'outside', base / 'fresh'
            root.mkdir(); outside.mkdir(); fresh.mkdir()
            (fresh / 'new.ts').write_text('new')
            (outside / 'generated').mkdir()
            (outside / 'generated/old.ts').write_text('preserve')
            (root / 'frontend').symlink_to(outside, target_is_directory=True)
            with self.assertRaises(ValueError):
                GATE.replace_generated(fresh, root / 'frontend/generated', root)
            self.assertEqual('preserve', (outside / 'generated/old.ts').read_text())
            with self.assertRaises(ValueError):
                GATE.contents(root / 'frontend')
            with self.assertRaises(ValueError):
                GATE.replace_generated(fresh, outside / 'generated', root)

    def test_replacement_removes_stale_files_after_valid_staging(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            fresh, destination = root / 'fresh', root / 'generated'
            fresh.mkdir(); destination.mkdir()
            (fresh / 'new.ts').write_text('new')
            (destination / 'old.ts').write_text('old')
            GATE.replace_generated(fresh, destination, root)
            self.assertEqual({'new.ts': b'new'}, GATE.contents(destination))


    def test_failed_install_restores_previous_sdk(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            fresh, destination = root / 'fresh', root / 'generated'
            fresh.mkdir(); destination.mkdir()
            (fresh / 'new.ts').write_text('new')
            (destination / 'old.ts').write_text('preserve')
            rename = Path.rename
            def fail_install(source, target):
                if source.name == 'new':
                    raise OSError('synthetic install failure')
                return rename(source, target)
            with patch.object(Path, 'rename', fail_install), self.assertRaises(OSError):
                GATE.replace_generated(fresh, destination, root)
            self.assertEqual({'old.ts': b'preserve'}, GATE.contents(destination))


if __name__ == '__main__':
    unittest.main()
