// src/shared/api/session.ts
import { api } from './client';

/**
 * Tenant context for the real backend.
 *
 * The backend authenticates with the bearer token the shared client already
 * sends, and selects the tenant per request via the X-Tenant-Id header —
 * membership is re-verified server-side on every call, so the header is a
 * selection, never a trust grant. GET /api/v1/session is the one endpoint
 * that works without a tenant header and tells us which tenants the signed-in
 * user actually belongs to; this module resolves that once and caches the
 * choice in localStorage (key read by shared/api/client.ts).
 */

export interface SessionMembership {
 tenantId: string;
 tenantName: string;
 role: string;
 status: string;
}

export interface SessionInfo {
 user: { id: string; subject: string };
 memberships: SessionMembership[];
 activeTenantId: string | null;
}

export interface TenantContext {
 tenantId: string;
 tenantName: string;
 role: string;
 userId: string;
}

const TENANT_KEY = 'validexia_tenant_id';

let cached: TenantContext | null = null;
let inflight: Promise<TenantContext> | null = null;

/** Resolve (once per page load) the tenant the real-API modules should use. */
export function ensureTenantContext(signal?: AbortSignal): Promise<TenantContext> {
 if (cached) return Promise.resolve(cached);
 if (inflight) return inflight;
 inflight = (async () => {
 const session = await api.get<SessionInfo>('/session', { signal });
 const memberships = session.memberships ?? [];
 // Prefer the session's active tenant when it is one of the memberships,
 // then any ACTIVE membership, then the first membership.
 const byId = (id: string | null) => memberships.find(m => m.tenantId === id);
 const chosen =
 byId(session.activeTenantId) ??
 memberships.find(m => m.status === 'ACTIVE') ??
 memberships[0];
 if (!chosen) {
 throw { status: 403, code: 'NO_TENANT', message: 'Your account is not a member of any tenant yet.' };
 }
 try { localStorage.setItem(TENANT_KEY, chosen.tenantId); } catch { /* storage unavailable */ }
 cached = {
 tenantId: chosen.tenantId,
 tenantName: chosen.tenantName,
 role: chosen.role,
 userId: session.user.id,
 };
 return cached;
 })();
 // A failed resolution must not poison later retries.
 inflight.catch(() => { inflight = null; });
 return inflight;
}

/** The tenant resolved by ensureTenantContext, if it has run successfully. */
export function currentTenantContext(): TenantContext | null {
 return cached;
}
