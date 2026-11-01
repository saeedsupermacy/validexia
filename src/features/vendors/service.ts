// src/features/vendors/service.ts
import { vendorsApi, VendorDTO } from './api';
import { getDevFixtureVendors } from '../../fixtures/DEV_FIXTURE_vendors';

// Toggle this via environment variable or backend readiness flag
const USE_MOCK_FIXTURES = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true';

export async function fetchVendorsList(signal?: AbortSignal): Promise<VendorDTO[]> {
 if (USE_MOCK_FIXTURES) {
 // Return clearly isolated development fixture data
 console.warn('[DEV MODE] Serving isolated mock vendor fixtures.');
 return Promise.resolve(getDevFixtureVendors());
 }

 // Production call to PHP backend JSON API
 return vendorsApi.getVendors(signal);
}