// src/features/vendors/service.ts
//
// Mapping layer between the REST API's VendorView and the UI's VendorRecord.
// The real workflow always goes through the API — there is no fixture fallback.
import { vendorsApi, type VendorDTO, type CreateVendorRequest } from './api';
import type { BusinessCriticality, VendorRecord } from '../../data/vendors';
import { riskLevelForScore } from '../../data/vendors';

const CRITICALITIES: readonly BusinessCriticality[] = ['Tier 1', 'Tier 2', 'Tier 3'];

export interface VendorCreateInput {
  name: string;
  criticality: BusinessCriticality;
  owner?: string;
  website?: string;
  description?: string;
  tieringComplete?: boolean;
}

function normalizeCriticality(value: string | null | undefined): BusinessCriticality {
  return CRITICALITIES.includes(value as BusinessCriticality)
    ? (value as BusinessCriticality)
    : 'Tier 3';
}

/** Format an API timestamp the way the table's "Last Material Change" column expects. */
function formatTimestamp(iso?: string): string {
  if (!iso) return '—';
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return '—';
  const time = at.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  if (at.toDateString() === new Date().toDateString()) return `Today, ${time}`;
  return `${at.toLocaleDateString([], { month: 'short', day: 'numeric' })}, ${time}`;
}

/**
 * Map a REST VendorView onto the UI's VendorRecord.
 *
 * Risk score, evidence status, assessment state, open issues and monitoring are not part of
 * the vendors slice, so an API vendor starts at an honest "not assessed" baseline rather than
 * borrowing fixture values. As the assessment/evidence slices land, their fields override
 * these defaults at the source.
 */
export function toVendorRecord(dto: VendorDTO): VendorRecord {
  const riskScore = 0;
  return {
    id: dto.id,
    name: dto.name,
    code: dto.code,
    criticality: normalizeCriticality(dto.criticality),
    riskLevel: riskLevelForScore(riskScore),
    riskScore,
    evidenceStatus: 'Missing',
    assessmentStatus: 'Not Started',
    openIssues: 0,
    owner: dto.ownerUserId ?? 'Unassigned',
    monitoringStatus: dto.status === 'Terminated' ? 'Paused' : 'Active',
    lastMaterialChange: formatTimestamp(dto.updatedAt ?? dto.createdAt),
    website: dto.website?.trim() || dto.domain?.trim() || undefined,
    description: dto.description?.trim() || undefined,
    category: dto.category?.trim() || undefined,
    domain: dto.domain?.trim() || undefined,
    status: dto.status === 'Terminated' ? 'Terminated' : 'Active',
    inherentRisk: riskScore,
    residualRisk: riskScore,
    contractStart: dto.contractStart ?? undefined,
    contractEnd: dto.contractEnd ?? undefined,
    tieringComplete: dto.tieringComplete,
  };
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function randomCodeSuffix(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().slice(0, 8).toUpperCase();
  }
  return Math.random().toString(36).slice(2, 10).toUpperCase();
}

/**
 * Build the create request. `code` is required and unique per tenant, so the client generates
 * one when the form does not supply it. The owner field is a display name today; it is only
 * sent as `ownerUserId` when it already is a user UUID (the identity slice does not yet expose
 * a directory the UI could resolve names against).
 */
export function toCreateRequest(input: VendorCreateInput): CreateVendorRequest {
  const owner = input.owner?.trim();
  return {
    name: input.name.trim(),
    code: `VND-${randomCodeSuffix()}`,
    criticality: normalizeCriticality(input.criticality),
    website: input.website?.trim() || null,
    description: input.description?.trim() || null,
    ownerUserId: owner && UUID_PATTERN.test(owner) ? owner : null,
    tieringComplete: input.tieringComplete ?? false,
  };
}

/** Fetch the tenant's vendors, mapped to the UI record shape. */
export async function fetchVendors(signal?: AbortSignal): Promise<VendorRecord[]> {
  const dtos = await vendorsApi.getVendors(signal);
  return dtos.map(toVendorRecord);
}
