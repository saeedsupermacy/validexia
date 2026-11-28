// src/features/access-grants/api.ts
import { api } from '../../shared/api/client';

/**
 * Real backend contract — mirrors AccessGrantsController (backend,
 * /api/v1/access-grants) field for field (M1, U1 freeze v1.4.1 §5).
 * A grant carries FIVE independent axes — privilege level, access method,
 * source kind, lifecycle status, verification status — which are never
 * merged into a single status. Evidence endpoints exist on the same
 * controller but are deliberately NOT part of this slice.
 */

export type ApiPrivilegeLevel = 'Read' | 'Write' | 'Admin';
export type ApiAccessMethod = 'Direct Login' | 'API' | 'Network' | 'File Exchange';
export type ApiSourceKind = 'Customer Entered' | 'Vendor Provided' | 'Unknown';
export type ApiLifecycleStatus = 'Active' | 'Suspended' | 'Revoked';
export type ApiVerificationStatus = 'Unverified' | 'Confirmed' | 'Rejected';

export const PRIVILEGE_LEVELS: ApiPrivilegeLevel[] = ['Read', 'Write', 'Admin'];
export const ACCESS_METHODS: ApiAccessMethod[] = ['Direct Login', 'API', 'Network', 'File Exchange'];
export const SOURCE_KINDS: ApiSourceKind[] = ['Customer Entered', 'Vendor Provided', 'Unknown'];
export const LIFECYCLE_STATUSES: ApiLifecycleStatus[] = ['Active', 'Suspended', 'Revoked'];
export const VERIFICATION_STATUSES: ApiVerificationStatus[] = ['Unverified', 'Confirmed', 'Rejected'];

export interface ApiAccessGrant {
 id: string;
 vendorId: string;
 internalSystemId: string;
 privilegeLevel: ApiPrivilegeLevel;
 accessMethod: ApiAccessMethod;
 sourceKind: ApiSourceKind;
 lifecycleStatus: ApiLifecycleStatus;
 verificationStatus: ApiVerificationStatus;
 createdByUserId: string;
 verifiedByUserId: string | null;
 /** ISO instant or null. */
 verifiedAt: string | null;
 rejectionReason: string | null;
 suspendedByUserId: string | null;
 /** ISO instant or null. */
 suspendedAt: string | null;
 suspensionReason: string | null;
 reinstatedByUserId: string | null;
 /** ISO instant or null. */
 reinstatedAt: string | null;
 revokedByUserId: string | null;
 /** ISO instant or null. */
 revokedAt: string | null;
 revocationReason: string | null;
 discoveryNote: string | null;
 /** ISO instant. */
 createdAt: string;
 /** ISO instant. */
 updatedAt: string;
}

/** POST /access-grants body. No actor, status, or timestamp fields exist —
 *  identity comes from the session and the axes are set by the server. */
export interface GrantCreatePayload {
 vendorId: string;
 internalSystemId: string;
 privilegeLevel: ApiPrivilegeLevel;
 accessMethod: ApiAccessMethod;
 sourceKind: ApiSourceKind;
}

/** POST /access-grants/discoveries body: the create fields plus the
 *  mandatory discovery note. A discovered grant is born Revoked. */
export interface GrantDiscoveryPayload extends GrantCreatePayload {
 discoveryNote: string;
}

/** Server-side list filters (GET /access-grants query parameters). */
export interface GrantFilters {
 vendorId?: string;
 internalSystemId?: string;
 lifecycleStatus?: ApiLifecycleStatus;
 verificationStatus?: ApiVerificationStatus;
}

export const accessGrantsApi = {
 list: (filters: GrantFilters = {}, signal?: AbortSignal) => {
 const params: Record<string, string> = {};
 if (filters.vendorId) params.vendorId = filters.vendorId;
 if (filters.internalSystemId) params.internalSystemId = filters.internalSystemId;
 if (filters.lifecycleStatus) params.lifecycleStatus = filters.lifecycleStatus;
 if (filters.verificationStatus) params.verificationStatus = filters.verificationStatus;
 return api.get<ApiAccessGrant[]>('/access-grants', { params, signal });
 },

 getById: (id: string, signal?: AbortSignal) =>
 api.get<ApiAccessGrant>(`/access-grants/${id}`, { signal }),

 create: (payload: GrantCreatePayload) => api.post<ApiAccessGrant>('/access-grants', payload),

 recordDiscovery: (payload: GrantDiscoveryPayload) =>
 api.post<ApiAccessGrant>('/access-grants/discoveries', payload),

 confirm: (id: string) => api.post<ApiAccessGrant>(`/access-grants/${id}/confirm`),

 reject: (id: string, reason: string) =>
 api.post<ApiAccessGrant>(`/access-grants/${id}/reject`, { reason }),

 suspend: (id: string, reason: string) =>
 api.post<ApiAccessGrant>(`/access-grants/${id}/suspend`, { reason }),

 reinstate: (id: string) => api.post<ApiAccessGrant>(`/access-grants/${id}/reinstate`),

 revoke: (id: string, reason: string) =>
 api.post<ApiAccessGrant>(`/access-grants/${id}/revoke`, { reason }),
};
