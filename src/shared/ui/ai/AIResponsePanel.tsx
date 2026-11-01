import { useState, useEffect } from 'react';
import { 
 Cpu, FileText, CheckCircle2, ShieldAlert, AlertTriangle, 
 HelpCircle, Check, X, Edit3, Copy, CheckCheck, ThumbsUp, ThumbsDown, Link, ShieldQuestion
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Button } from '../core';

export type AIConfidenceLevel = 'High' | 'Medium' | 'Low' | 'Insufficient';

export interface AISourceCitation {
 id: string;
 documentName: string;
 citation: string;
}

export interface AIResponseData {
 id: string;
 title: string;
 isInsufficientEvidence?: boolean;
 conciseAnswer: string;
 confidence: AIConfidenceLevel;
 sources: AISourceCitation[];
 assumptions: string[];
 unknowns: string[];
 recommendedAction: string;
}

interface AIResponsePanelProps {
 response: AIResponseData;
 onApprove?: (id: string) => void;
 onReject?: (id: string) => void;
 onModify?: (id: string) => void;
 onFeedback?: (id: string, helpful: boolean) => void;
}

export function AIResponsePanel({ response, onApprove, onReject, onModify, onFeedback }: AIResponsePanelProps) {
 const [copied, setCopied] = useState(false);
 const [feedbackState, setFeedbackState] = useState<'idle' | 'submitted'>('idle');
 // Inline editing state for the "Modify" action: the analyst can refine the
 // AI-drafted answer in place before accepting or discarding it.
 const [isEditing, setIsEditing] = useState(false);
 const [answer, setAnswer] = useState(response.conciseAnswer);
 const [draft, setDraft] = useState(response.conciseAnswer);

 useEffect(() => {
 setAnswer(response.conciseAnswer);
 setDraft(response.conciseAnswer);
 setIsEditing(false);
 }, [response.id, response.conciseAnswer]);

 const handleCopy = () => {
 navigator.clipboard.writeText(answer);
 setCopied(true);
 setTimeout(() => setCopied(false), 2000);
 };

 const handleFeedback = (helpful: boolean) => {
 setFeedbackState('submitted');
 onFeedback?.(response.id, helpful);
 setTimeout(() => setFeedbackState('idle'), 3000);
 };

 const getConfidenceBadge = (confidence: AIConfidenceLevel) => {
 switch (confidence) {
 case 'High': return <span className="px-2 py-0.5 rounded-md text-xs font-medium bg-status-success/10 text-status-success flex items-center gap-1.5"><CheckCircle2 size={12} strokeWidth={1.5} /> High confidence</span>;
 case 'Medium': return <span className="px-2 py-0.5 rounded-md text-xs font-medium bg-status-warning/10 text-status-warning flex items-center gap-1.5"><AlertTriangle size={12} strokeWidth={1.5} /> Medium confidence</span>;
 case 'Low': return <span className="px-2 py-0.5 rounded-md text-xs font-medium bg-risk-critical/10 text-risk-critical flex items-center gap-1.5"><ShieldAlert size={12} strokeWidth={1.5} /> Low confidence</span>;
 case 'Insufficient': return <span className="px-2 py-0.5 rounded-md text-xs font-medium bg-surface-hover text-text-secondary dark:bg-surface-hover dark:text-text-secondary flex items-center gap-1.5"><ShieldQuestion size={12} strokeWidth={1.5} /> Insufficient evidence</span>;
 }
 };

 const renderAnswer = () => {
 if (isEditing) {
 return (
 <div className="space-y-2">
 <label htmlFor="ai-answer-edit" className="sr-only">Edit concise assessment</label>
 <textarea
 id="ai-answer-edit"
 value={draft}
 onChange={(e) => setDraft(e.target.value)}
 rows={4}
 className="w-full bg-surface border border-border rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:ring-2 focus:ring-info"
 />
 <div className="flex justify-end gap-2">
 <Button type="button" variant="secondary" size="sm" onClick={() => { setDraft(answer); setIsEditing(false); }}>
 <X aria-hidden="true" size={14} strokeWidth={1.5} className="mr-1.5" /> Cancel
 </Button>
 <Button type="button" variant="primary" size="sm" onClick={() => { setAnswer(draft); setIsEditing(false); }}>
 <Check aria-hidden="true" size={14} strokeWidth={1.5} className="mr-1.5" /> Save Changes
 </Button>
 </div>
 </div>
 );
 }
 return <p className="text-sm text-text-primary leading-relaxed m-0">{answer}</p>;
 };

 return (
 <Card className={`transition-all bg-surface overflow-hidden ${response.isInsufficientEvidence ? 'border border-risk-moderate/20' : 'border border-accent/20'}`}>
 <CardHeader className="border-b border-border px-6 py-4 flex flex-row items-center justify-between">
 <div className="flex items-center gap-2.5">
 <div aria-hidden="true" className={`p-1.5 rounded-md ${response.isInsufficientEvidence ? 'bg-risk-moderate/10 text-risk-moderate' : 'bg-accent-muted text-accent'}`}>
 <Cpu size={16} strokeWidth={1.5} />
 </div>
 <CardTitle className="text-sm font-semibold text-text-primary m-0">{response.title}</CardTitle>
 </div>

 <div className="flex items-center gap-3">
 {getConfidenceBadge(response.confidence)}
 <div aria-hidden="true" className="h-3 w-px bg-border"></div>
 <button 
 type="button"
 onClick={handleCopy}
 className="p-1.5 rounded-md hover:bg-surface-hover text-text-secondary hover:text-text-primary transition-colors flex items-center"
 aria-label="Copy concise assessment"
 >
 {copied ? <CheckCheck aria-hidden="true" size={14} strokeWidth={1.5} className="text-status-success" /> : <Copy aria-hidden="true" size={14} strokeWidth={1.5} />}
 </button>
 </div>
 </CardHeader>

 <CardContent className="p-0 divide-y divide-border-subtle text-sm">
 {response.isInsufficientEvidence ? (
 <div className="p-6 bg-risk-moderate/30 dark:bg-risk-moderate/10 space-y-2">
 <div className="flex items-center gap-2 text-risk-moderate font-medium text-xs">
 <AlertTriangle aria-hidden="true" size={14} strokeWidth={1.5} />
 <span>Insufficient Evidence to Form Defensible Conclusion</span>
 </div>
 {renderAnswer()}
 </div>
 ) : (
 <div className="p-6 space-y-1.5">
 <h4 className="text-xs font-medium text-text-tertiary m-0">Concise Assessment</h4>
 {renderAnswer()}
 </div>
 )}

 <div className="grid grid-cols-1 md:grid-cols-2 divide-y md:divide-y-0 md:divide-x divide-border-subtle bg-surface">
 <div className="p-5 space-y-2">
 <h4 className="text-xs font-medium text-text-tertiary flex items-center gap-1.5 m-0">
 <HelpCircle aria-hidden="true" size={12} strokeWidth={1.5} className="text-status-warning" /> Assumptions made
 </h4>
 {response.assumptions.length > 0 ? (
 <ul className="list-disc pl-4 space-y-1 text-sm text-text-secondary m-0">
 {response.assumptions.map((item, idx) => <li key={idx}>{item}</li>)}
 </ul>
 ) : (
 <p className="text-sm text-text-tertiary m-0">No underlying assumptions required.</p>
 )}
 </div>

 <div className="p-5 space-y-2">
 <h4 className="text-xs font-medium text-text-tertiary flex items-center gap-1.5 m-0">
 <ShieldAlert aria-hidden="true" size={12} strokeWidth={1.5} className="text-risk-critical" /> Identified unknowns
 </h4>
 {response.unknowns.length > 0 ? (
 <ul className="list-disc pl-4 space-y-1 text-sm text-risk-critical m-0">
 {response.unknowns.map((item, idx) => <li key={idx}>{item}</li>)}
 </ul>
 ) : (
 <p className="text-sm text-text-tertiary m-0">Zero unknown parameters identified.</p>
 )}
 </div>
 </div>

 {response.sources.length > 0 && (
 <div className="p-5 space-y-3 bg-base">
 <h4 className="text-xs font-medium text-text-tertiary m-0">Supporting evidence & citations</h4>
 <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
 {response.sources.map(source => (
 <div key={source.id} className="p-3 bg-surface border border-border rounded-lg flex items-start gap-3">
 <FileText aria-hidden="true" size={16} strokeWidth={1.5} className="text-text-tertiary shrink-0 mt-0.5" />
 <div className="min-w-0">
 <div className="font-medium text-text-primary text-xs truncate">{source.documentName}</div>
 <div className="text-[11px] font-mono text-info mt-0.5 flex items-center gap-1">
 <Link aria-hidden="true" size={10} strokeWidth={1.5} /> <span className="tabular-nums">{source.citation}</span>
 </div>
 </div>
 </div>
 ))}
 </div>
 </div>
 )}

 <div className="p-5 bg-surface flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-sm">
 <div className="flex flex-col gap-0.5">
 <span className="text-xs font-medium text-text-tertiary">Recommended action</span>
 <span className="text-text-primary font-medium">{response.recommendedAction}</span>
 </div>
 </div>

 <div className="p-5 bg-base flex flex-col md:flex-row md:items-center justify-between gap-4">
 <div className="flex items-center gap-3 h-9">
 {feedbackState === 'submitted' ? (
 <span className="text-xs font-medium text-status-success flex items-center gap-1.5 animate-in fade-in duration-200">
 <CheckCircle2 size={14} strokeWidth={1.5} /> Evaluation recorded
 </span>
 ) : (
 <>
 <span className="text-xs font-medium text-text-tertiary">Evaluate output:</span>
 <div className="flex items-center gap-1">
 <button type="button" onClick={() => handleFeedback(true)} className="p-1.5 rounded-md border bg-surface border-border text-text-secondary hover:bg-surface-hover hover:text-text-primary transition-colors">
 <ThumbsUp size={14} strokeWidth={1.5} />
 </button>
 <button type="button" onClick={() => handleFeedback(false)} className="p-1.5 rounded-md border bg-surface border-border text-text-secondary hover:bg-surface-hover hover:text-risk-critical transition-colors">
 <ThumbsDown size={14} strokeWidth={1.5} />
 </button>
 </div>
 </>
 )}
 </div>

 <div className="flex items-center gap-2">
 <Button type="button" variant="ghost" size="sm" onClick={() => onReject?.(response.id)} className="h-9 hover:text-risk-critical">
 <X aria-hidden="true" size={14} strokeWidth={1.5} className="mr-1.5" /> Discard
 </Button>
 <Button type="button" variant="secondary" size="sm" onClick={() => { setDraft(answer); setIsEditing(true); onModify?.(response.id); }} className="h-9" disabled={isEditing}>
 <Edit3 aria-hidden="true" size={14} strokeWidth={1.5} className="mr-1.5" /> Modify
 </Button>
 <Button type="button" variant="primary" size="sm" onClick={() => onApprove?.(response.id)} className="h-9 gap-1.5">
 <Check aria-hidden="true" size={14} strokeWidth={1.5} /> Accept & Apply
 </Button>
 </div>
 </div>
 </CardContent>
 </Card>
 );
}