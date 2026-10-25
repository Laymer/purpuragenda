Feature: Protected version 1 booking against two backend processes
  @api
  Scenario: Guest request, truthful confirmation, private calendar and cutoff-eligible cancellation
    Given an isolated migrated PostgreSQL stack with two backends and captured mail
    When a verified guest requests a reading and retries the same command
    Then one pending booking exists and unrelated guests cannot read it
    When the owner approves it using password and TOTP authentication
    Then it is confirmed and unpaid with a private actual-session calendar
    When a verified external receipt and eligible cancellation are recorded
    Then the appointment is cancelled and the money requires refund follow-up

  @api @concurrency
  Scenario: Twenty customers compete across two backend processes
    Given an isolated migrated PostgreSQL stack with two backends and captured mail
    When twenty verified customers request the same time together
    Then exactly one request succeeds with one occupancy and submission charge

  @browser
  Scenario: Responsive guest and owner operation
    Given an isolated migrated PostgreSQL stack with two backends and captured mail
    When a guest requests and the owner approves using the selected browser and viewport
    Then the browser journey has no horizontal overflow or serious accessibility violations

  @performance
  Scenario: Controlled mobile browser performance
    Given an isolated migrated PostgreSQL stack with two backends and captured mail
    When nine cold mobile browser runs are measured under the specified lab limits
    Then the browser performance budgets pass

  @load
  Scenario: Twenty-user measured HTTP load
    Given an isolated migrated PostgreSQL stack with two backends and captured mail
    When twenty virtual users run two minutes of warm-up and five measured minutes
    Then the HTTP budgets and database integrity checks pass
