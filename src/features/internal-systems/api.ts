// src/features/internal-systems/api.ts
import { api } from '../../shared/api/client';

/**
 * Real backend contract — mirrors InternalSystemsController (backend,
 * /api/v1/internal-systems) field for field (M1, U1 freeze v1.4.1 §5).
 * Every response carries activeGrantCount: the number of the system's
 * access grants whose lifecycle is Active, computed server-side.
 */

export type ApiSystemStatus = 'Active' | 'Retired';

export interface ApiInternalSystem {
 id: string;
 name: string;
 description: string | null;
 ownerUserId: string | null;
 status: ApiSystemStatus;
 retiredByUserId: string | null;
 /** ISO instant or null. */
 retiredAt: string | null;
 activeGrantCount: number;
 /** ISO instant. */
 createdAt: string;
 /** ISO instant. */
 updatedAt: string;
}

/**
 * POST and PUT body — one shape for both: PUT is a full replacement, so an
 * omitted optional field is sent as null and CLEARS the stored value
 * (freeze §5.1). No body ever carries an actor id or a lifecycle field:
 * identity comes from the session, lifecycle from the dedicated endpoints.
 */
export interface InternalSystemPayload {
 name: string;
 description: string | null;
 ownerUserId: string | null;
}

export const internalSystemsApi = {
 list: (signal?: AbortSignal) => api.get<ApiInternalSystem[]>('/internal-systems', { signal }),

 getById: (id: string, signal?: AbortSignal) =>
 api.get<ApiInternalSystem>(`/internal-systems/${id}`, { signal }),

 create: (payload: InternalSystemPayload) =>
 api.post<ApiInternalSystem>('/internal-systems', payload),

 update: (id: string, payload: InternalSystemPayload) =>
 api.put<ApiInternalSystem>(`/internal-systems/${id}`, payload),

 retire: (id: string) => api.post<ApiInternalSystem>(`/internal-systems/${id}/retire`),

 restore: (id: string) => api.post<ApiInternalSystem>(`/internal-systems/${id}/restore`),
};
