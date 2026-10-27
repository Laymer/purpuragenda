# Purpuragenda user's manual

This manual explains how to use Purpuragenda 1.0.0 as a guest, a customer with a booking, and the administrator. To install, start and stop the application, see the [README](../README.md).

1.0.0 is a local release. The application runs at http://localhost:8080 and sends no real email. Every message (verification links, booking updates) is captured by Mailpit at http://localhost:8025, where you open it as if it were your inbox. All times are business times in Europe/Brussels unless a page shows another time zone.

## Booking a consultation (guests)

No account is needed. Use one browser from start to finish, because the verification link only works in the browser that asked for it.

1. Open http://localhost:8080/book. The page **Request a consultation** opens.
2. Choose a consultation, set the date range, and press **Show available times**. Pick a time, check **Your selection** and press **Continue**. The time is not reserved yet; it is checked again when you submit.
3. Under **Verify your email**, enter an address where you can receive booking updates and press **Send verification link**.
4. Open the message in Mailpit and follow its link in the same browser. Press **Verify email**. The link can be used once.
5. Return to the booking page and press **I have verified my email**.
6. Under **Your consultation details**, enter your first and last name, telephone, birth date, birth time and birthplace, and an optional message. Birth date and time are taken exactly as you enter them, and no birth time zone is inferred. Press **Review request**.
7. Under **Review your request**, check the details and the terms, tick the agreement and press **Submit booking request**.

The page **Your booking** shows your booking reference and the status "pending confirmation". Keep the reference, because you need it to manage the booking. You also receive a "Booking request received" message.

Your details stay on the page only until you submit. A page refresh or an expired session clears the form. If a submission result is uncertain, use **Retry same submission** and do not start a new request.

### What happens next

A request stays pending until the practitioner confirms it. A pending hold lasts a limited number of hours and ends before the consultation; the review step shows the exact limits. If the practitioner does not confirm in time, the request expires and the time becomes available again.

When the booking is confirmed, you receive a "Booking confirmed" message with the Zoom meeting link. On the booking page, **Refresh booking status** shows the new status and an **Open meeting link** button.

## Managing your booking (customers)

1. Open http://localhost:8080/manage. The page **Manage your booking** opens.
2. Enter your booking reference and the email address you used. If they match, a one-time link is sent to that address.
3. Open the link from Mailpit in the same browser. The page **Your booking** shows the status, the time and, once confirmed, the meeting link.

From there you can:

- To cancel, press **Cancel this booking**, then confirm with **Yes, cancel this booking**, or go back with **Keep my booking**.
- To move to another time, press **Change time** and choose a date range of at most 31 dates. The time zone is always shown. Pick a new time, press **Review new time**, and confirm with **Yes, move my booking**, or go back with **Keep my current time**.

Changes are possible only before the change cutoff. If no times are offered, the change window may have closed and the booking can no longer be moved. If the booking changed in the meantime, for example because the practitioner confirmed it, the page asks you to reload and review it again.

## Administration (practitioner)

The administrator account is created once on the host with `./purpuragenda.sh admin`. It sets a password and an authenticator app (TOTP). There is no public registration.

### Signing in and out

1. Open http://localhost:8080/admin/login. The page **Administrator sign in** opens.
2. Enter the email address, the password and a fresh code from the authenticator app, then press **Sign in**.
3. After repeated failures, the page says **Please wait before trying again.** Wait before retrying.
4. Press **Sign out** when you are done.

Sensitive actions require a recent sign-in. If your session is too old, the application asks you to sign in again and then returns to the page you were on.

If you lose access to the password or the authenticator, use the private administrator recovery procedure on the host (docs/admin-recovery.md). Recovery allows enrolling again; signing in still requires a fresh authenticator code.

### Agenda and pending requests

The **Agenda** page shows bookings between two Brussels dates (at most 31 dates). Use **Today** or **This week**, or set **From (Brussels date)** and **Through (Brussels date)** and press **Show**. Requests awaiting confirmation are listed separately with their count. Use **Load more** for long lists.

### Approving a booking

1. In the agenda, open a booking by its reference.
2. Check the **Customer details** and the requested time.
3. Paste the meeting link into **Zoom meeting link**. It must be an https link on zoom.us, like https://zoom.us/j/123456789.
4. Press **Approve booking**. The status becomes CONFIRMED and the customer receives the confirmation with the link.

Approval only works while the request's hold is still live and the consultation is in the future. If the result of an approval is uncertain, use **Retry the same approval**.

Not available in 1.0.0: rejecting a request from the interface, recording payments, editing services or schedules, customer accounts, privacy tools and calendar exports. See STATUS.md for what is planned.

## Troubleshooting

- A verification or management link does not work: Open it in the same browser that asked for it. Each link works once and expires; request a new one.
- No message arrives: Check Mailpit at http://localhost:8025 and make sure the stack is running (`./purpuragenda.sh status`).
- The page does not load: Use http://localhost:8080, not 127.0.0.1, and run `./purpuragenda.sh smoke`.
- Sign-in keeps failing: Check the device clock that runs the authenticator app, and wait for a new code before trying again.

Never run `docker compose down -v` and never delete `./.secrets`. Doing so destroys the bookings and the keys that protect them.
