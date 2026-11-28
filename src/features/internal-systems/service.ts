// src/features/internal-systems/service.ts
import { internalSystemsApi, type ApiInternalSystem, type InternalSystemPayload } from './api';
import { ensureTenantContext, type TenantContext } from '../../shared/api/session';
import { normalizeApiError } from '../../shared/api/errorHandler';

/**
 * Adapter seam between the Internal Systems UI and the real backend.
 *
 * Unlike the vendors portfolio — a pre-existing demo-first page that keeps a
 * clearly labelled fixture fallback — the internal-systems inventory has no
 * demo data and no fallback: the backend is the only source of truth, and a
 * failed load surfaces as an error state the user can retry. Nothing here
 * invents systems, owners, or grant counts the API did not return.
 */

export interface SystemsResult {
 systems: ApiInternalSystem[];
 tenant: TenantContext;
}

/** Short, honest owner label for a user UUID — never an invented name. */
export function ownerLabel(ownerUserId: string | null): string {
 if (!ownerUserId) return 'Unassigned';
 return `Owner ${ownerUserId.slice(0, 8)}…`;
}

/** Load the tenant's internal-systems inventory from the real API. */
export async function loadInternalSystems(signal?: AbortSignal): Promise<SystemsResult> {
 const tenant = await ensureTenantContext(signal);
 const systems = await internalSystemsApi.list(signal);
 return { systems, tenant };
}

/** Create a system on the real API; resolves with the created view. */
export async function createInternalSystem(payload: InternalSystemPayload): Promise<ApiInternalSystem> {
 await ensureTenantContext();
 return internalSystemsApi.create(payload);
}

/** Full-replace update on the real API; resolves with the updated view. */
export async function updateInternalSystem(id: string, payload: InternalSystemPayload): Promise<ApiInternalSystem> {
 await ensureTenantContext();
 return internalSystemsApi.update(id, payload);
}

/** Retire on the real API (refused by the backend unless the system is Active). */
export async function retireInternalSystem(id: string): Promise<ApiInternalSystem> {
 await ensureTenantContext();
 return internalSystemsApi.retire(id);
}

/** Restore on the real API (refused by the backend unless the system is Retired). */
export async function restoreInternalSystem(id: string): Promise<ApiInternalSystem> {
 await ensureTenantContext();
 return internalSystemsApi.restore(id);
}

/** User-facing message for any API failure (the backend's messages are accurate). */
export function apiErrorMessage(error: unknown): string {
 return normalizeApiError(error).message;
}

/** The backend's stable error code for a failure, when it carries one. */
export function apiErrorCode(error: unknown): string {
 return normalizeApiError(error).code;
}
