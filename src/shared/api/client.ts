import { API_BASE_URL, API_TIMEOUT } from './config';
import { normalizeApiError } from './errorHandler';

interface RequestOptions extends RequestInit {
 params?: Record<string, string | number>;
 timeout?: number;
 retries?: number;
 retryDelay?: number;
}

class HttpClient {
 private getAuthHeader(): Record<string, string> {
 const token = localStorage.getItem('validexia_auth_token');
 return token ? { Authorization: `Bearer ${token}` } : {};
 }

 private getTenantHeader(): Record<string, string> {
 // Tenant selection is per-request: the backend re-verifies membership for
 // the X-Tenant-Id header on every call (ClerkAuthenticationFilter). The
 // value is established from GET /session by shared/api/session.ts.
 const tenantId = localStorage.getItem('validexia_tenant_id');
 return tenantId ? { 'X-Tenant-Id': tenantId } : {};
 }

 private async handleResponse<T>(response: Response): Promise<T> {
 if (response.status === 401) {
 localStorage.removeItem('validexia_auth_token');
 window.dispatchEvent(new CustomEvent('auth:session-expired'));
 }

 if (!response.ok) {
 let errorData;
 try {
 errorData = await response.json();
 } catch {
 errorData = { message: response.statusText };
 }
 throw { status: response.status, data: errorData };
 }

 if (response.status === 204) {
 return {} as T;
 }

 return response.json();
 }

 async request<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
 const { params, timeout = API_TIMEOUT, retries = 1, retryDelay = 1000, ...fetchOptions } = options;

 let url = `${API_BASE_URL}${endpoint}`;
 if (params) {
 const searchParams = new URLSearchParams();
 Object.entries(params).forEach(([key, val]) => searchParams.append(key, String(val)));
 url += `?${searchParams.toString()}`;
 }

 const headers: Record<string, string> = {
 'Content-Type': 'application/json',
 'Accept': 'application/json',
 ...this.getAuthHeader(),
 ...this.getTenantHeader(),
 ...(fetchOptions.headers as Record<string, string>),
 };

 let attempt = 0;
 while (true) {
 const controller = new AbortController();
 const timer = setTimeout(() => controller.abort(), timeout);
 // Respect caller's signal: abort our controller if they abort theirs
 const callerSignal = (fetchOptions as RequestInit).signal;
 if (callerSignal) {
 if (callerSignal.aborted) controller.abort();
 else callerSignal.addEventListener('abort', () => controller.abort(), { once: true });
 }

 try {
 const response = await fetch(url, {
 ...fetchOptions,
 headers,
 signal: controller.signal,
 });

 clearTimeout(timer);
 return await this.handleResponse<T>(response);
 } catch (error) {
 clearTimeout(timer);
 attempt++;

 const normalized = normalizeApiError(error);
 const isClientError = normalized.status >= 400 && normalized.status < 500;
 
 if (attempt >= retries || isClientError || normalized.code === 'REQUEST_CANCELLED') {
 throw normalized;
 }

 await new Promise((resolve) => setTimeout(resolve, retryDelay * attempt));
 }
 }
 }

 get<T>(endpoint: string, options?: RequestOptions) {
 return this.request<T>(endpoint, { ...options, method: 'GET' });
 }

 post<T>(endpoint: string, body?: unknown, options?: RequestOptions) {
 return this.request<T>(endpoint, { ...options, method: 'POST', body: JSON.stringify(body) });
 }

 put<T>(endpoint: string, body?: unknown, options?: RequestOptions) {
 return this.request<T>(endpoint, { ...options, method: 'PUT', body: JSON.stringify(body) });
 }

 delete<T>(endpoint: string, options?: RequestOptions) {
 return this.request<T>(endpoint, { ...options, method: 'DELETE' });
 }
}

export const api = new HttpClient();