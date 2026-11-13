// src/features/vendors/LiveVendorDetail.tsx
import { useState, useEffect } from 'react';
import { Building2, Server, Lock, Sparkles, Pencil, UserX, Plus, Trash2 } from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Button } from '../../shared/ui/core';
import { Dialog } from '../../shared/ui/Dialog';
import { navigateTo } from '../../shared/navigation';
import type { ApiVendor, ApiDependentSystem, ApiCriticality } from './api';
import {
 fetchDependentSystemsOnApi,
 replaceDependentSystemsOnApi,
 updateVendorOnApi,
 terminateVendorOnApi,
 apiErrorMessage,
 ownerLabel,
} from './service';
import { showToast } from '../../shared/ui/toast';

/**
 * Detail view for a REAL backend vendor record. Everything shown comes from
 * the API: identity, lifecycle, contract and dependent systems. There are no
 * risk scores, evidence or remediation sections — those do not exist for API
 * records yet, and this view says so instead of borrowing the demo story.
 */

const dash = (v: string | null | undefined) => (v && v.trim() ? v : '—');

interface SystemRow { name: string; dataHandled: string; residency: string }

export function LiveVendorDetail({ initialVendor, onBack }: { initialVendor: ApiVendor; onBack?: () => void }) {
 const [vendor, setVendor] = useState<ApiVendor>(initialVendor);
 const [systems, setSystems] = useState<ApiDependentSystem[] | null>(null);
 const [systemsError, setSystemsError] = useState<string | null>(null);
 const [pageError, setPageError] = useState<string | null>(null);

 const [isEditOpen, setIsEditOpen] = useState(false);
 const [editForm, setEditForm] = useState({
 name: '', legalName: '', lei: '', jurisdiction: '', category: '', domain: '',
 website: '', description: '', criticality: 'Tier 3' as ApiCriticality,
 ownerUserId: '', contractStart: '', contractEnd: '', tieringComplete: false,
 });
 const [editError, setEditError] = useState<string | null>(null);
 const [isSaving, setIsSaving] = useState(false);

 const [isTerminateOpen, setIsTerminateOpen] = useState(false);
 const [isTerminating, setIsTerminating] = useState(false);

 const [isSystemsEditing, setIsSystemsEditing] = useState(false);
 const [systemRows, setSystemRows] = useState<SystemRow[]>([]);
 const [systemsSaveError, setSystemsSaveError] = useState<string | null>(null);
 const [isSavingSystems, setIsSavingSystems] = useState(false);

 const isTerminated = vendor.status === 'Terminated';

 useEffect(() => {
 let cancelled = false;
 (async () => {
 try {
 const list = await fetchDependentSystemsOnApi(vendor.id);
 if (!cancelled) setSystems(list);
 } catch (error) {
 if (!cancelled) setSystemsError(apiErrorMessage(error));
 }
 })();
 return () => { cancelled = true; };
 }, [vendor.id]);

 const openEdit = () => {
 setEditForm({
 name: vendor.name,
 legalName: vendor.legalName ?? '',
 lei: vendor.lei ?? '',
 jurisdiction: vendor.jurisdiction ?? '',
 category: vendor.category ?? '',
 domain: vendor.domain ?? '',
 website: vendor.website ?? '',
 description: vendor.description ?? '',
 criticality: vendor.criticality,
 ownerUserId: vendor.ownerUserId ?? '',
 contractStart: vendor.contractStart ?? '',
 contractEnd: vendor.contractEnd ?? '',
 tieringComplete: vendor.tieringComplete,
 });
 setEditError(null);
 setIsEditOpen(true);
 };

 const handleSaveEdit = async () => {
 const ownerRaw = editForm.ownerUserId.trim();
 if (ownerRaw && !/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(ownerRaw)) {
 setEditError('Owner must be a user UUID (or left empty) — the API does not accept display names.');
 return;
 }
 setIsSaving(true);
 setEditError(null);
 try {
 const updated = await updateVendorOnApi(vendor.id, {
 name: editForm.name.trim(),
 legalName: editForm.legalName.trim() || null,
 lei: editForm.lei.trim() || null,
 jurisdiction: editForm.jurisdiction.trim() || null,
 category: editForm.category.trim() || null,
 domain: editForm.domain.trim() || null,
 website: editForm.website.trim() || null,
 description: editForm.description.trim() || null,
 criticality: editForm.criticality,
 ownerUserId: ownerRaw || null,
 contractStart: editForm.contractStart || null,
 contractEnd: editForm.contractEnd || null,
 tieringComplete: editForm.tieringComplete,
 });
 setVendor(updated);
 setIsEditOpen(false);
 showToast(`${updated.name} updated`, 'success');
 } catch (error) {
 setEditError(apiErrorMessage(error));
 } finally {
 setIsSaving(false);
 }
 };

 const handleTerminate = async () => {
 setIsTerminating(true);
 setPageError(null);
 try {
 const updated = await terminateVendorOnApi(vendor.id);
 setVendor(updated);
 setIsTerminateOpen(false);
 showToast(`${updated.name} terminated`, 'success');
 } catch (error) {
 setPageError(apiErrorMessage(error));
 setIsTerminateOpen(false);
 } finally {
 setIsTerminating(false);
 }
 };

 const openSystemsEditor = () => {
 setSystemRows((systems ?? []).map(s => ({
 name: s.name, dataHandled: s.dataHandled ?? '', residency: s.residency ?? '',
 })));
 setSystemsSaveError(null);
 setIsSystemsEditing(true);
 };

 const handleSaveSystems = async () => {
 if (systemRows.some(r => !r.name.trim())) {
 setSystemsSaveError('every dependent system needs a name');
 return;
 }
 setIsSavingSystems(true);
 setSystemsSaveError(null);
 try {
 const saved = await replaceDependentSystemsOnApi(vendor.id, systemRows.map(r => ({
 name: r.name.trim(),
 dataHandled: r.dataHandled.trim() || null,
 residency: r.residency.trim() || null,
 })));
 setSystems(saved);
 setIsSystemsEditing(false);
 showToast('Dependent systems updated', 'success');
 } catch (error) {
 setSystemsSaveError(apiErrorMessage(error));
 } finally {
 setIsSavingSystems(false);
 }
 };

 const inputCls = 'w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info';
 const labelCls = 'block text-xs font-semibold text-text-secondary mb-1.5';

 return (
 <div className="space-y-8 max-w-7xl mx-auto pb-28 animate-in fade-in duration-200">
 <div className="flex items-center justify-between border-b border-border pb-4">
 <button
 type="button"
 onClick={onBack}
 className="text-xs font-semibold text-text-tertiary hover:text-text-primary transition-colors flex items-center gap-1.5 focus:outline-none focus:ring-2 focus:ring-info rounded"
 >
 <span>← Back to Vendor Portfolio</span>
 </button>
 <span className="inline-flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wider text-status-success">
 <span aria-hidden="true" className="w-1.5 h-1.5 rounded-full bg-status-success"></span>
 Live record
 </span>
 </div>

 {pageError && (
 <div role="alert" className="rounded-xl border border-risk-critical/30 bg-risk-critical/10 px-4 py-3 text-sm text-risk-critical">
 {pageError}
 </div>
 )}

 <Card className="shadow-sm overflow-visible">
 <div className="bg-surface p-6 sm:p-7 flex flex-col lg:flex-row lg:items-start justify-between gap-6 border-b border-border rounded-xl">
 <div className="flex items-start gap-4 min-w-0">
 <div aria-hidden="true" className="w-12 h-12 rounded-lg bg-base border border-border-strong flex items-center justify-center text-text-primary shadow-sm shrink-0 mt-0.5">
 <Building2 size={24} strokeWidth={1.5} />
 </div>
 <div className="min-w-0">
 <div className="flex flex-wrap items-center gap-2 mb-1">
 <h1 className="text-lg sm:text-xl font-bold text-text-primary tracking-tight truncate m-0">{vendor.name}</h1>
 <span className="text-xs font-mono text-text-tertiary bg-base px-2 py-0.5 rounded border border-border">{vendor.code}</span>
 <span className="text-[11px] font-bold uppercase tracking-wider px-2 py-0.5 rounded bg-risk-critical/10 text-risk-critical">{vendor.criticality}</span>
 {isTerminated && (
 <span className="text-[11px] font-bold uppercase tracking-wider px-2 py-0.5 rounded bg-base text-text-tertiary border border-border-strong">Terminated</span>
 )}
 </div>
 <p className="text-xs text-text-secondary flex flex-wrap items-center gap-2 m-0">
 <span className="font-medium">{dash(vendor.category)}</span>
 <span aria-hidden="true" className="text-text-tertiary">•</span>
 <span className="font-mono">{dash(vendor.domain ?? vendor.website)}</span>
 <span aria-hidden="true" className="text-text-tertiary">•</span>
 <span>{vendor.status}</span>
 </p>
 {vendor.description && (
 <p className="text-sm text-text-secondary mt-3 m-0 max-w-2xl">{vendor.description}</p>
 )}
 </div>
 </div>
 <div className="flex flex-wrap items-center gap-2 shrink-0">
 <Button variant="primary" size="sm" onClick={() => navigateTo('ai-playground', { vendorId: vendor.id, vendorName: vendor.name })}>
 <Sparkles aria-hidden="true" size={14} className="mr-1.5" />
 Request Intelligence Brief
 </Button>
 <Button variant="secondary" size="sm" onClick={openEdit} disabled={isTerminated}>
 <Pencil aria-hidden="true" size={14} className="mr-1.5" />
 Edit Record
 </Button>
 {!isTerminated && (
 <Button variant="secondary" size="sm" onClick={() => setIsTerminateOpen(true)} className="text-risk-critical">
 <UserX aria-hidden="true" size={14} className="mr-1.5" />
 Terminate
 </Button>
 )}
 </div>
 </div>

 <div className="p-6 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-x-8 gap-y-4">
 {[
 { label: 'Legal name', value: dash(vendor.legalName) },
 { label: 'LEI', value: dash(vendor.lei), mono: true },
 { label: 'Jurisdiction', value: dash(vendor.jurisdiction) },
 { label: 'Owner', value: ownerLabel(vendor.ownerUserId), title: vendor.ownerUserId ?? undefined },
 { label: 'Website', value: dash(vendor.website) },
 { label: 'Domain', value: dash(vendor.domain), mono: true },
 { label: 'Contract start', value: dash(vendor.contractStart), mono: true },
 { label: 'Contract end', value: dash(vendor.contractEnd), mono: true },
 { label: 'Tiering complete', value: vendor.tieringComplete ? 'Yes' : 'No' },
 ].map(field => (
 <div key={field.label}>
 <span className="block text-[11px] font-bold uppercase tracking-wider text-text-tertiary">{field.label}</span>
 <span className={`block text-sm text-text-primary mt-0.5 ${field.mono ? 'font-mono' : ''}`} title={field.title}>{field.value}</span>
 </div>
 ))}
 </div>
 </Card>

 <Card className="shadow-sm">
 <CardContent className="p-6">
 <h2 className="text-sm font-bold text-text-primary m-0">Risk posture</h2>
 <p className="text-sm text-text-secondary mt-1.5 m-0">
 Not assessed. This API record carries identity and lifecycle data only — risk scoring belongs to
 the assessments workflow and the scoring model, neither of which has run for this vendor. No score
 is shown because none exists.
 </p>
 </CardContent>
 </Card>

 <Card className="shadow-sm">
 <CardHeader className="border-b border-border px-6 py-4 flex items-center justify-between">
 <CardTitle className="flex items-center gap-2 text-sm font-bold uppercase tracking-wider">
 <Server size={18} strokeWidth={1.5} className="text-text-secondary" />
 Dependent Systems & Data Scope
 </CardTitle>
 {!isSystemsEditing && !isTerminated && systems !== null && (
 <Button variant="secondary" size="sm" onClick={openSystemsEditor}>Edit Systems</Button>
 )}
 </CardHeader>
 <CardContent className="p-6 space-y-4">
 {systemsError && (
 <div role="alert" className="rounded-lg border border-risk-critical/30 bg-risk-critical/10 px-3 py-2 text-sm text-risk-critical">{systemsError}</div>
 )}
 {systems === null && !systemsError && (
 <p className="text-xs text-text-tertiary m-0">Loading dependent systems…</p>
 )}
 {!isSystemsEditing && systems !== null && (
 <>
 {systems.map(sys => (
 <div key={sys.id} className="p-3.5 rounded-lg border border-border bg-surface space-y-1">
 <div className="flex items-center justify-between">
 <span className="text-sm font-bold text-text-primary">{sys.name}</span>
 <span className="text-[11px] font-mono font-semibold text-info bg-info/5 px-2 py-0.5 rounded border border-info/20">{dash(sys.residency)}</span>
 </div>
 <p className="text-xs text-text-secondary flex items-center gap-1.5 pt-0.5 m-0">
 <Lock size={12} strokeWidth={1.5} className="text-text-tertiary" />
 <span>Data Classification: <strong className="text-text-primary font-medium">{dash(sys.dataHandled)}</strong></span>
 </p>
 </div>
 ))}
 {systems.length === 0 && (
 <p className="text-xs text-text-tertiary text-center py-4 m-0">No dependent systems recorded for this vendor yet.</p>
 )}
 </>
 )}
 {isSystemsEditing && (
 <div className="space-y-3">
 {systemRows.map((row, idx) => (
 <div key={idx} className="grid grid-cols-1 sm:grid-cols-[1fr_1fr_1fr_auto] gap-2 items-end">
 <div>
 <label className={labelCls} htmlFor={`sys-name-${idx}`}>System name</label>
 <input id={`sys-name-${idx}`} className={inputCls} value={row.name} onChange={e => setSystemRows(rows => rows.map((r, i) => i === idx ? { ...r, name: e.target.value } : r))} placeholder="e.g. Core Billing Engine" />
 </div>
 <div>
 <label className={labelCls} htmlFor={`sys-data-${idx}`}>Data handled</label>
 <input id={`sys-data-${idx}`} className={inputCls} value={row.dataHandled} onChange={e => setSystemRows(rows => rows.map((r, i) => i === idx ? { ...r, dataHandled: e.target.value } : r))} placeholder="e.g. Customer PII" />
 </div>
 <div>
 <label className={labelCls} htmlFor={`sys-res-${idx}`}>Residency</label>
 <input id={`sys-res-${idx}`} className={inputCls} value={row.residency} onChange={e => setSystemRows(rows => rows.map((r, i) => i === idx ? { ...r, residency: e.target.value } : r))} placeholder="e.g. AWS eu-west-1" />
 </div>
 <button type="button" aria-label={`Remove system ${idx + 1}`} onClick={() => setSystemRows(rows => rows.filter((_, i) => i !== idx))} className="p-2 rounded-lg border border-border text-text-tertiary hover:text-risk-critical hover:border-risk-critical/40 focus:outline-none focus:ring-2 focus:ring-info">
 <Trash2 size={15} />
 </button>
 </div>
 ))}
 <button type="button" onClick={() => setSystemRows(rows => [...rows, { name: '', dataHandled: '', residency: '' }])} className="inline-flex items-center gap-1.5 text-xs font-semibold text-info hover:underline focus:outline-none focus:ring-2 focus:ring-info rounded px-1">
 <Plus size={13} /> Add a system
 </button>
 {systemsSaveError && (
 <div role="alert" className="rounded-lg border border-risk-critical/30 bg-risk-critical/10 px-3 py-2 text-sm text-risk-critical">{systemsSaveError}</div>
 )}
 <p className="text-[11px] text-text-tertiary m-0">Saving replaces the full list for this vendor (the API stores dependent systems as a set).</p>
 <div className="flex justify-end gap-2">
 <Button variant="secondary" size="sm" onClick={() => setIsSystemsEditing(false)}>Cancel</Button>
 <Button variant="primary" size="sm" onClick={handleSaveSystems} disabled={isSavingSystems}>{isSavingSystems ? 'Saving…' : 'Save Systems'}</Button>
 </div>
 </div>
 )}
 </CardContent>
 </Card>

 <Dialog open={isEditOpen} onClose={() => setIsEditOpen(false)} title={`Edit ${vendor.name}`} maxWidth="max-w-lg">
 <div className="p-6 space-y-4">
 <div>
 <h2 className="text-base font-bold text-text-primary m-0">Edit Vendor Record</h2>
 <p className="text-xs text-text-secondary mt-1 m-0">
 Code <span className="font-mono font-semibold text-text-primary">{vendor.code}</span> is immutable and not part of the update.
 Saving replaces the full record — every field below is sent.
 </p>
 </div>
 <div>
 <label className={labelCls} htmlFor="edit-name">Vendor name</label>
 <input id="edit-name" className={inputCls} value={editForm.name} onChange={e => setEditForm(f => ({ ...f, name: e.target.value }))} />
 </div>
 <div className="grid grid-cols-2 gap-3">
 <div>
 <label className={labelCls} htmlFor="edit-legal">Legal name</label>
 <input id="edit-legal" className={inputCls} value={editForm.legalName} onChange={e => setEditForm(f => ({ ...f, legalName: e.target.value }))} />
 </div>
 <div>
 <label className={labelCls} htmlFor="edit-jurisdiction">Jurisdiction</label>
 <input id="edit-jurisdiction" className={inputCls} value={editForm.jurisdiction} onChange={e => setEditForm(f => ({ ...f, jurisdiction: e.target.value }))} />
 </div>
 </div>
 <div className="grid grid-cols-2 gap-3">
 <div>
 <label className={labelCls} htmlFor="edit-lei">LEI (20 characters)</label>
 <input id="edit-lei" className={inputCls} value={editForm.lei} onChange={e => setEditForm(f => ({ ...f, lei: e.target.value }))} />
 </div>
 <div>
 <label className={labelCls} htmlFor="edit-category">Category</label>
 <input id="edit-category" className={inputCls} value={editForm.category} onChange={e => setEditForm(f => ({ ...f, category: e.target.value }))} />
 </div>
 </div>
 <div className="grid grid-cols-2 gap-3">
 <div>
 <label className={labelCls} htmlFor="edit-domain">Domain</label>
 <input id="edit-domain" className={inputCls} value={editForm.domain} onChange={e => setEditForm(f => ({ ...f, domain: e.target.value }))} />
 </div>
 <div>
 <label className={labelCls} htmlFor="edit-website">Website</label>
 <input id="edit-website" className={inputCls} value={editForm.website} onChange={e => setEditForm(f => ({ ...f, website: e.target.value }))} />
 </div>
 </div>
 <div>
 <label className={labelCls} htmlFor="edit-desc">Description</label>
 <textarea id="edit-desc" className={inputCls} rows={3} value={editForm.description} onChange={e => setEditForm(f => ({ ...f, description: e.target.value }))} />
 </div>
 <div className="grid grid-cols-2 gap-3">
 <div>
 <label className={labelCls} htmlFor="edit-criticality">Criticality (required)</label>
 <select id="edit-criticality" className={inputCls} value={editForm.criticality} onChange={e => setEditForm(f => ({ ...f, criticality: e.target.value as ApiCriticality }))}>
 <option value="Tier 1">Tier 1 — Critical</option>
 <option value="Tier 2">Tier 2 — Important</option>
 <option value="Tier 3">Tier 3 — Standard</option>
 </select>
 </div>
 <div>
 <label className={labelCls} htmlFor="edit-owner">Owner user ID</label>
 <input id="edit-owner" className={inputCls} value={editForm.ownerUserId} onChange={e => setEditForm(f => ({ ...f, ownerUserId: e.target.value }))} placeholder="UUID or empty" />
 </div>
 </div>
 <div className="grid grid-cols-2 gap-3">
 <div>
 <label className={labelCls} htmlFor="edit-start">Contract start</label>
 <input id="edit-start" type="date" className={inputCls} value={editForm.contractStart} onChange={e => setEditForm(f => ({ ...f, contractStart: e.target.value }))} />
 </div>
 <div>
 <label className={labelCls} htmlFor="edit-end">Contract end</label>
 <input id="edit-end" type="date" className={inputCls} value={editForm.contractEnd} onChange={e => setEditForm(f => ({ ...f, contractEnd: e.target.value }))} />
 </div>
 </div>
 <label className="flex items-center gap-2.5 text-sm text-text-secondary cursor-pointer">
 <input type="checkbox" checked={editForm.tieringComplete} onChange={e => setEditForm(f => ({ ...f, tieringComplete: e.target.checked }))} className="w-4 h-4 rounded accent-accent" />
 Tiering questionnaire completed
 </label>
 {editError && (
 <div role="alert" className="rounded-lg border border-risk-critical/30 bg-risk-critical/10 px-3 py-2 text-sm text-risk-critical">{editError}</div>
 )}
 <div className="flex justify-end gap-2 pt-1">
 <Button variant="secondary" size="sm" onClick={() => setIsEditOpen(false)}>Cancel</Button>
 <Button variant="primary" size="sm" onClick={handleSaveEdit} disabled={!editForm.name.trim() || isSaving}>{isSaving ? 'Saving…' : 'Save Changes'}</Button>
 </div>
 </div>
 </Dialog>

 <Dialog open={isTerminateOpen} onClose={() => setIsTerminateOpen(false)} title={`Terminate ${vendor.name}`} maxWidth="max-w-md">
 <div className="p-6 space-y-4">
 <div>
 <h2 className="text-base font-bold text-text-primary m-0">Terminate {vendor.name}?</h2>
 <p className="text-sm text-text-secondary mt-1.5 m-0">
 This sets the vendor's status to Terminated on the server. The record is kept for audit and
 dependency history — termination is a lifecycle status, not a deletion. Terminating an
 already-terminated vendor is safe (the API treats it as a no-op).
 </p>
 </div>
 <div className="flex justify-end gap-2">
 <Button variant="secondary" size="sm" onClick={() => setIsTerminateOpen(false)}>Cancel</Button>
 <Button variant="primary" size="sm" onClick={handleTerminate} disabled={isTerminating} className="bg-risk-critical hover:bg-risk-critical/90 border-risk-critical">
 {isTerminating ? 'Terminating…' : 'Terminate Vendor'}
 </Button>
 </div>
 </div>
 </Dialog>
 </div>
 );
}
