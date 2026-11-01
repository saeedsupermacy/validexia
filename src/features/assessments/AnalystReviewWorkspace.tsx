// src/features/assessments/AnalystReviewWorkspace.tsx
import { useState, useEffect } from 'react';
import {
 ArrowLeft, FileText, HelpCircle, History, Check, X, AlertTriangle 
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Button } from '../../shared/ui/core';
import { navigateTo } from '../../shared/navigation';

export type ReviewDecision = 'Approve' | 'Reject' | 'Clarify' | 'Accept risk';

export interface AuditHistoryEvent {
 timestamp: string;
 author: string;
 action: string;
 details: string;
}

export interface ReviewItem {
 id: string;
 controlId: string;
 question: string;
 vendorAnswer: string;
 evidenceReference: string;
 evidenceCitation: string;
 expectedRequirement: string;
 analystAssessment: string;
 currentDecision: ReviewDecision | 'Pending';
 comment: string;
 history: AuditHistoryEvent[];
}

const DEV_REVIEW_ITEMS: ReviewItem[] = [
 {
 id: 'rev-01',
 controlId: 'CC6.1',
 question: 'Are administrative access tokens restricted to a maximum 8-hour session validity?',
 vendorAnswer: 'Yes, admin tokens expire after 8 hours of continuous inactivity or 12 hours absolute time.',
 evidenceReference: 'CloudScale_Access_Policy_v4.pdf',
 evidenceCitation: 'Page 9, Section 3.2 (Session Lifecycle Limits)',
 expectedRequirement: 'Maximum absolute session limit must not exceed 12 hours with mandatory re-authentication.',
 analystAssessment: 'Vendor policy aligns with enterprise baseline. Evidence citation verified against technical configuration logs.',
 currentDecision: 'Approve',
 comment: 'Verified against technical config extract. Compliant with baseline.',
 history: [
 { timestamp: 'Oct 02, 2026 - 14:22', author: 'AI Parser', action: 'Extracted fact', details: 'Parsed absolute limit as 12 hours.' },
 { timestamp: 'Oct 03, 2026 - 09:15', author: 'Sarah Jenkins', action: 'Decision: Approve', details: 'Confirmed session timeout policy matches security requirement.' }
 ]
 },
 {
 id: 'rev-02',
 controlId: 'CC7.2',
 question: 'Is multi-factor authentication (MFA) cryptographically enforced using FIDO2 / WebAuthn standards across all environments?',
 vendorAnswer: 'MFA is enforced using TOTP app authenticators and SMS backup codes.',
 evidenceReference: 'SecureAuth_ISO27001_Audit_Q4.pdf',
 evidenceCitation: 'Page 12, Clause 9.4 (Authentication Mechanisms)',
 expectedRequirement: 'Phishing-resistant MFA (FIDO2/WebAuthn or Hardware Keys) required for all production environments. SMS OTP is deprecated.',
 analystAssessment: 'Vendor relies on SMS backup codes which violates modern phishing-resistance requirements.',
 currentDecision: 'Reject',
 comment: 'SMS backup mechanism presents interception risk. Must disable SMS fallback.',
 history: [
 { timestamp: 'Oct 01, 2026 - 11:00', author: 'Marcus Chen', action: 'Flagged for Review', details: 'Identified SMS fallback in vendor attestation.' }
 ]
 },
 {
 id: 'rev-03',
 controlId: 'CC9.4',
 question: 'Do you maintain real-time telemetry streaming into a centralized SIEM for incident detection?',
 vendorAnswer: 'Logs are batched and exported via daily CSV uploads to secure object storage.',
 evidenceReference: 'DataFlow_SIG_Lite_v4.xlsx',
 evidenceCitation: 'Tab 4, Row 18 (Logging & Monitoring)',
 expectedRequirement: 'Real-time or near-real-time SIEM log streaming (API/Syslog) required for critical tier vendors.',
 analystAssessment: 'Batch CSV export does not satisfy real-time threat detection baseline, but vendor is operating under legacy contract exception.',
 currentDecision: 'Accept risk',
 comment: 'Accepted under temporary exception expiring Q2 2027 pending architecture upgrade.',
 history: [
 { timestamp: 'Sep 28, 2026 - 16:40', author: 'Alex Rivera', action: 'Risk Acceptance Drafted', details: 'Associated with Risk Exception #EX-2026-88.' }
 ]
 }
];

export interface AnalystReviewWorkspaceProps {
 vendorName: string;
 onBack: () => void;
}

