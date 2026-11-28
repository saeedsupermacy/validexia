// src/features/access-grants/AccessGrantsWorkspace.tsx
import { useCallback, useEffect, useRef, useState } from 'react';
import { Eye, KeyRound, Plus } from 'lucide-react';
import { Badge, Button, Card, EmptyState, Eyebrow, StatusBadge } from '../../shared/ui/core';
import { Textarea } from '../../shared/ui/forms';
import { Dialog } from '../../shared/ui/Dialog';
import { TableSkeleton } from '../../shared/ui/loading';
import { showToast } from '../../shared/ui/toast';
import { clearNavParams, getNavParams } from '../../shared/navigation';
import type { TenantContext } from '../../shared/api/session';
import type { ApiVendor } from '../vendors/api';
import type { ApiInternalSystem } from '../internal-systems/api';
import {
 ACCESS_METHODS,
 LIFECYCLE_STATUSES,
 PRIVILEGE_LEVELS,
 SOURCE_KINDS,
 VERIFICATION_STATUSES,
 type ApiAccessGrant,
 type ApiAccessMethod,
 type ApiLifecycleStatus,
 type ApiPrivilegeLevel,
 type ApiSourceKind,
 type ApiVerificationStatus,
 type GrantFilters,
} from './api';
import {
 apiErrorMessage,
 canModerate,
 confirmAccessGrant,
 createAccessGrant,
 loadAccessGrants,
 loadReferenceData,
 recordDiscoveredGrant,
 reinstateAccessGrant,
 rejectAccessGrant,
 revokeAccessGrant,
 suspendAccessGrant,
 userLabel,
} from './service';

/**
 * Vendor Access Grants (M1 Slice 3B) — which vendor has access to which of
 * the tenant's internal systems, against the real backend
 * (/api/v1/access-grants). Verification and lifecycle are shown as the two
 * separate axes they are. There is no demo data and no local simulation:
 * the list is server-filtered, and every transition updates only from the
 * server's response. Evidence linking is a separate slice and is not here.
 */

interface FilterState {
 vendorId: string;
 internalSystemId: string;
 lifecycleStatus: string;
 verificationStatus: string;
}

type TransitionKind = 'confirm' | 'reject' | 'suspend' | 'reinstate' | 'revoke';
type TransitionState = { kind: TransitionKind; grant: ApiAccessGrant };

const selectCls =
 'w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:ring-2 focus:ring-info';
const labelCls = 'block text-xs font-medium text-text-secondary mb-1.5';

