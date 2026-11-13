// src/features/ai/VendorBriefPanel.tsx
import { useState, useEffect, useRef } from 'react';
import { Sparkles, AlertTriangle, Ban, CheckCircle2, Loader2, XCircle } from 'lucide-react';
import { Card, Button } from '../../shared/ui/core';
import { agentsApi, VENDOR_INTELLIGENCE_AGENT_KEY, type AgentRun } from './agentsApi';
import { loadVendorPortfolio, apiErrorMessage } from '../vendors/service';
import type { VendorRecord } from '../../data/vendors';

/**
 * The one REAL intent in the Workbench: a Vendor Intelligence Brief produced
 * by an actual AgentEngine run (agent definition `vendor.intelligence`)
 * against a real backend vendor record. Everything rendered here — run
 * state, task states, errors, and the brief itself — comes from
 * /api/v1/agents. A failed run renders as a failure, never as a brief.
 */

interface BriefFact { text: string; source?: string }
interface ParsedBrief {
 summary?: string;
 keyFacts: BriefFact[];
 dataGaps: string[];
 questionsForVendor: string[];
 abstained: boolean;
 abstentionReason?: string;
}

function asStringList(value: unknown): string[] {
 if (!Array.isArray(value)) return [];
 return value.filter((v): v is string => typeof v === 'string' && v.trim().length > 0);
}

function parseFact(value: unknown): BriefFact | null {
 if (typeof value === 'string') {
  const trimmed = value.trim();
  if (!trimmed) return null;
  // The backend encodes each keyFact as a JSON object string
  // ({"fact", "source_kind", "source", "value"}); unwrap it when present.
  if (trimmed.startsWith('{')) {
   try {
    return parseFact(JSON.parse(trimmed));
   } catch {
    /* not JSON — fall through and treat as plain text */
   }
  }
  return { text: trimmed };
 }
 if (value && typeof value === 'object') {
 const o = value as Record<string, unknown>;
 const text = [o.fact, o.text, o.value, o.statement].find(v => typeof v === 'string' && v.trim());
 if (!text) return null;
 const source = [o.source, o.sourceField, o.field, o.source_field].find(v => typeof v === 'string' && v.trim());
 return { text: text as string, source: source as string | undefined };
 }
 return null;
}

/** Parse a brief-shaped object; returns null when it is not brief-shaped. */
function parseBriefObject(parsed: unknown): ParsedBrief | null {
 if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
 const o = parsed as Record<string, unknown>;
 const keyFacts = Array.isArray(o.keyFacts)
 ? o.keyFacts.map(parseFact).filter((f): f is BriefFact => f !== null)
 : [];
 // A brief-shaped object must carry at least one brief signal; any other
 // JSON object is not a brief and falls through to the raw-result warning.
 if (
  !(typeof o.summary === 'string' && o.summary.trim()) &&
  o.abstained !== true &&
  keyFacts.length === 0
 ) {
  return null;
 }
 return {
 summary: typeof o.summary === 'string' ? o.summary : undefined,
 keyFacts,
 dataGaps: asStringList(o.dataGaps),
 questionsForVendor: asStringList(o.questionsForVendor),
 abstained: o.abstained === true,
 abstentionReason: typeof o.abstentionReason === 'string' ? o.abstentionReason : undefined,
 };
}

/**
 * Extract the brief from a finished run. The engine's run-level `result` is
 * only a task-count summary ({"tasksSucceeded": N}); the vendor.intelligence
 * brief itself is the final task's `output` — a provenance envelope
 * ({schemaVersion, agentKey, vendorId, inputHash, model, brief}) whose
 * `brief` member is the brief. Returns the parsed brief plus the raw string
 * to show when the output is not brief-shaped.
 */
function briefFromRun(run: AgentRun): { brief: ParsedBrief | null; raw: string | null } {
 const candidates = [...run.tasks]
  .filter(t => t.output)
  .sort((a, b) => a.seq - b.seq);
 const briefTask =
  candidates.filter(t => t.toolKey === 'vendor.intelligence.brief').pop() ??
  candidates.pop();
 const raw = briefTask?.output ?? run.result;
 if (!raw) return { brief: null, raw: null };
 let parsed: unknown;
 try { parsed = JSON.parse(raw); } catch { return { brief: null, raw }; }
 if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
  const envelope = parsed as Record<string, unknown>;
  if (envelope.brief && typeof envelope.brief === 'object') {
   return { brief: parseBriefObject(envelope.brief), raw };
  }
 }
 return { brief: parseBriefObject(parsed), raw };
}

