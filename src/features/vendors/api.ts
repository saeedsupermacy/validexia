import { api } from '../../shared/api/client';

/**
 * A vendor exactly as the REST API returns it (VendorView in VendorController).
 *
 * The vendors slice owns identity + lifecycle only (migration V12). Risk score, risk level,
 * evidence status, assessment state, open issues and monitoring state are owned by the
 * assessments/evidence/monitoring slices — they are deliberately absent here.
 */
export interface VendorDTO {
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
  criticality: string;
  ownerUserId: string | null;
  status: string;
  contractStart: string | null;
  contractEnd: string | null;
  tieringComplete: boolean;
  createdAt?: string;
  updatedAt?: string;
}

/** POST /vendors — `code` is required; `criticality` defaults to Tier 3 server-side. */
export interface CreateVendorRequest {
  name: string;
  legalName?: string | null;
  lei?: string | null;
  jurisdiction?: string | null;
  code: string;
  category?: string | null;
  domain?: string | null;
  website?: string | null;
  description?: string | null;
  criticality?: string | null;
  ownerUserId?: string | null;
  contractStart?: string | null;
  contractEnd?: string | null;
  tieringComplete: boolean;
}

/** PUT /vendors/{id} — full replace; unlike create, `criticality` is required here. */
export interface UpdateVendorRequest {
  name: string;
  legalName?: string | null;
  lei?: string | null;
  jurisdiction?: string | null;
  category?: string | null;
  domain?: string | null;
  website?: string | null;
  description?: string | null;
  criticality: string;
  ownerUserId?: string | null;
  contractStart?: string | null;
  contractEnd?: string | null;
  tieringComplete: boolean;
}

export interface DependentSystemInput {
  name: string;
  dataHandled?: string | null;
  residency?: string | null;
}

export interface DependentSystemDTO {
  id: string;
  name: string;
  dataHandled: string | null;
  residency: string | null;
}

export const vendorsApi = {
  getVendors: (signal?: AbortSignal) =>
    api.get<VendorDTO[]>('/vendors', { signal }),

  getVendorById: (id: string, signal?: AbortSignal) =>
    api.get<VendorDTO>(`/vendors/${id}`, { signal }),

  createVendor: (input: CreateVendorRequest) =>
    api.post<VendorDTO>('/vendors', input),

  updateVendor: (id: string, input: UpdateVendorRequest) =>
    api.put<VendorDTO>(`/vendors/${id}`, input),

  terminateVendor: (id: string) =>
    api.post<VendorDTO>(`/vendors/${id}/terminate`),

  listDependentSystems: (id: string, signal?: AbortSignal) =>
    api.get<DependentSystemDTO[]>(`/vendors/${id}/dependent-systems`, { signal }),

  replaceDependentSystems: (id: string, systems: DependentSystemInput[]) =>
    api.put<DependentSystemDTO[]>(`/vendors/${id}/dependent-systems`, systems),
};