function formatInstant(iso: string | null): string {
 if (!iso) return '—';
 const date = new Date(iso);
 return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

function lifecycleBadge(status: ApiLifecycleStatus) {
 if (status === 'Active') return <StatusBadge status="success" label="Active" />;
 if (status === 'Suspended') return <StatusBadge status="warning" label="Suspended" />;
 return <StatusBadge status="critical" label="Revoked" />;
}

function verificationBadge(status: ApiVerificationStatus) {
 if (status === 'Confirmed') return <StatusBadge status="success" label="Confirmed" />;
 if (status === 'Rejected') return <StatusBadge status="critical" label="Rejected" />;
 return <StatusBadge status="warning" label="Unverified" />;
}

export function AccessGrantsWorkspace() {
 const [grants, setGrants] = useState<ApiAccessGrant[] | null>(null);
 const [vendors, setVendors] = useState<ApiVendor[]>([]);
 const [systems, setSystems] = useState<ApiInternalSystem[]>([]);
 const [tenant, setTenant] = useState<TenantContext | null>(null);
 const [loading, setLoading] = useState(true);
 const [loadError, setLoadError] = useState<string | null>(null);
 const [filters, setFilters] = useState<FilterState>(() => {
 const params = getNavParams();
 return {
 vendorId: typeof params.vendorId === 'string' ? params.vendorId : '',
 internalSystemId: typeof params.internalSystemId === 'string' ? params.internalSystemId : '',
 lifecycleStatus: '',
 verificationStatus: '',
 };
 });
 const [createOpen, setCreateOpen] = useState(false);
 const [detailId, setDetailId] = useState<string | null>(null);
 const [transition, setTransition] = useState<TransitionState | null>(null);

 const toApiFilters = useCallback((f: FilterState): GrantFilters => ({
 vendorId: f.vendorId || undefined,
 internalSystemId: f.internalSystemId || undefined,
 lifecycleStatus: (f.lifecycleStatus || undefined) as ApiLifecycleStatus | undefined,
 verificationStatus: (f.verificationStatus || undefined) as ApiVerificationStatus | undefined,
 }), []);

 const refreshGrants = useCallback(async (signal?: AbortSignal) => {
 const result = await loadAccessGrants(toApiFilters(filters), signal);
 setGrants(result.grants);
 setTenant(result.tenant);
 }, [filters, toApiFilters]);

 const loadInitial = useCallback(async (signal?: AbortSignal) => {
 setLoading(true);
 setLoadError(null);
 try {
 const reference = await loadReferenceData(signal);
 setVendors(reference.vendors);
 setSystems(reference.systems);
 await refreshGrants(signal);
 } catch (error) {
 if (!signal?.aborted) setLoadError(apiErrorMessage(error));
 } finally {
 if (!signal?.aborted) setLoading(false);
 }
 }, [refreshGrants]);

 // Initial load: reference data (vendors + systems) and the grants list.
 // Runs once on mount — loadInitial's identity changes with the filters,
 // and filter changes are handled by the refilter effect below.
 const loadInitialRef = useRef(loadInitial);
 loadInitialRef.current = loadInitial;
 useEffect(() => {
 const controller = new AbortController();
 clearNavParams();
 loadInitialRef.current(controller.signal);
 return () => controller.abort();
 }, []);

 // Server-side refilter whenever a filter changes (skips the first run —
 // the initial load above already fetched with the starting filters).
 const firstFilterRun = useRef(true);
 useEffect(() => {
 if (firstFilterRun.current) {
 firstFilterRun.current = false;
 return;
 }
 const controller = new AbortController();
 (async () => {
 try {
 await refreshGrants(controller.signal);
 setLoadError(null);
 } catch (error) {
 if (!controller.signal.aborted) setLoadError(apiErrorMessage(error));
 }
 })();
 return () => controller.abort();
 }, [filters, refreshGrants]);

 const vendorName = useCallback(
 (id: string) => vendors.find(v => v.id === id)?.name ?? `Vendor ${id.slice(0, 8)}…`,
 [vendors]);
 const systemName = useCallback(
 (id: string) => systems.find(s => s.id === id)?.name ?? `System ${id.slice(0, 8)}…`,
 [systems]);

 const detail = grants?.find(g => g.id === detailId) ?? null;
 const hasFilters = Boolean(filters.vendorId || filters.internalSystemId || filters.lifecycleStatus || filters.verificationStatus);
 const moderate = canModerate(tenant);

 const afterMutation = useCallback(async (message: string) => {
 try {
 await refreshGrants();
 } catch {
 // The mutation itself succeeded; a failed refresh leaves the old list
 // visible with the toast still confirming the server's decision.
 }
 showToast(message, 'success');
 }, [refreshGrants]);

 return (
 <div>
 <div className="flex flex-wrap items-start justify-between gap-4 mb-6">
 <div>
 <Eyebrow>Program</Eyebrow>
 <h1 className="text-xl font-semibold text-text-primary tracking-tight m-0">Access Grants</h1>
 <p className="text-sm text-text-secondary mt-1 m-0 max-w-xl">
 Which vendors can access which of your internal systems, at what privilege, and whether
 that access has been verified.
 {tenant && <> Showing live data for <span className="font-medium text-text-primary">{tenant.tenantName}</span>.</>}
 </p>
 </div>
 <Button type="button" variant="primary" onClick={() => setCreateOpen(true)}>
 <Plus aria-hidden="true" size={14} strokeWidth={1.5} className="mr-1.5" /> Record grant
 </Button>
 </div>

 <Card className="p-4 mb-6">
 <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
 <div>
 <label htmlFor="filter-vendor" className={labelCls}>Vendor</label>
 <select id="filter-vendor" className={selectCls} value={filters.vendorId} onChange={e => setFilters(f => ({ ...f, vendorId: e.target.value }))}>
 <option value="">All vendors</option>
 {vendors.map(v => <option key={v.id} value={v.id}>{v.name}</option>)}
 </select>
 </div>
 <div>
 <label htmlFor="filter-system" className={labelCls}>Internal system</label>
 <select id="filter-system" className={selectCls} value={filters.internalSystemId} onChange={e => setFilters(f => ({ ...f, internalSystemId: e.target.value }))}>
 <option value="">All systems</option>
 {systems.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
 </select>
 </div>
 <div>
 <label htmlFor="filter-lifecycle" className={labelCls}>Lifecycle status</label>
 <select id="filter-lifecycle" className={selectCls} value={filters.lifecycleStatus} onChange={e => setFilters(f => ({ ...f, lifecycleStatus: e.target.value }))}>
 <option value="">All lifecycle states</option>
 {LIFECYCLE_STATUSES.map(s => <option key={s} value={s}>{s}</option>)}
 </select>
 </div>
 <div>
 <label htmlFor="filter-verification" className={labelCls}>Verification status</label>
 <select id="filter-verification" className={selectCls} value={filters.verificationStatus} onChange={e => setFilters(f => ({ ...f, verificationStatus: e.target.value }))}>
 <option value="">All verification states</option>
 {VERIFICATION_STATUSES.map(s => <option key={s} value={s}>{s}</option>)}
 </select>
 </div>
 </div>
 {hasFilters && (
 <div className="mt-3 flex items-center justify-between">
 <span className="text-xs text-text-secondary">{grants?.length ?? 0} grant{(grants?.length ?? 0) === 1 ? '' : 's'} match these filters</span>
 <Button type="button" variant="ghost" size="sm" onClick={() => setFilters({ vendorId: '', internalSystemId: '', lifecycleStatus: '', verificationStatus: '' })}>
 Clear filters
 </Button>
 </div>
 )}
 </Card>

 {loading && grants === null && <TableSkeleton rows={5} cols={6} />}

 {!loading && loadError && grants === null && (
 <Card className="p-6" role="alert">
 <h2 className="text-sm font-semibold text-text-primary m-0">Access grants could not be loaded</h2>
 <p className="text-sm text-text-secondary mt-1">{loadError}</p>
 <Button type="button" variant="outline" size="sm" className="mt-4" onClick={() => loadInitial()}>
 Try again
 </Button>
 </Card>
 )}

 {loadError && grants !== null && (
 <p role="alert" className="mb-4 text-sm text-risk-critical bg-risk-critical/10 border border-risk-critical/30 rounded-lg px-3 py-2">
 The list could not be refreshed: {loadError}
 </p>
 )}

 {grants !== null && grants.length === 0 && !hasFilters && (
 <EmptyState
 icon={KeyRound}
 title="No access grants yet"
 description="Record the access a vendor holds into one of your internal systems, or log access you discovered during a review."
 whatItIs="Access grants are the record of vendor access into your own systems — the privilege, the method, where the record came from, and whether it has been verified."
 whyItsEmpty="No access grants have been recorded for this tenant yet. Nothing is pre-filled or imported."
 actionLabel="Record the first grant"
 onAction={() => setCreateOpen(true)}
 />
 )}

 {grants !== null && grants.length === 0 && hasFilters && (
 <Card className="p-6 text-center">
 <h2 className="text-sm font-semibold text-text-primary m-0">No grants match these filters</h2>
 <p className="text-sm text-text-secondary mt-1">Try a different combination, or clear the filters to see every grant.</p>
 <Button type="button" variant="outline" size="sm" className="mt-4" onClick={() => setFilters({ vendorId: '', internalSystemId: '', lifecycleStatus: '', verificationStatus: '' })}>
 Clear filters
 </Button>
 </Card>
 )}

 {grants !== null && grants.length > 0 && (
 <Card>
 <div className="overflow-x-auto">
 <table className="w-full text-sm">
 <thead>
 <tr className="border-b border-border-subtle text-left">
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary">Vendor → System</th>
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary">Privilege</th>
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary">Method</th>
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary">Verification</th>
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary">Lifecycle</th>
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary">Updated</th>
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary"><span className="sr-only">Actions</span></th>
 </tr>
 </thead>
 <tbody>
 {grants.map(grant => (
 <tr key={grant.id} className="border-b border-border-subtle last:border-0 hover:bg-surface-hover/50 transition-colors">
 <td className="px-5 py-3.5">
 <button type="button" onClick={() => setDetailId(grant.id)} className="font-medium text-text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-info rounded-sm text-left">
 {vendorName(grant.vendorId)} <span aria-hidden="true" className="text-text-tertiary">→</span> {systemName(grant.internalSystemId)}
 </button>
 <p className="text-xs text-text-secondary mt-0.5 m-0">
 Source: {grant.sourceKind}
 {grant.discoveryNote !== null && <> · <Badge className="ml-1">Discovered</Badge></>}
 </p>
 </td>
 <td className="px-5 py-3.5 text-text-secondary">{grant.privilegeLevel}</td>
 <td className="px-5 py-3.5 text-text-secondary">{grant.accessMethod}</td>
 <td className="px-5 py-3.5">{verificationBadge(grant.verificationStatus)}</td>
 <td className="px-5 py-3.5">{lifecycleBadge(grant.lifecycleStatus)}</td>
 <td className="px-5 py-3.5 text-text-secondary whitespace-nowrap">{formatInstant(grant.updatedAt)}</td>
 <td className="px-5 py-3.5">
 <div className="flex items-center justify-end">
 <Button type="button" variant="ghost" size="sm" onClick={() => setDetailId(grant.id)} aria-label={`View grant for ${vendorName(grant.vendorId)}`}>
 <Eye aria-hidden="true" size={14} strokeWidth={1.5} />
 </Button>
 </div>
 </td>
 </tr>
 ))}
 </tbody>
 </table>
 </div>
 </Card>
 )}

 {createOpen && (
 <GrantFormDialog
 vendors={vendors}
 systems={systems}
 onClose={() => setCreateOpen(false)}
 onSaved={async grant => {
 setCreateOpen(false);
 await afterMutation(
 grant.discoveryNote !== null
 ? 'Discovery recorded — the grant is Revoked and Unverified'
 : `Grant recorded for ${vendorName(grant.vendorId)}`);
 }}
 />
 )}

 {detail && (
 <GrantDetailDialog
 grant={detail}
 vendorName={vendorName(detail.vendorId)}
 systemName={systemName(detail.internalSystemId)}
 canModerate={moderate}
 onClose={() => setDetailId(null)}
 onTransition={kind => { setDetailId(null); setTransition({ kind, grant: detail }); }}
 />
 )}

 {transition && (
 <TransitionDialog
 state={transition}
 vendorName={vendorName(transition.grant.vendorId)}
 systemName={systemName(transition.grant.internalSystemId)}
 onClose={() => setTransition(null)}
 onDone={async message => {
 setTransition(null);
 await afterMutation(message);
 }}
 />
 )}
 </div>
 );
}

function GrantFormDialog({
 vendors,
 systems,
 onClose,
 onSaved,
}: {
 vendors: ApiVendor[];
 systems: ApiInternalSystem[];
 onClose: () => void;
 onSaved: (grant: ApiAccessGrant) => void;
}) {
 const [mode, setMode] = useState<'create' | 'discovery'>('create');
 const [vendorId, setVendorId] = useState('');
 const [internalSystemId, setInternalSystemId] = useState('');
 const [privilegeLevel, setPrivilegeLevel] = useState<ApiPrivilegeLevel>('Read');
 const [accessMethod, setAccessMethod] = useState<ApiAccessMethod>('Direct Login');
 const [sourceKind, setSourceKind] = useState<ApiSourceKind>('Customer Entered');
 const [discoveryNote, setDiscoveryNote] = useState('');
 const [formError, setFormError] = useState<string | null>(null);
 const [saving, setSaving] = useState(false);

 const submit = async () => {
 if (!vendorId) { setFormError('Choose the vendor this access belongs to'); return; }
 if (!internalSystemId) { setFormError('Choose the internal system being accessed'); return; }
 if (mode === 'discovery' && !discoveryNote.trim()) { setFormError('A discovery note is required — say where this access was found'); return; }
 setSaving(true);
 setFormError(null);
 try {
 const grant = mode === 'discovery'
 ? await recordDiscoveredGrant({ vendorId, internalSystemId, privilegeLevel, accessMethod, sourceKind, discoveryNote: discoveryNote.trim() })
 : await createAccessGrant({ vendorId, internalSystemId, privilegeLevel, accessMethod, sourceKind });
 onSaved(grant);
 } catch (error) {
 setFormError(apiErrorMessage(error));
 setSaving(false);
 }
 };

 return (
 <Dialog open onClose={onClose} title={mode === 'discovery' ? 'Record a discovered grant' : 'Record an access grant'} maxWidth="max-w-xl">
 <div className="p-6">
 <h2 className="text-base font-semibold text-text-primary m-0">
 {mode === 'discovery' ? 'Record a discovered grant' : 'Record an access grant'}
 </h2>

 <div className="mt-4 inline-flex items-center gap-1 p-1 bg-surface-hover/50 border border-border rounded-xl" role="tablist" aria-label="Grant kind">
 <button type="button" role="tab" aria-selected={mode === 'create'} onClick={() => setMode('create')} className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-all focus:outline-none focus:ring-2 focus:ring-info ${mode === 'create' ? 'bg-surface text-text-primary shadow-sm ring-1 ring-border' : 'text-text-secondary hover:text-text-primary'}`}>
 New grant
 </button>
 <button type="button" role="tab" aria-selected={mode === 'discovery'} onClick={() => setMode('discovery')} className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-all focus:outline-none focus:ring-2 focus:ring-info ${mode === 'discovery' ? 'bg-surface text-text-primary shadow-sm ring-1 ring-border' : 'text-text-secondary hover:text-text-primary'}`}>
 Discovery
 </button>
 </div>

 {mode === 'discovery' ? (
 <p className="text-sm text-text-secondary mt-3 leading-relaxed">
 A discovered grant documents access you found during a review. It is recorded as
 <span className="font-medium text-text-primary"> Revoked and Unverified</span> — it never
 represents live access, and it cannot be confirmed into one. To authorize this access,
 record a new grant instead.
 </p>
 ) : (
 <p className="text-sm text-text-secondary mt-3">
 A new grant starts <span className="font-medium text-text-primary">Active and Unverified</span>.
 A tenant owner or admin can confirm or reject it afterwards.
 </p>
 )}

 <div className="mt-5 grid grid-cols-1 sm:grid-cols-2 gap-4">
 <div>
 <label htmlFor="grant-vendor" className={labelCls}>Vendor <span className="text-risk-critical">*</span></label>
 <select id="grant-vendor" className={selectCls} value={vendorId} onChange={e => setVendorId(e.target.value)}>
 <option value="">Choose a vendor…</option>
 {vendors.map(v => (
 <option key={v.id} value={v.id} disabled={v.status === 'Terminated'}>
 {v.name}{v.status === 'Terminated' ? ' (Terminated)' : ''}
 </option>
 ))}
 </select>
 </div>
 <div>
 <label htmlFor="grant-system" className={labelCls}>Internal system <span className="text-risk-critical">*</span></label>
 <select id="grant-system" className={selectCls} value={internalSystemId} onChange={e => setInternalSystemId(e.target.value)}>
 <option value="">Choose a system…</option>
 {systems.map(s => (
 <option key={s.id} value={s.id} disabled={s.status === 'Retired'}>
 {s.name}{s.status === 'Retired' ? ' (Retired)' : ''}
 </option>
 ))}
 </select>
 </div>
 <div>
 <label htmlFor="grant-privilege" className={labelCls}>Privilege level</label>
 <select id="grant-privilege" className={selectCls} value={privilegeLevel} onChange={e => setPrivilegeLevel(e.target.value as ApiPrivilegeLevel)}>
 {PRIVILEGE_LEVELS.map(p => <option key={p} value={p}>{p}</option>)}
 </select>
 </div>
 <div>
 <label htmlFor="grant-method" className={labelCls}>Access method</label>
 <select id="grant-method" className={selectCls} value={accessMethod} onChange={e => setAccessMethod(e.target.value as ApiAccessMethod)}>
 {ACCESS_METHODS.map(m => <option key={m} value={m}>{m}</option>)}
 </select>
 </div>
 <div className="sm:col-span-2">
 <label htmlFor="grant-source" className={labelCls}>Source of this record</label>
 <select id="grant-source" className={selectCls} value={sourceKind} onChange={e => setSourceKind(e.target.value as ApiSourceKind)}>
 {SOURCE_KINDS.map(s => <option key={s} value={s}>{s}</option>)}
 </select>
 </div>
 {mode === 'discovery' && (
 <div className="sm:col-span-2">
 <label htmlFor="grant-note" className={labelCls}>Discovery note <span className="text-risk-critical">*</span></label>
 <Textarea id="grant-note" value={discoveryNote} onChange={e => setDiscoveryNote(e.target.value)} placeholder="Where was this access found? e.g. Found in the Q3 access review export from the vendor" />
 </div>
 )}
 </div>

 {formError && (
 <p role="alert" className="mt-4 text-sm text-risk-critical bg-risk-critical/10 border border-risk-critical/30 rounded-lg px-3 py-2">
 {formError}
 </p>
 )}

 <div className="mt-6 flex items-center justify-end gap-2">
 <Button type="button" variant="ghost" onClick={onClose} disabled={saving}>Cancel</Button>
 <Button type="button" variant="primary" onClick={submit} isLoading={saving}>
 {mode === 'discovery' ? 'Record discovery' : 'Record grant'}
 </Button>
 </div>
 </div>
 </Dialog>
 );
}

function GrantDetailDialog({
 grant,
 vendorName,
 systemName,
 canModerate: moderate,
 onClose,
 onTransition,
}: {
 grant: ApiAccessGrant;
 vendorName: string;
 systemName: string;
 canModerate: boolean;
 onClose: () => void;
 onTransition: (kind: TransitionKind) => void;
}) {
 const rows: Array<[string, string]> = [
 ['Vendor', vendorName],
 ['Internal system', systemName],
 ['Privilege level', grant.privilegeLevel],
 ['Access method', grant.accessMethod],
 ['Source of record', grant.sourceKind],
 ['Recorded by', `${userLabel(grant.createdByUserId)} · ${formatInstant(grant.createdAt)}`],
 ['Last updated', formatInstant(grant.updatedAt)],
 ];
 if (grant.verifiedByUserId || grant.verifiedAt) {
 rows.push(['Verified by', `${userLabel(grant.verifiedByUserId)} · ${formatInstant(grant.verifiedAt)}`]);
 }
 if (grant.rejectionReason) rows.push(['Rejection reason', grant.rejectionReason]);
 if (grant.discoveryNote) rows.push(['Discovery note', grant.discoveryNote]);
 if (grant.suspendedAt) {
 rows.push(['Suspended', `${userLabel(grant.suspendedByUserId)} · ${formatInstant(grant.suspendedAt)}`]);
 if (grant.suspensionReason) rows.push(['Suspension reason', grant.suspensionReason]);
 }
 if (grant.reinstatedAt) {
 rows.push(['Reinstated', `${userLabel(grant.reinstatedByUserId)} · ${formatInstant(grant.reinstatedAt)}`]);
 }
 if (grant.revokedAt) {
 rows.push(['Revoked', `${userLabel(grant.revokedByUserId)} · ${formatInstant(grant.revokedAt)}`]);
 if (grant.revocationReason) rows.push(['Revocation reason', grant.revocationReason]);
 }

 const isActive = grant.lifecycleStatus === 'Active';
 const isSuspended = grant.lifecycleStatus === 'Suspended';
 const isRevoked = grant.lifecycleStatus === 'Revoked';
 const canConfirm = isActive && (grant.verificationStatus === 'Unverified' || grant.verificationStatus === 'Rejected');
 const canReject = isActive && (grant.verificationStatus === 'Unverified' || grant.verificationStatus === 'Confirmed');

 return (
 <Dialog open onClose={onClose} title="Access grant" maxWidth="max-w-xl">
 <div className="p-6">
 <div className="flex flex-wrap items-start justify-between gap-3">
 <h2 className="text-base font-semibold text-text-primary m-0">
 {vendorName} <span aria-hidden="true" className="text-text-tertiary">→</span> {systemName}
 </h2>
 <div className="flex items-center gap-2">
 {verificationBadge(grant.verificationStatus)}
 {lifecycleBadge(grant.lifecycleStatus)}
 </div>
 </div>

 {grant.discoveryNote !== null && (
 <p className="mt-3 text-sm text-text-secondary leading-relaxed">
 This grant was recorded from a discovery. It is an assertion that access was found —
 not an authorization — and a revoked grant cannot be confirmed or reinstated. To
 authorize this access, record a new grant.
 </p>
 )}
 {isRevoked && grant.discoveryNote === null && (
 <p className="mt-3 text-sm text-text-secondary">Revocation is permanent. To restore this access, record a new grant.</p>
 )}

 <dl className="mt-5 space-y-3">
 {rows.map(([label, value]) => (
 <div key={label} className="flex items-baseline justify-between gap-6 border-b border-border-subtle pb-2.5 last:border-0">
 <dt className="text-xs font-medium text-text-tertiary shrink-0">{label}</dt>
 <dd className="text-sm text-text-primary text-right m-0">{value}</dd>
 </div>
 ))}
 </dl>

 {!moderate && (canConfirm || canReject) && (
 <p className="mt-4 text-xs text-text-secondary">
 Confirming and rejecting grants is restricted to tenant owners and admins.
 </p>
 )}

 <div className="mt-6 flex flex-wrap items-center justify-end gap-2">
 <Button type="button" variant="ghost" onClick={onClose}>Close</Button>
 {canConfirm && (
 <Button type="button" variant="primary" disabled={!moderate} onClick={() => onTransition('confirm')}>
 Confirm access
 </Button>
 )}
 {canReject && (
 <Button type="button" variant="outline" disabled={!moderate} onClick={() => onTransition('reject')}>
 Reject
 </Button>
 )}
 {isActive && (
 <Button type="button" variant="outline" onClick={() => onTransition('suspend')}>
 Suspend
 </Button>
 )}
 {isSuspended && (
 <Button type="button" variant="primary" onClick={() => onTransition('reinstate')}>
 Reinstate
 </Button>
 )}
 {(isActive || isSuspended) && (
 <Button type="button" variant="danger" onClick={() => onTransition('revoke')}>
 Revoke
 </Button>
 )}
 </div>
 </div>
 </Dialog>
 );
}

const TRANSITION_COPY: Record<TransitionKind, { title: string; body: string; cta: string; done: string; needsReason: boolean; reasonLabel: string; danger?: boolean }> = {
 confirm: {
 title: 'Confirm this access?',
 body: 'Confirming records that a tenant owner or admin has verified this vendor access is legitimate. The grant stays Active.',
 cta: 'Confirm access',
 done: 'Access confirmed',
 needsReason: false,
 reasonLabel: '',
 },
 reject: {
 title: 'Reject this access?',
 body: 'Rejecting records that this access was reviewed and is not legitimate. The grant stays visible in every listing, marked Rejected.',
 cta: 'Reject access',
 done: 'Access rejected',
 needsReason: true,
 reasonLabel: 'Rejection reason',
 },
 suspend: {
 title: 'Suspend this access?',
 body: 'Suspension pauses the access without ending it. It can be reinstated later — unless the vendor is terminated in the meantime.',
 cta: 'Suspend access',
 done: 'Access suspended',
 needsReason: true,
 reasonLabel: 'Suspension reason',
 },
 reinstate: {
 title: 'Reinstate this access?',
 body: 'Reinstating returns a suspended grant to Active. If the vendor has been terminated, the reinstatement will be refused.',
 cta: 'Reinstate access',
 done: 'Access reinstated',
 needsReason: false,
 reasonLabel: '',
 },
 revoke: {
 title: 'Revoke this access permanently?',
 body: 'Revocation is terminal — it cannot be undone or reinstated. To restore this access afterwards, a new grant must be recorded.',
 cta: 'Revoke access',
 done: 'Access revoked',
 needsReason: true,
 reasonLabel: 'Revocation reason',
 danger: true,
 },
};

function TransitionDialog({
 state,
 vendorName,
 systemName,
 onClose,
 onDone,
}: {
 state: TransitionState;
 vendorName: string;
 systemName: string;
 onClose: () => void;
 onDone: (message: string) => void;
}) {
 const { kind, grant } = state;
 const copy = TRANSITION_COPY[kind];
 const [reason, setReason] = useState('');
 const [busy, setBusy] = useState(false);
 const [error, setError] = useState<string | null>(null);

 const run = async () => {
 if (copy.needsReason && !reason.trim()) {
 setError(`${copy.reasonLabel} is required`);
 return;
 }
 setBusy(true);
 setError(null);
 try {
 if (kind === 'confirm') await confirmAccessGrant(grant.id);
 else if (kind === 'reject') await rejectAccessGrant(grant.id, reason.trim());
 else if (kind === 'suspend') await suspendAccessGrant(grant.id, reason.trim());
 else if (kind === 'reinstate') await reinstateAccessGrant(grant.id);
 else await revokeAccessGrant(grant.id, reason.trim());
 onDone(`${copy.done} — ${vendorName} → ${systemName}`);
 } catch (e) {
 setError(apiErrorMessage(e));
 setBusy(false);
 }
 };

 return (
 <Dialog open onClose={onClose} title={copy.title} disableBackdropClose={busy}>
 <div className="p-6">
 <h2 className="text-base font-semibold text-text-primary m-0">{copy.title}</h2>
 <p className="text-sm text-text-secondary mt-1">
 {vendorName} <span aria-hidden="true" className="text-text-tertiary">→</span> {systemName} · {grant.privilegeLevel} · {grant.accessMethod}
 </p>
 <p className="text-sm text-text-secondary mt-2 leading-relaxed">{copy.body}</p>

 {copy.needsReason && (
 <div className="mt-4">
 <label htmlFor="transition-reason" className={labelCls}>{copy.reasonLabel} <span className="text-risk-critical">*</span></label>
 <Textarea id="transition-reason" value={reason} onChange={e => setReason(e.target.value)} placeholder="Required — recorded with the decision" />
 </div>
 )}

 {error && (
 <p role="alert" className="mt-4 text-sm text-risk-critical bg-risk-critical/10 border border-risk-critical/30 rounded-lg px-3 py-2">
 {error}
 </p>
 )}

 <div className="mt-6 flex items-center justify-end gap-2">
 <Button type="button" variant="ghost" onClick={onClose} disabled={busy}>Cancel</Button>
 <Button type="button" variant={copy.danger ? 'danger' : 'primary'} onClick={run} isLoading={busy}>
 {copy.cta}
 </Button>
 </div>
 </div>
 </Dialog>
 );
}
