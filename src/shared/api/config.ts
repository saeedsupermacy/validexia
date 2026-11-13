export interface ApiErrorResponse {
 /** The real backend sends {code, message} on every error (ApiErrorHandler). */
 code?: string;
 error_code?: string;
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