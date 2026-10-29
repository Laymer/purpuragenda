import { Router } from '@angular/router';
import { SessionAccess } from '../shared/access/session-access';

const RETURN = /^\/admin(\/reconciliation|\/notifications|\/customers|\/audit|\/calendar|\/policy|\/services|\/bookings\/(new|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(\/(reschedule|finance|history))?))?$/;

/** Only same-origin administrator pages are valid return targets; anything else falls back to the agenda. */
export function safeReturn(value: string | null | undefined): string { return typeof value === 'string' && RETURN.test(value) ? value : '/admin'; }

/**
 * A 403 means the server no longer accepts this session for administrator work (for example the fresh-authenticator
 * window ended). Drop the browser session so the sign-in form is shown, then return to the page afterwards.
 */
export async function reauthenticate(sessions: SessionAccess, router: Router, returnTo: string): Promise<void> {
  sessions.invalidate();
  await router.navigate(['/admin/login'], { queryParams: { reason: 'reauth', return: safeReturn(returnTo) } }).catch(() => false);
}
