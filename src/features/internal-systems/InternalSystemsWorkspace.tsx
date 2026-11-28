// src/features/internal-systems/InternalSystemsWorkspace.tsx
import { useCallback, useEffect, useState } from 'react';
import { Archive, ArchiveRestore, Eye, Pencil, Plus, Server } from 'lucide-react';
import { Button, Card, EmptyState, Eyebrow, StatusBadge } from '../../shared/ui/core';
import { Input, Textarea } from '../../shared/ui/forms';
import { Dialog } from '../../shared/ui/Dialog';
import { TableSkeleton } from '../../shared/ui/loading';
import { showToast } from '../../shared/ui/toast';
import type { TenantContext } from '../../shared/api/session';
import type { ApiInternalSystem, InternalSystemPayload } from './api';
import {
 apiErrorMessage,
 createInternalSystem,
 loadInternalSystems,
 ownerLabel,
 restoreInternalSystem,
 retireInternalSystem,
 updateInternalSystem,
} from './service';

/**
 * Internal Systems inventory (M1 Slice 3A) — the tenant's own systems,
 * maintained against the real backend (/api/v1/internal-systems). There is
 * no demo data and no local fallback: loading, empty, and error states are
 * rendered honestly from the API result, and every mutation is confirmed by
 * the server response before the UI reflects it.
 */

type FormState = { mode: 'create' } | { mode: 'edit'; system: ApiInternalSystem };
type ConfirmState = { action: 'retire' | 'restore'; system: ApiInternalSystem };

const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