export function AnalystReviewWorkspace({ vendorName, onBack }: AnalystReviewWorkspaceProps) {
 const [items, setItems] = useState<ReviewItem[]>(DEV_REVIEW_ITEMS);
 const [selectedItemId, setSelectedItemId] = useState<string>(items[0].id);
 const [commentInput, setCommentInput] = useState('');

 const currentItem = items.find(i => i.id === selectedItemId) || items[0];

 useEffect(() => {
 const handleKeyDown = (e: KeyboardEvent) => {
 if (e.key === 'Escape') {
 onBack();
 }
 };
 document.addEventListener('keydown', handleKeyDown);
 return () => document.removeEventListener('keydown', handleKeyDown);
 }, [onBack]);

 const handleDecision = (decision: ReviewDecision) => {
 setItems(prev => prev.map(item => {
 if (item.id === currentItem.id) {
 const newHistoryEvent: AuditHistoryEvent = {
 timestamp: new Date().toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) + ' - ' + new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
 author: 'Current Analyst',
 action: `Decision: ${decision}`,
 details: commentInput ? `Comment: ${commentInput}` : 'Status updated without additional comment.'
 };
 return {
 ...item,
 currentDecision: decision,
 comment: commentInput || item.comment,
 history: [newHistoryEvent, ...item.history]
 };
 }
 return item;
 }));
 setCommentInput('');
 };

 const getDecisionBadge = (decision: ReviewDecision | 'Pending') => {
 switch (decision) {
 case 'Approve':
 return <span className="px-2.5 py-1 rounded text-[10px] font-semibold uppercase tracking-wider bg-status-success/10 text-status-success ">Approve</span>;
 case 'Reject':
 return <span className="px-2.5 py-1 rounded text-[10px] font-semibold uppercase tracking-wider bg-risk-critical/10 text-risk-critical ">Reject</span>;
 case 'Clarify':
 return <span className="px-2.5 py-1 rounded text-[10px] font-semibold uppercase tracking-wider bg-status-warning/10 text-status-warning ">Clarify</span>;
 case 'Accept risk':
 return <span className="px-2.5 py-1 rounded text-[10px] font-semibold uppercase tracking-wider bg-accent-muted text-accent ">Accept Risk</span>;
 default:
 return <span className="px-2.5 py-1 rounded text-[10px] font-semibold uppercase tracking-wider bg-surface-hover text-text-secondary border border-border">Pending</span>;
 }
 };

 return (
 <div className="space-y-6 max-w-7xl mx-auto pb-28 animate-in fade-in duration-300">
 
 <div aria-live="polite" className="sr-only">
 Active review item: Control {currentItem.controlId}. Current decision: {currentItem.currentDecision}.
 </div>

 <div className="flex items-center justify-between border-b border-border pb-4">
 <div className="flex items-center gap-3">
 <Button type="button" variant="secondary" size="sm" onClick={onBack} className="h-8 px-3 text-xs gap-1.5">
 <ArrowLeft aria-hidden="true" size={14} />
 <span>Back to Assessment</span>
 </Button>
 <div aria-hidden="true" className="h-4 w-px bg-border"></div>
 <div>
 <h1 className="text-lg font-bold text-text-primary">Defensible Review: {vendorName}</h1>
 <p className="text-xs text-text-secondary">Evaluate vendor assertions against expected compliance baselines.</p>
 </div>
 </div>
 </div>

 <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
 
 <div className="lg:col-span-4 space-y-3">
 <Card className="shadow-sm overflow-hidden">
 <CardHeader className="border-b border-border px-4 py-3 bg-surface-hover/30">
 <CardTitle className="text-xs font-semibold uppercase tracking-wider">Review Queue ({items.length})</CardTitle>
 </CardHeader>
 <div className="divide-y divide-border-subtle bg-surface" role="tablist" aria-label="Review Queue Items">
 {items.map(item => {
 const isSelected = selectedItemId === item.id;
 return (
 <button
 key={item.id}
 type="button"
 role="tab"
 aria-selected={isSelected}
 aria-controls="review-panel-content"
 onClick={() => setSelectedItemId(item.id)}
 className={`w-full text-left p-4 transition-colors flex flex-col gap-2 focus:outline-none focus:ring-2 focus:ring-inset focus:ring-info ${
 isSelected 
 ? 'bg-surface-hover border-l-4 border-l-info shadow-sm' 
 : 'hover:bg-surface-hover/50'
 }`}
 >
 <div className="flex items-center justify-between w-full">
 <span className="text-xs font-mono font-bold text-text-primary bg-base px-2 py-0.5 rounded border border-border">
 {item.controlId}
 </span>
 {getDecisionBadge(item.currentDecision)}
 </div>
 <p className="text-xs font-medium text-text-primary line-clamp-2">
 {item.question}
 </p>
 </button>
 );
 })}
 </div>
 </Card>
 </div>

 <div id="review-panel-content" role="tabpanel" className="lg:col-span-8 space-y-6">
 <Card className="shadow-sm">
 <CardContent className="p-6 space-y-6">
 
 <div className="border-b border-border-subtle pb-5">
 <div className="flex items-center justify-between mb-2">
 <span className="text-xs font-mono font-bold text-info bg-info/10 px-2.5 py-1 rounded border border-info/30">
 Control: {currentItem.controlId}
 </span>
 {getDecisionBadge(currentItem.currentDecision)}
 </div>
 <h2 className="text-base font-bold text-text-primary leading-snug m-0">
 {currentItem.question}
 </h2>
 </div>

 <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
 <div className="bg-base border border-border p-4 rounded-xl space-y-2">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary block">Vendor Answer</span>
 <p className="text-xs font-medium text-text-primary leading-relaxed m-0">
 "{currentItem.vendorAnswer}"
 </p>
 </div>

 <div className="bg-base border border-border p-4 rounded-xl space-y-2">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary block">Evidence Source</span>
 <div className="text-xs font-semibold text-text-primary flex items-center gap-1.5">
 <FileText aria-hidden="true" size={14} className="text-text-tertiary shrink-0" />
 <span className="truncate">{currentItem.evidenceReference}</span>
 </div>
 <span className="text-[11px] font-mono text-info block">{currentItem.evidenceCitation}</span>
 </div>
 </div>

 <div className="space-y-4">
 <div className="p-4 rounded-xl border border-border bg-surface space-y-1.5">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary block">Expected Baseline Requirement</span>
 <p className="text-xs text-text-primary leading-relaxed font-medium m-0">
 {currentItem.expectedRequirement}
 </p>
 </div>

 <div className="p-4 rounded-xl border border-border bg-surface space-y-1.5">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary block">Analyst Assessment</span>
 <p className="text-xs text-text-secondary leading-relaxed m-0">
 {currentItem.analystAssessment}
 </p>
 </div>
 </div>

 <div className="space-y-2">
 <div className="flex items-center justify-between mb-2">
 <label htmlFor="analystCommentInput" className="text-xs font-semibold uppercase tracking-wider text-text-tertiary block">
 Analyst Comment / Defensible Rationale
 </label>
 <button type="button" onClick={() => navigateTo('decisions', { vendorName })} className="inline-flex items-center gap-1.5 px-2.5 py-1 text-[10px] font-semibold uppercase tracking-wider text-accent bg-accent-muted hover:bg-accent-muted rounded transition-colors focus:ring-2 focus:ring-info outline-none ">
 <FileText aria-hidden="true" size={12} className="opacity-70" /> Prepare Decision Brief
 </button>
 </div>
 <textarea
 id="analystCommentInput"
 rows={3}
 value={commentInput}
 onChange={(e) => setCommentInput(e.target.value)}
 placeholder="Enter notes supporting your decision for audit defense..."
 className="w-full bg-base border border-border rounded-lg p-3 text-xs text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info shadow-sm resize-none"
 />
 </div>

 <div className="space-y-3 pt-2 border-t border-border-subtle">
 <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary block">Make Defensible Decision</span>
 <div className="flex flex-wrap items-center gap-2">
 <Button
 type="button"
 variant="secondary"
 size="sm"
 onClick={() => handleDecision('Approve')}
 className="h-9 px-4 text-xs font-bold gap-1.5 hover:bg-status-success/10 hover:text-status-success"
 >
 <Check aria-hidden="true" size={14} className="text-status-success" />
 <span>Approve</span>
 </Button>

 <Button
 type="button"
 variant="secondary"
 size="sm"
 onClick={() => handleDecision('Reject')}
 className="h-9 px-4 text-xs font-bold gap-1.5 hover:bg-risk-critical/10 hover:text-risk-critical"
 >
 <X aria-hidden="true" size={14} className="text-risk-critical" />
 <span>Reject</span>
 </Button>

 <Button
 type="button"
 variant="secondary"
 size="sm"
 onClick={() => handleDecision('Clarify')}
 className="h-9 px-4 text-xs font-bold gap-1.5 hover:bg-status-warning/10 hover:text-status-warning"
 >
 <HelpCircle aria-hidden="true" size={14} className="text-status-warning" />
 <span>Clarify</span>
 </Button>

 <Button
 type="button"
 variant="secondary"
 size="sm"
 onClick={() => handleDecision('Accept risk')}
 className="h-9 px-4 text-xs font-bold gap-1.5 hover:bg-accent-muted hover:text-accent"
 >
 <AlertTriangle aria-hidden="true" size={14} className="text-accent" />
 <span>Accept Risk</span>
 </Button>
 </div>
 </div>

 <div className="space-y-3 pt-4 border-t border-border-subtle">
 <h3 className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-text-tertiary m-0">
 <History aria-hidden="true" size={14} />
 <span>Audit History & Decision Trail</span>
 </h3>
 <div className="space-y-2">
 {currentItem.history.map((h, idx) => (
 <div key={idx} className="bg-base border border-border p-3 rounded-lg text-xs space-y-1">
 <div className="flex items-center justify-between text-text-tertiary font-mono text-[10px]">
 <span className="font-bold text-text-primary">{h.author}</span>
 <span>{h.timestamp}</span>
 </div>
 <div className="font-bold text-text-primary">{h.action}</div>
 <p className="text-text-secondary m-0">{h.details}</p>
 </div>
 ))}
 </div>
 </div>

 </CardContent>
 </Card>
 </div>

 </div>
 </div>
 );
}