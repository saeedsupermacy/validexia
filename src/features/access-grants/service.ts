// src/features/access-grants/service.ts
import {
 accessGrantsApi,
 type ApiAccessGrant,
 type GrantCreatePayload,
 type GrantDiscoveryPayload,
 type GrantFilters,
} from './api';
import { vendorsApi, type ApiVendor } from '../vendors/api';
import { internalSystemsApi, type ApiInternalSystem } from '../internal-systems/api';
import { ensureTenantContext, type TenantContext } from '../../shared/api/session';
import { normalizeApiError } from '../../shared/api/errorHandler';

/**
 * Adapter seam between the Access Grants UI and the real backend.
 *
 * Like the internal-systems inventory, this workspace has NO demo fallback:
 * vendors, systems, and grants all come from the tenant's real API records,
 * and a failed load surfaces as a retryable error state. Mutations resolve
 * with the server's returned view; the UI never simulates a transition.
 */

export interface GrantsResult {
 grants: ApiAccessGrant[];
 tenant: TenantContext;
}

export interface ReferenceData {
 vendors: ApiVendor[];
 systems: ApiInternalSystem[];
}

/** Short, honest label for a user UUID — never an invented name. */
export function userLabel(userId: string | null): string {
 if (!userId) return '—';
 return `User ${userId.slice(0, 8)}…`;
}

/** Whether the session's tenant role may confirm/reject (owner or admin).
 *  A usability aid only — the backend enforces the rule on every call. */
export function canModerate(tenant: TenantContext | null): boolean {
 const role = (tenant?.role ?? '').toLowerCase();
 return role === 'owner' || role === 'admin';
}

/** Load grants from the real API, with the controller's server-side filters. */
export async function loadAccessGrants(
 filters: GrantFilters = {},
 signal?: AbortSignal,
): Promise<GrantsResult> {
 const tenant = await ensureTenantContext(signal);
 const grants = await accessGrantsApi.list(filters, signal);
 return { grants, tenant };
}

/** Vendors and internal systems for selectors and name resolution — the
 *  tenant's actual records only, from their own real endpoints. */
export async function loadReferenceData(signal?: AbortSignal): Promise<ReferenceData> {
 await ensureTenantContext(signal);
 const [vendors, systems] = await Promise.all([
 vendorsApi.list(signal),
 internalSystemsApi.list(signal),
 ]);
 return { vendors, systems };
}

export async function createAccessGrant(payload: GrantCreatePayload): Promise<ApiAccessGrant> {
 await ensureTenantContext();
 return accessGrantsApi.create(payload);
}

export async function recordDiscoveredGrant(payload: GrantDiscoveryPayload): Promise<ApiAccessGrant> {
 await ensureTenantContext();
 return accessGrantsApi.recordDiscovery(payload);
}

export async function confirmAccessGrant(id: string): Promise<ApiAccessGrant> {
 await ensureTenantContext();
 return accessGrantsApi.confirm(id);
}

export async function rejectAccessGrant(id: string, reason: string): Promise<ApiAccessGrant> {
 await ensureTenantContext();
 return accessGrantsApi.reject(id, reason);
}

export async function suspendAccessGrant(id: string, reason: string): Promise<ApiAccessGrant> {
 await ensureTenantContext();
 return accessGrantsApi.suspend(id, reason);
}

export async function reinstateAccessGrant(id: string): Promise<ApiAccessGrant> {
 await ensureTenantContext();
 return accessGrantsApi.reinstate(id);
}

export async function revokeAccessGrant(id: string, reason: string): Promise<ApiAccessGrant> {
 await ensureTenantContext();
 return accessGrantsApi.revoke(id, reason);
}

/** User-facing message for any API failure (the backend's messages are accurate). */
export function apiErrorMessage(error: unknown): string {
 return normalizeApiError(error).message;
}
