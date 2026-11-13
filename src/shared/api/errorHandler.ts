import { NormalizedApiError, ApiErrorResponse } from './config';

export function normalizeApiError(error: unknown): NormalizedApiError {
 if (error instanceof DOMException && error.name === 'AbortError') {
 return {
 status: 499,
 code: 'REQUEST_CANCELLED',
 message: 'The request was cancelled by the client.',
 };
 }

 if (error instanceof TypeError && error.message.includes('NetworkError')) {
 return {
 status: 503,
 code: 'NETWORK_UNAVAILABLE',
 message: 'Unable to reach the server. Please check your network connection.',
 };
 }

 if (typeof error === 'object' && error !== null && 'status' in error) {
 const err = error as { status: number; data?: ApiErrorResponse };
 return {
 status: err.status,
 // The real backend's error body is {code, message} (ApiErrorHandler);
 // error_code is kept for older payloads that used that key.
 code: err.data?.code || err.data?.error_code || 'HTTP_ERROR',
 message: err.data?.message || `Request failed with status ${err.status}`,
 details: err.data?.details,
 };
 }

 return {
 status: 500,
 code: 'UNKNOWN_ERROR',
 message: error instanceof Error ? error.message : 'An unexpected error occurred.',
 };
}