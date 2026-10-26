export * from './administrator.service';
import { AdministratorService } from './administrator.service';
export * from './catalog.service';
import { CatalogService } from './catalog.service';
export * from './guest.service';
import { GuestService } from './guest.service';
export * from './session.service';
import { SessionService } from './session.service';
export const APIS = [AdministratorService, CatalogService, GuestService, SessionService];
