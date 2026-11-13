export interface ApiErrorResponse {
 error_code: string;
 message: string;
 details?: Record<string, string[]>;
}

export interface NormalizedApiError {
 status: number;
 code: string;
 message: string;
 details?: Record<string, string[]>;
}

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1';
export const API_TIMEOUT = Number(import.meta.env.VITE_API_TIMEOUT) || 15000;

/**
 * Per-request tenant scope. The vendor API (and every authenticated endpoint) requires an
 * `X-Tenant-Id` header — the auth filter re-verifies membership on every request. Resolve it
 * from the environment first, then from the tenant chosen at sign-in.
 */
export function getTenantId(): string {
  const fromEnv = import.meta.env.VITE_TENANT_ID;
  if (fromEnv) return fromEnv;
  try {
    return localStorage.getItem('validexia_tenant_id') || '';
  } catch {
    return '';
  }
}