function formatInstant(iso: string | null): string {
 if (!iso) return '—';
 const date = new Date(iso);
 return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

export function InternalSystemsWorkspace() {
 const [systems, setSystems] = useState<ApiInternalSystem[] | null>(null);
 const [tenant, setTenant] = useState<TenantContext | null>(null);
 const [loading, setLoading] = useState(true);
 const [loadError, setLoadError] = useState<string | null>(null);
 const [form, setForm] = useState<FormState | null>(null);
 const [detailId, setDetailId] = useState<string | null>(null);
 const [confirm, setConfirm] = useState<ConfirmState | null>(null);

 const load = useCallback(async (signal?: AbortSignal) => {
 setLoading(true);
 setLoadError(null);
 try {
 const result = await loadInternalSystems(signal);
 setSystems(result.systems);
 setTenant(result.tenant);
 } catch (error) {
 if (signal?.aborted) return;
 setLoadError(apiErrorMessage(error));
 } finally {
 if (!signal?.aborted) setLoading(false);
 }
 }, []);

 useEffect(() => {
 const controller = new AbortController();
 load(controller.signal);
 return () => controller.abort();
 }, [load]);

 /** Replace one row with the server's returned view after a mutation. */
 const applyUpdated = useCallback((updated: ApiInternalSystem) => {
 setSystems(prev => (prev ?? []).map(s => (s.id === updated.id ? updated : s)));
 }, []);

 const detail = systems?.find(s => s.id === detailId) ?? null;

 return (
 <div>
 <div className="flex flex-wrap items-start justify-between gap-4 mb-6">
 <div>
 <Eyebrow>Program</Eyebrow>
 <h1 className="text-xl font-semibold text-text-primary tracking-tight m-0">Internal Systems</h1>
 <p className="text-sm text-text-secondary mt-1 m-0 max-w-xl">
 The systems your organisation runs and grants vendors access to.
 {tenant && <> Showing live data for <span className="font-medium text-text-primary">{tenant.tenantName}</span>.</>}
 </p>
 </div>
 <Button type="button" variant="primary" onClick={() => setForm({ mode: 'create' })}>
 <Plus aria-hidden="true" size={14} strokeWidth={1.5} className="mr-1.5" /> Add system
 </Button>
 </div>

 {loading && systems === null && <TableSkeleton rows={5} cols={5} />}

 {!loading && loadError && systems === null && (
 <Card className="p-6" role="alert">
 <h2 className="text-sm font-semibold text-text-primary m-0">Internal systems could not be loaded</h2>
 <p className="text-sm text-text-secondary mt-1">{loadError}</p>
 <Button type="button" variant="outline" size="sm" className="mt-4" onClick={() => load()}>
 Try again
 </Button>
 </Card>
 )}

 {systems !== null && systems.length === 0 && !loadError && (
 <EmptyState
 icon={Server}
 title="No internal systems yet"
 description="Add the systems your organisation runs so vendor access can be tracked against them."
 whatItIs="This inventory is the record of your own technology environment — the systems vendors will be granted access to through access grants."
 whyItsEmpty="No internal systems have been created for this tenant yet. Nothing is pre-filled or imported."
 actionLabel="Add your first system"
 onAction={() => setForm({ mode: 'create' })}
 />
 )}

 {systems !== null && systems.length > 0 && (
 <Card>
 <div className="overflow-x-auto">
 <table className="w-full text-sm">
 <thead>
 <tr className="border-b border-border-subtle text-left">
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary">System</th>
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary">Owner</th>
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary">Status</th>
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary">Active grants</th>
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary">Updated</th>
 <th scope="col" className="px-5 py-3 text-xs font-semibold uppercase tracking-wider text-text-tertiary"><span className="sr-only">Actions</span></th>
 </tr>
 </thead>
 <tbody>
 {systems.map(system => (
 <tr key={system.id} className="border-b border-border-subtle last:border-0 hover:bg-surface-hover/50 transition-colors">
 <td className="px-5 py-3.5">
 <button type="button" onClick={() => setDetailId(system.id)} className="font-medium text-text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-info rounded-sm">
 {system.name}
 </button>
 {system.description && <p className="text-xs text-text-secondary mt-0.5 m-0 line-clamp-2 max-w-md">{system.description}</p>}
 </td>
 <td className="px-5 py-3.5 text-text-secondary">{ownerLabel(system.ownerUserId)}</td>
 <td className="px-5 py-3.5">
 {system.status === 'Active'
 ? <StatusBadge status="success" label="Active" />
 : <StatusBadge status="warning" label="Retired" />}
 </td>
 <td className="px-5 py-3.5 text-text-secondary">{system.activeGrantCount}</td>
 <td className="px-5 py-3.5 text-text-secondary whitespace-nowrap">{formatInstant(system.updatedAt)}</td>
 <td className="px-5 py-3.5">
 <div className="flex items-center justify-end gap-1.5">
 <Button type="button" variant="ghost" size="sm" onClick={() => setDetailId(system.id)} aria-label={`View ${system.name}`}>
 <Eye aria-hidden="true" size={14} strokeWidth={1.5} />
 </Button>
 <Button type="button" variant="ghost" size="sm" disabled={system.status !== 'Active'} title={system.status !== 'Active' ? 'Retired systems cannot be edited' : undefined} onClick={() => setForm({ mode: 'edit', system })} aria-label={`Edit ${system.name}`}>
 <Pencil aria-hidden="true" size={14} strokeWidth={1.5} />
 </Button>
 {system.status === 'Active'
 ? (
 <Button type="button" variant="ghost" size="sm" onClick={() => setConfirm({ action: 'retire', system })} aria-label={`Retire ${system.name}`}>
 <Archive aria-hidden="true" size={14} strokeWidth={1.5} />
 </Button>
 )
 : (
 <Button type="button" variant="ghost" size="sm" onClick={() => setConfirm({ action: 'restore', system })} aria-label={`Restore ${system.name}`}>
 <ArchiveRestore aria-hidden="true" size={14} strokeWidth={1.5} />
 </Button>
 )}
 </div>
 </td>
 </tr>
 ))}
 </tbody>
 </table>
 </div>
 </Card>
 )}

 {form && (
 <SystemFormDialog
 key={form.mode === 'edit' ? form.system.id : 'create'}
 initial={form.mode === 'edit' ? form.system : null}
 onClose={() => setForm(null)}
 onSaved={saved => {
 setSystems(prev => {
 const list = prev ?? [];
 return form.mode === 'create' ? [saved, ...list] : list.map(s => (s.id === saved.id ? saved : s));
 });
 setForm(null);
 showToast(form.mode === 'create' ? `${saved.name} added` : `${saved.name} updated`, 'success');
 }}
 />
 )}

 {detail && (
 <SystemDetailDialog
 system={detail}
 onClose={() => setDetailId(null)}
 onEdit={() => { setDetailId(null); setForm({ mode: 'edit', system: detail }); }}
 onLifecycle={action => { setDetailId(null); setConfirm({ action, system: detail }); }}
 />
 )}

 {confirm && (
 <LifecycleConfirmDialog
 state={confirm}
 onClose={() => setConfirm(null)}
 onDone={updated => {
 applyUpdated(updated);
 setConfirm(null);
 showToast(
 confirm.action === 'retire' ? `${updated.name} retired` : `${updated.name} restored`,
 'success');
 }}
 />
 )}
 </div>
 );
}

function SystemFormDialog({
 initial,
 onClose,
 onSaved,
}: {
 initial: ApiInternalSystem | null;
 onClose: () => void;
 onSaved: (system: ApiInternalSystem) => void;
}) {
 const [name, setName] = useState(initial?.name ?? '');
 const [description, setDescription] = useState(initial?.description ?? '');
 const [ownerUserId, setOwnerUserId] = useState(initial?.ownerUserId ?? '');
 const [nameError, setNameError] = useState<string | undefined>(undefined);
 const [ownerError, setOwnerError] = useState<string | undefined>(undefined);
 const [formError, setFormError] = useState<string | null>(null);
 const [saving, setSaving] = useState(false);

 const submit = async () => {
 const trimmedName = name.trim();
 const trimmedOwner = ownerUserId.trim();
 setNameError(trimmedName ? undefined : 'Name is required');
 setOwnerError(trimmedOwner && !UUID_PATTERN.test(trimmedOwner) ? 'Owner must be a valid user ID (UUID)' : undefined);
 if (!trimmedName || (trimmedOwner && !UUID_PATTERN.test(trimmedOwner))) return;
 const payload: InternalSystemPayload = {
 name: trimmedName,
 description: description.trim() ? description.trim() : null,
 ownerUserId: trimmedOwner ? trimmedOwner : null,
 };
 setSaving(true);
 setFormError(null);
 try {
 const saved = initial ? await updateInternalSystem(initial.id, payload) : await createInternalSystem(payload);
 onSaved(saved);
 } catch (error) {
 setFormError(apiErrorMessage(error));
 setSaving(false);
 }
 };

 return (
 <Dialog open onClose={onClose} title={initial ? 'Edit internal system' : 'Add internal system'}>
 <div className="p-6">
 <h2 className="text-base font-semibold text-text-primary m-0">{initial ? 'Edit internal system' : 'Add internal system'}</h2>
 <p className="text-sm text-text-secondary mt-1">
 {initial
 ? 'Saving replaces every field — a field left blank is cleared on the system.'
 : 'The system is created in your current tenant and starts Active.'}
 </p>

 <div className="mt-5 space-y-4">
 <div>
 <label htmlFor="system-name" className="block text-xs font-medium text-text-secondary mb-1.5">Name <span className="text-risk-critical">*</span></label>
 <Input id="system-name" value={name} onChange={e => setName(e.target.value)} placeholder="e.g. Salesforce CRM" error={nameError} autoComplete="off" />
 </div>
 <div>
 <label htmlFor="system-description" className="block text-xs font-medium text-text-secondary mb-1.5">Description</label>
 <Textarea id="system-description" value={description} onChange={e => setDescription(e.target.value)} placeholder="What this system is and what it holds" />
 </div>
 <div>
 <label htmlFor="system-owner" className="block text-xs font-medium text-text-secondary mb-1.5">Owner user ID</label>
 <Input id="system-owner" value={ownerUserId} onChange={e => setOwnerUserId(e.target.value)} placeholder="Optional — must be a member of this tenant" error={ownerError} autoComplete="off" />
 </div>
 </div>

 {formError && (
 <p role="alert" className="mt-4 text-sm text-risk-critical bg-risk-critical/10 border border-risk-critical/30 rounded-lg px-3 py-2">
 {formError}
 </p>
 )}

 <div className="mt-6 flex items-center justify-end gap-2">
 <Button type="button" variant="ghost" onClick={onClose} disabled={saving}>Cancel</Button>
 <Button type="button" variant="primary" onClick={submit} isLoading={saving}>
 {initial ? 'Save changes' : 'Add system'}
 </Button>
 </div>
 </div>
 </Dialog>
 );
}

function SystemDetailDialog({
 system,
 onClose,
 onEdit,
 onLifecycle,
}: {
 system: ApiInternalSystem;
 onClose: () => void;
 onEdit: () => void;
 onLifecycle: (action: 'retire' | 'restore') => void;
}) {
 const rows: Array<[string, string]> = [
 ['Status', system.status],
 ['Owner', ownerLabel(system.ownerUserId)],
 ['Active access grants', String(system.activeGrantCount)],
 ['Description', system.description ?? '—'],
 ['Created', formatInstant(system.createdAt)],
 ['Last updated', formatInstant(system.updatedAt)],
 ];
 if (system.status === 'Retired') {
 rows.push(['Retired at', formatInstant(system.retiredAt)]);
 }
 return (
 <Dialog open onClose={onClose} title={system.name}>
 <div className="p-6">
 <div className="flex items-start justify-between gap-4">
 <h2 className="text-base font-semibold text-text-primary m-0">{system.name}</h2>
 {system.status === 'Active'
 ? <StatusBadge status="success" label="Active" />
 : <StatusBadge status="warning" label="Retired" />}
 </div>
 <dl className="mt-5 space-y-3">
 {rows.map(([label, value]) => (
 <div key={label} className="flex items-baseline justify-between gap-6 border-b border-border-subtle pb-2.5 last:border-0">
 <dt className="text-xs font-medium text-text-tertiary shrink-0">{label}</dt>
 <dd className="text-sm text-text-primary text-right m-0">{value}</dd>
 </div>
 ))}
 </dl>
 <div className="mt-6 flex items-center justify-end gap-2">
 <Button type="button" variant="ghost" onClick={onClose}>Close</Button>
 {system.status === 'Active' && (
 <Button type="button" variant="outline" onClick={onEdit}>
 <Pencil aria-hidden="true" size={14} strokeWidth={1.5} className="mr-1.5" /> Edit
 </Button>
 )}
 {system.status === 'Active'
 ? (
 <Button type="button" variant="danger" onClick={() => onLifecycle('retire')}>
 <Archive aria-hidden="true" size={14} strokeWidth={1.5} className="mr-1.5" /> Retire
 </Button>
 )
 : (
 <Button type="button" variant="primary" onClick={() => onLifecycle('restore')}>
 <ArchiveRestore aria-hidden="true" size={14} strokeWidth={1.5} className="mr-1.5" /> Restore
 </Button>
 )}
 </div>
 </div>
 </Dialog>
 );
}

function LifecycleConfirmDialog({
 state,
 onClose,
 onDone,
}: {
 state: ConfirmState;
 onClose: () => void;
 onDone: (system: ApiInternalSystem) => void;
}) {
 const { action, system } = state;
 const [busy, setBusy] = useState(false);
 const [error, setError] = useState<string | null>(null);

 const run = async () => {
 setBusy(true);
 setError(null);
 try {
 const updated = action === 'retire'
 ? await retireInternalSystem(system.id)
 : await restoreInternalSystem(system.id);
 onDone(updated);
 } catch (e) {
 setError(apiErrorMessage(e));
 setBusy(false);
 }
 };

 return (
 <Dialog open onClose={onClose} title={action === 'retire' ? 'Retire system' : 'Restore system'} disableBackdropClose={busy}>
 <div className="p-6">
 <h2 className="text-base font-semibold text-text-primary m-0">
 {action === 'retire' ? `Retire ${system.name}?` : `Restore ${system.name}?`}
 </h2>
 {action === 'retire' ? (
 <p className="text-sm text-text-secondary mt-2 leading-relaxed">
 The system stays in the inventory as Retired and can be restored later. Retiring never
 changes access grants — this system currently has {system.activeGrantCount} active
 grant{system.activeGrantCount === 1 ? '' : 's'}, and they are unaffected. New grants
 cannot be created against a retired system.
 </p>
 ) : (
 <p className="text-sm text-text-secondary mt-2 leading-relaxed">
 The system becomes Active again and can receive new access grants. If another active
 system has taken this name in the meantime, the restore will be refused.
 </p>
 )}
 {error && (
 <p role="alert" className="mt-4 text-sm text-risk-critical bg-risk-critical/10 border border-risk-critical/30 rounded-lg px-3 py-2">
 {error}
 </p>
 )}
 <div className="mt-6 flex items-center justify-end gap-2">
 <Button type="button" variant="ghost" onClick={onClose} disabled={busy}>Cancel</Button>
 <Button type="button" variant={action === 'retire' ? 'danger' : 'primary'} onClick={run} isLoading={busy}>
 {action === 'retire' ? 'Retire system' : 'Restore system'}
 </Button>
 </div>
 </div>
 </Dialog>
 );
}
