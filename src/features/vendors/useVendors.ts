// src/features/vendors/useVendors.ts
import { useCallback, useEffect, useRef, useState } from 'react';
import { vendorsApi } from './api';
import { fetchVendors, toCreateRequest, toVendorRecord, type VendorCreateInput } from './service';
import { normalizeApiError } from '../../shared/api/errorHandler';
import type { NormalizedApiError } from '../../shared/api/config';
import type { VendorRecord } from '../../data/vendors';

/** The four states the Vendors view can be in. */
export type VendorsStatus = 'loading' | 'error' | 'empty' | 'success';

export interface UseVendorsResult {
  vendors: VendorRecord[];
  status: VendorsStatus;
  error: NormalizedApiError | null;
  /** Re-read the tenant's vendors from the API. */
  refresh: () => Promise<void>;
  /** Create a vendor through the API and add it to the list. Throws NormalizedApiError. */
  createVendor: (input: VendorCreateInput) => Promise<VendorRecord>;
  /** Terminate a vendor through the API and update the list. Throws NormalizedApiError. */
  terminateVendor: (id: string) => Promise<VendorRecord>;
}

export function useVendors(): UseVendorsResult {
  const [vendors, setVendors] = useState<VendorRecord[]>([]);
  const [status, setStatus] = useState<VendorsStatus>('loading');
  const [error, setError] = useState<NormalizedApiError | null>(null);
  // Guards against out-of-order responses and setState after unmount.
  const requestIdRef = useRef(0);
  const mountedRef = useRef(false);

  const load = useCallback(async (signal?: AbortSignal) => {
    const requestId = ++requestIdRef.current;
    setStatus('loading');
    setError(null);
    try {
      const records = await fetchVendors(signal);
      if (!mountedRef.current || requestId !== requestIdRef.current) return;
      setVendors(records);
      setStatus(records.length === 0 ? 'empty' : 'success');
    } catch (e) {
      if (!mountedRef.current || requestId !== requestIdRef.current) return;
      // A caller-initiated abort is not an error state.
      if (e instanceof DOMException && e.name === 'AbortError') return;
      setError(normalizeApiError(e));
      setStatus('error');
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    const controller = new AbortController();
    void load(controller.signal);
    return () => {
      mountedRef.current = false;
      controller.abort();
    };
  }, [load]);

  const refresh = useCallback(async () => {
    await load();
  }, [load]);

  const createVendor = useCallback(async (input: VendorCreateInput): Promise<VendorRecord> => {
    const dto = await vendorsApi.createVendor(toCreateRequest(input));
    const record = toVendorRecord(dto);
    setVendors(prev => [...prev, record]);
    setStatus('success');
    return record;
  }, []);

  const terminateVendor = useCallback(async (id: string): Promise<VendorRecord> => {
    const dto = await vendorsApi.terminateVendor(id);
    const record = toVendorRecord(dto);
    setVendors(prev => prev.map(v => (v.id === id ? record : v)));
    return record;
  }, []);

  return { vendors, status, error, refresh, createVendor, terminateVendor };
}
