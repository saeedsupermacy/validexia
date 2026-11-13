// src/features/vendors/service.ts
import { vendorsApi, type ApiVendor } from './api';
import { ensureTenantContext, type TenantContext } from '../../shared/api/session';
import { normalizeApiError } from '../../shared/api/errorHandler';
import { getVendorsWithStatus, type VendorRecord } from '../../data/vendors';

/**
 * Adapter seam between the Vendors UI and the real backend.
 *
 * The UI keeps consuming VendorRecord rows, but live rows are mapped honestly
 * from the API contract: the API has no risk scores, evidence status,
 * assessment status or open-issue counts, so a live row is marked
 * source:'live' + assessmentStatus:'Not Started' and the UI renders
 * "Not assessed" / "—" for everything the API does not know. Placeholder
 * numbers exist only to satisfy the row type and are never displayed for
 * live rows.
 *
 * If the API cannot be reached (no session, network down, server error),
 * the portfolio falls back to the existing demo fixtures — clearly labelled
 * source:'demo' with the failure reason attached, never silently mixed.
 */

export type PortfolioSource = 'live' | 'demo';

export interface PortfolioResult {
 source: PortfolioSource;
 vendors: VendorRecord[];
 tenant: TenantContext | null;
 /** Why live data is not being shown (demo fallback only). */
 error?: string;
}

/** Short, honest owner label for a user UUID — never an invented name. */
export function ownerLabel(ownerUserId: string | null): string {
 if (!ownerUserId) return 'Unassigned';
 return `Owner ${ownerUserId.slice(0, 8)}…`;
}

export function apiVendorToRecord(v: ApiVendor): VendorRecord {
 return {
 id: v.id,
 name: v.name,
 code: v.code,
 source: 'live',
 legalName: v.legalName,
 lei: v.lei,
 jurisdiction: v.jurisdiction,
 ownerUserId: v.ownerUserId,
 criticality: v.criticality,
 // The API has no scoring yet — 'Not Started' is the truthful state and is
 // what makes the table render "Not assessed" instead of a score ring.
 assessmentStatus: 'Not Started',
 riskScore: 0,
 riskLevel: 'Low',
 inherentRisk: 0,
 residualRisk: 0,
 evidenceStatus: 'Missing',
 openIssues: 0,
 owner: ownerLabel(v.ownerUserId),
 monitoringStatus: '—',
 lastMaterialChange: '—',
 website: v.website ?? undefined,
 description: v.description ?? undefined,
 category: v.category ?? undefined,
 domain: v.domain ?? undefined,
 status: v.status,
 contractStart: v.contractStart ?? undefined,
 contractEnd: v.contractEnd ?? undefined,
 tieringComplete: v.tieringComplete,
 };
}

function demoPortfolio(error: string): PortfolioResult {
 return {
 source: 'demo',
 vendors: getVendorsWithStatus().map(v => ({ ...v, source: 'demo' as const })),
 tenant: null,
 error,
 };
}

/** Load the vendor portfolio: real API first, demo fixtures as a labelled fallback. */
export async function loadVendorPortfolio(signal?: AbortSignal): Promise<PortfolioResult> {
 try {
 const tenant = await ensureTenantContext(signal);
 const vendors = await vendorsApi.list(signal);
 return { source: 'live', vendors: vendors.map(apiVendorToRecord), tenant };
 } catch (error) {
 return demoPortfolio(normalizeApiError(error).message);
 }
}

/** Create a vendor on the real API; resolves with the created record. */
export async function createVendorOnApi(payload: Parameters<typeof vendorsApi.create>[0]): Promise<ApiVendor> {
 await ensureTenantContext();
 return vendorsApi.create(payload);
}

/** Full-replace update on the real API. */
export async function updateVendorOnApi(id: string, payload: Parameters<typeof vendorsApi.update>[1]): Promise<ApiVendor> {
 await ensureTenantContext();
 return vendorsApi.update(id, payload);
}

/** Terminate (idempotent server-side) on the real API. */
export async function terminateVendorOnApi(id: string): Promise<ApiVendor> {
 await ensureTenantContext();
 return vendorsApi.terminate(id);
}

/** Fetch one vendor from the real API; rejects with the normalized API error. */
export async function fetchVendorOnApi(id: string, signal?: AbortSignal): Promise<ApiVendor> {
 await ensureTenantContext(signal);
 return vendorsApi.getById(id, signal);
}

export async function fetchDependentSystemsOnApi(id: string, signal?: AbortSignal) {
 await ensureTenantContext(signal);
 return vendorsApi.listDependentSystems(id, signal);
}

export async function replaceDependentSystemsOnApi(id: string, systems: Parameters<typeof vendorsApi.replaceDependentSystems>[1]) {
 await ensureTenantContext();
 return vendorsApi.replaceDependentSystems(id, systems);
}

/** User-facing message for any API failure (the backend's 400 messages are accurate). */
export function apiErrorMessage(error: unknown): string {
 return normalizeApiError(error).message;
}
