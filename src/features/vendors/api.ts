// src/features/vendors/api.ts
import { api } from '../../shared/api/client';

/**
 * Real backend contract — mirrors VendorController (backend, /api/v1/vendors)
 * field for field. The API deliberately has NO riskScore/riskLevel: scores
 * belong to the assessments slice and the (still open) scoring decision, so
 * nothing here invents them.
 */

export type ApiCriticality = 'Tier 1' | 'Tier 2' | 'Tier 3';
export type ApiVendorStatus = 'Active' | 'Terminated';

export interface ApiVendor {
 id: string;
 name: string;
 legalName: string | null;
 lei: string | null;
 jurisdiction: string | null;
 code: string;
 category: string | null;
 domain: string | null;
 website: string | null;
 description: string | null;
 criticality: ApiCriticality;
 ownerUserId: string | null;
 status: ApiVendorStatus;
 /** ISO date (yyyy-mm-dd) or null. */
 contractStart: string | null;
 /** ISO date (yyyy-mm-dd) or null. */
 contractEnd: string | null;
 tieringComplete: boolean;
}

export interface ApiDependentSystem {
 id: string;
 name: string;
 dataHandled: string | null;
 residency: string | null;
}

export interface DependentSystemInput {
 name: string;
 dataHandled: string | null;
 residency: string | null;
}

/** POST /vendors body. criticality may be omitted (database defaults Tier 3);
 *  tieringComplete is a primitive boolean server-side and must be sent. */
export interface VendorCreatePayload {
 name: string;
 legalName: string | null;
 lei: string | null;
 jurisdiction: string | null;
 code: string;
 category: string | null;
 domain: string | null;
 website: string | null;
 description: string | null;
 criticality: ApiCriticality | null;
 ownerUserId: string | null;
 contractStart: string | null;
 contractEnd: string | null;
 tieringComplete: boolean;
}

/** PUT /vendors/{id} body: full replace, criticality required, code immutable
 *  (it is not part of the update contract at all). */
export interface VendorUpdatePayload {
 name: string;
 legalName: string | null;
 lei: string | null;
 jurisdiction: string | null;
 category: string | null;
 domain: string | null;
 website: string | null;
 description: string | null;
 criticality: ApiCriticality;
 ownerUserId: string | null;
 contractStart: string | null;
 contractEnd: string | null;
 tieringComplete: boolean;
}

export const vendorsApi = {
 list: (signal?: AbortSignal) => api.get<ApiVendor[]>('/vendors', { signal }),

 getById: (id: string, signal?: AbortSignal) => api.get<ApiVendor>(`/vendors/${id}`, { signal }),

 create: (payload: VendorCreatePayload) => api.post<ApiVendor>('/vendors', payload),

 update: (id: string, payload: VendorUpdatePayload) => api.put<ApiVendor>(`/vendors/${id}`, payload),

 terminate: (id: string) => api.post<ApiVendor>(`/vendors/${id}/terminate`),

 listDependentSystems: (id: string, signal?: AbortSignal) =>
 api.get<ApiDependentSystem[]>(`/vendors/${id}/dependent-systems`, { signal }),

 replaceDependentSystems: (id: string, systems: DependentSystemInput[]) =>
 api.put<ApiDependentSystem[]>(`/vendors/${id}/dependent-systems`, systems),
};
