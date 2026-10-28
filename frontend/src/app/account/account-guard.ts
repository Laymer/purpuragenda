import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionStateRoleEnum } from '../shared/generated';
import { SessionAccess } from '../shared/access/session-access';

/** Navigation convenience; persisted account ownership is checked by every backend request. */
export const accountGuard: CanActivateFn = async () => {
  const sessions = inject(SessionAccess), router = inject(Router);
  try {
    const state = sessions.state() ?? await sessions.bootstrap();
    if (state.role === SessionStateRoleEnum.Account) return true;
  } catch { /* Expired or unavailable context requires an explicit sign-in check. */ }
  return router.parseUrl('/account/login');
};
