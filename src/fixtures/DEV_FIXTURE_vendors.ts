// Legacy dev adapter: map the demo vendor registry onto the REST DTO shape.
// The vendors workflow reads the API directly; this is retained for demo tooling only.
import type { VendorDTO } from '../features/vendors/api';
import { getVendors } from '../data/vendors';

export function getDevFixtureVendors(): VendorDTO[] {
  return getVendors().map(vendor => ({
    id: vendor.id,
    name: vendor.name,
    legalName: null,
    lei: null,
    jurisdiction: null,
    code: vendor.code,
    category: vendor.category ?? null,
    domain: vendor.domain ?? vendor.website ?? null,
    website: vendor.website ?? null,
    description: vendor.description ?? null,
    criticality: vendor.criticality,
    ownerUserId: null,
    status: vendor.status,
    contractStart: vendor.contractStart ?? null,
    contractEnd: vendor.contractEnd ?? null,
    tieringComplete: vendor.tieringComplete ?? false,
  }));
}