const TERMINAL: ReadonlySet<string> = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'WAITING_APPROVAL']);

export function VendorBriefPanel({ initialVendorId }: { initialVendorId?: string }) {
 const [vendors, setVendors] = useState<VendorRecord[] | null>(null);
 const [unavailable, setUnavailable] = useState<string | null>(null);
 const [selectedVendorId, setSelectedVendorId] = useState<string>(initialVendorId ?? '');
 const [run, setRun] = useState<AgentRun | null>(null);
 const [createError, setCreateError] = useState<string | null>(null);
 const [pollError, setPollError] = useState<string | null>(null);
 const [isCreating, setIsCreating] = useState(false);
 const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

 useEffect(() => {
 let cancelled = false;
 (async () => {
 const result = await loadVendorPortfolio();
 if (cancelled) return;
 if (result.source !== 'live') {
 setUnavailable(
 `The brief runs against real vendor records, and the live API is not reachable (${result.error ?? 'unknown error'}). Demo vendors cannot be briefed.`
 );
 setVendors([]);
 return;
 }
 setVendors(result.vendors);
 setSelectedVendorId(prev => prev || result.vendors[0]?.id || '');
 })();
 return () => { cancelled = true; };
 }, []);

 useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current); }, []);

 const startPolling = (runId: string) => {
 if (pollRef.current) clearInterval(pollRef.current);
 pollRef.current = setInterval(async () => {
 try {
 const latest = await agentsApi.getRun(runId);
 setRun(latest);
 setPollError(null);
 if (TERMINAL.has(latest.state) && pollRef.current) {
 clearInterval(pollRef.current);
 pollRef.current = null;
 }
 } catch (error) {
 setPollError(apiErrorMessage(error));
 }
 }, 1500);
 };

 const handleRequestBrief = async () => {
 const vendor = vendors?.find(v => v.id === selectedVendorId);
 if (!vendor) return;
 setIsCreating(true);
 setCreateError(null);
 setPollError(null);
 setRun(null);
 try {
 const created = await agentsApi.createRun({
 agentKey: VENDOR_INTELLIGENCE_AGENT_KEY,
 objective: `Vendor Intelligence Brief for ${vendor.name} (${vendor.code})`,
 mode: 'ASSIST',
 idempotencyKey: crypto.randomUUID(),
 context: JSON.stringify({ vendorId: vendor.id }),
 });
 setRun(created);
 if (!TERMINAL.has(created.state)) startPolling(created.id);
 } catch (error) {
 setCreateError(apiErrorMessage(error));
 } finally {
 setIsCreating(false);
 }
 };

 const briefOutcome = run && run.state === 'SUCCEEDED' ? briefFromRun(run) : null;
 const brief = briefOutcome?.brief ?? null;
 const isRunning = !!run && (run.state === 'PENDING' || run.state === 'RUNNING');

 return (
 <Card className="shadow-sm">
 <div className="p-5 space-y-4">
 <div className="flex items-start justify-between gap-3">
 <div>
 <h2 className="text-sm font-bold text-text-primary m-0 flex items-center gap-2">
 Vendor Intelligence Brief
 <span className="text-[10px] font-semibold uppercase tracking-wider px-2 py-0.5 rounded bg-status-success/10 text-status-success border border-status-success/20">
 Real · AgentEngine
 </span>
 </h2>
 <p className="text-xs text-text-secondary mt-1 m-0">
 Runs the <span className="font-mono">vendor.intelligence</span> agent on the existing engine against a
 real vendor record. Facts in the brief cite the record fields they came from; the agent invents no
 scores and claims no external verification.
 </p>
 </div>
 </div>

 {unavailable ? (
 <div role="alert" className="flex items-start gap-2.5 rounded-lg border border-status-warning/30 bg-status-warning/10 px-3.5 py-3">
 <AlertTriangle aria-hidden="true" size={16} className="text-status-warning shrink-0 mt-0.5" />
 <p className="text-sm text-text-secondary m-0">{unavailable}</p>
 </div>
 ) : vendors === null ? (
 <p role="status" className="text-sm text-text-tertiary m-0">Loading vendors from the API…</p>
 ) : vendors.length === 0 ? (
 <p className="text-sm text-text-tertiary m-0">No vendors exist in this tenant yet — add one in the Vendors tab first.</p>
 ) : (
 <div className="flex flex-col sm:flex-row gap-2.5">
 <label className="sr-only" htmlFor="brief-vendor">Vendor</label>
 <select
 id="brief-vendor"
 value={selectedVendorId}
 onChange={e => setSelectedVendorId(e.target.value)}
 className="flex-1 bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:ring-2 focus:ring-info"
 >
 {vendors.map(v => (
 <option key={v.id} value={v.id}>{v.name} ({v.code}){v.status === 'Terminated' ? ' — Terminated' : ''}</option>
 ))}
 </select>
 <Button variant="primary" size="sm" onClick={handleRequestBrief} disabled={!selectedVendorId || isCreating || isRunning} className="h-[38px]">
 <Sparkles aria-hidden="true" size={14} className="mr-1.5" />
 {isCreating ? 'Starting run…' : 'Request Brief'}
 </Button>
 </div>
 )}

 {createError && (
 <div role="alert" className="flex items-start gap-2.5 rounded-lg border border-risk-critical/30 bg-risk-critical/10 px-3.5 py-3">
 <XCircle aria-hidden="true" size={16} className="text-risk-critical shrink-0 mt-0.5" />
 <div>
 <p className="text-sm font-bold text-risk-critical m-0">The run could not be created</p>
 <p className="text-sm text-text-secondary mt-0.5 m-0">{createError}</p>
 </div>
 </div>
 )}

 {run && (
 <div className="rounded-xl border border-border bg-base p-4 space-y-4" aria-live="polite">
 <div className="flex flex-wrap items-center gap-2 text-xs">
 <span className="font-bold text-text-primary">Run</span>
 <span className="font-mono text-text-tertiary">{run.id.slice(0, 8)}…</span>
 <span className="text-text-tertiary">·</span>
 <span className="text-text-secondary">{run.agentKey}</span>
 <span className="text-text-tertiary">·</span>
 <span className="text-text-secondary">Mode: {run.mode}</span>
 <span className={`inline-flex items-center gap-1.5 font-semibold uppercase tracking-wider text-[10px] px-2 py-0.5 rounded border ${
 run.state === 'SUCCEEDED' ? 'bg-status-success/10 text-status-success border-status-success/20'
 : run.state === 'FAILED' || run.state === 'CANCELLED' ? 'bg-risk-critical/10 text-risk-critical border-risk-critical/20'
 : run.state === 'WAITING_APPROVAL' ? 'bg-status-warning/10 text-status-warning border-status-warning/20'
 : 'bg-info/10 text-info border-info/20'
 }`}>
 {isRunning && <Loader2 aria-hidden="true" size={11} className="animate-spin" />}
 {run.state}
 </span>
 </div>

 {run.tasks.length > 0 && (
 <ol className="space-y-1.5 m-0 p-0 list-none">
 {run.tasks.map(task => (
 <li key={task.id} className="flex flex-wrap items-center gap-2 text-xs">
 <span className="font-mono text-text-tertiary w-6">{task.seq}.</span>
 <span className="font-mono text-text-primary">{task.toolKey}</span>
 <span className="text-text-tertiary">({task.riskClass})</span>
 <span className={`font-semibold ${
 task.state === 'SUCCEEDED' ? 'text-status-success'
 : task.state === 'FAILED' ? 'text-risk-critical'
 : 'text-text-secondary'
 }`}>{task.state}</span>
 {task.attempts > 1 && <span className="text-text-tertiary">· {task.attempts} attempts</span>}
 {task.error && <span className="text-risk-critical">— {task.error}</span>}
 </li>
 ))}
 </ol>
 )}

 {pollError && (
 <p className="text-xs text-status-warning m-0">Status refresh failed ({pollError}) — retrying automatically.</p>
 )}

 {run.state === 'WAITING_APPROVAL' && (
 <div className="flex items-start gap-2.5 rounded-lg border border-status-warning/30 bg-status-warning/10 px-3.5 py-3">
 <AlertTriangle aria-hidden="true" size={16} className="text-status-warning shrink-0 mt-0.5" />
 <p className="text-sm text-text-secondary m-0">
 This run is parked waiting for a human approval decision in the engine. It has not produced a brief.
 </p>
 </div>
 )}

 {run.state === 'FAILED' && (
 <div role="alert" className="flex items-start gap-2.5 rounded-lg border border-risk-critical/30 bg-risk-critical/10 px-3.5 py-3">
 <XCircle aria-hidden="true" size={16} className="text-risk-critical shrink-0 mt-0.5" />
 <div>
 <p className="text-sm font-bold text-risk-critical m-0">Run failed — no brief was produced</p>
 <p className="text-sm text-text-secondary mt-0.5 m-0">{run.error ?? 'The engine reported a failure without a message.'}</p>
 </div>
 </div>
 )}

 {run.state === 'CANCELLED' && (
 <p className="text-sm text-text-secondary m-0">This run was cancelled. No brief was produced.</p>
 )}

 {run.state === 'SUCCEEDED' && !brief && (
 <div role="alert" className="flex items-start gap-2.5 rounded-lg border border-status-warning/30 bg-status-warning/10 px-3.5 py-3">
 <AlertTriangle aria-hidden="true" size={16} className="text-status-warning shrink-0 mt-0.5" />
 <div>
 <p className="text-sm font-bold text-text-primary m-0">The run finished, but its result is not a valid brief</p>
 <p className="text-sm text-text-secondary mt-0.5 m-0">The result is shown raw below and is NOT rendered as a brief.</p>
 {briefOutcome?.raw && (
 <pre className="mt-2 max-h-48 overflow-auto rounded-lg bg-surface border border-border p-3 text-[11px] text-text-secondary whitespace-pre-wrap">{briefOutcome.raw}</pre>
 )}
 </div>
 </div>
 )}

 {run.state === 'SUCCEEDED' && brief && brief.abstained && (
 <div className="flex items-start gap-2.5 rounded-lg border border-status-warning/30 bg-status-warning/10 px-3.5 py-3">
 <Ban aria-hidden="true" size={16} className="text-status-warning shrink-0 mt-0.5" />
 <div>
 <p className="text-sm font-bold text-text-primary m-0">The agent abstained</p>
 <p className="text-sm text-text-secondary mt-0.5 m-0">
 {brief.abstentionReason ?? 'The vendor record does not contain enough information for a defensible brief.'}
 </p>
 {brief.dataGaps.length > 0 && (
 <>
 <p className="text-xs font-bold uppercase tracking-wider text-text-tertiary mt-3 mb-1">Missing information</p>
 <ul className="list-disc pl-5 space-y-0.5 text-sm text-text-secondary m-0">
 {brief.dataGaps.map((gap, i) => <li key={i}>{gap}</li>)}
 </ul>
 </>
 )}
 </div>
 </div>
 )}

 {run.state === 'SUCCEEDED' && brief && !brief.abstained && (
 <div className="space-y-4">
 <div className="flex items-center gap-2 text-status-success">
 <CheckCircle2 aria-hidden="true" size={15} />
 <span className="text-xs font-bold uppercase tracking-wider">Brief — from the vendor record</span>
 </div>
 {brief.summary && <p className="text-sm text-text-primary m-0 leading-relaxed">{brief.summary}</p>}
 {brief.keyFacts.length > 0 && (
 <div>
 <p className="text-xs font-bold uppercase tracking-wider text-text-tertiary mb-1.5">Key facts</p>
 <ul className="space-y-1.5 m-0 p-0 list-none">
 {brief.keyFacts.map((fact, i) => (
 <li key={i} className="text-sm text-text-secondary flex flex-wrap items-baseline gap-2">
 <span>{fact.text}</span>
 {fact.source && (
 <span className="text-[10px] font-mono font-semibold uppercase tracking-wider px-1.5 py-0.5 rounded bg-info/10 text-info border border-info/20">
 source: {fact.source}
 </span>
 )}
 </li>
 ))}
 </ul>
 </div>
 )}
 {brief.dataGaps.length > 0 && (
 <div>
 <p className="text-xs font-bold uppercase tracking-wider text-text-tertiary mb-1.5">Data gaps</p>
 <ul className="list-disc pl-5 space-y-0.5 text-sm text-text-secondary m-0">
 {brief.dataGaps.map((gap, i) => <li key={i}>{gap}</li>)}
 </ul>
 </div>
 )}
 {brief.questionsForVendor.length > 0 && (
 <div>
 <p className="text-xs font-bold uppercase tracking-wider text-text-tertiary mb-1.5">Questions for the vendor</p>
 <ul className="list-disc pl-5 space-y-0.5 text-sm text-text-secondary m-0">
 {brief.questionsForVendor.map((q, i) => <li key={i}>{q}</li>)}
 </ul>
 </div>
 )}
 {!brief.summary && brief.keyFacts.length === 0 && brief.dataGaps.length === 0 && brief.questionsForVendor.length === 0 && (
 <p className="text-sm text-text-tertiary m-0">The brief came back empty — no summary, facts, gaps or questions were returned.</p>
 )}
 </div>
 )}
 </div>
 )}
 </div>
 </Card>
 );
}
