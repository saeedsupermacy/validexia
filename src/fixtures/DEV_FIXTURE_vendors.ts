// Legacy API adapter: derive mock responses from the canonical vendor registry.
import type { VendorDTO } from '../features/vendors/api';
import { getVendors } from '../data/vendors';

export function getDevFixtureVendors(): VendorDTO[] {
  return getVendors().map(vendor => ({
    id: vendor.id,
    name: vendor.name,
    code: vendor.code,
    domain: vendor.domain ?? vendor.website ?? '',
    category: vendor.category ?? 'Uncategorized',
    riskScore: vendor.riskScore,
    riskLevel: vendor.riskLevel,
  }));
}