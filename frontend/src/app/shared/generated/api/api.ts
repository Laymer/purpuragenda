export * from './catalog.service';
import { CatalogService } from './catalog.service';
export * from './guest.service';
import { GuestService } from './guest.service';
export * from './session.service';
import { SessionService } from './session.service';
export const APIS = [CatalogService, GuestService, SessionService];
