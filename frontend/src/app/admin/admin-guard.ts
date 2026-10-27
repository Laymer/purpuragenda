import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionStateRoleEnum } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';

/** Routing convenience only: the server re-authorizes every administrator request. */
export const adminGuard: CanActivateFn = async () => {
  const sessions = inject(SessionAccess), router = inject(Router);
  try {
    const state = sessions.state() ?? await sessions.bootstrap();
    if (state.role === SessionStateRoleEnum.Admin) return true;
  } catch { /* An unavailable or expired session falls through to sign in. */ }
  return router.parseUrl('/admin/login');
};
