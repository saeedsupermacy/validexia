// src/shared/ui/ai/AIAnalysisCard.tsx
import React from 'react';
import { 
 Sparkles, FileText, CheckCircle2, ShieldAlert, 
 AlertTriangle, UserCheck, Check, X, Edit3, Link
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Button } from '../core';

export type AIConfidence = 'High' | 'Medium' | 'Low';

export interface AISource {
 id: string;
 name: string;
 citation: string;
 url?: string;
}

export interface AIAnalysisResult {
 id: string;
 taskTitle: string;
 reasoning: string;
 extractedData?: React.ReactNode;
 sources: AISource[];
 confidence: AIConfidence;
 suggestedActions: string[];
}

interface AIAnalysisCardProps {
 result: AIAnalysisResult;
 onApprove: (id: string) => void;
 onReject: (id: string) => void;
 onEdit: (id: string) => void;
}

export function AIAnalysisCard({ result, onApprove, onReject, onEdit }: AIAnalysisCardProps) {
 const getConfidenceColor = (confidence: AIConfidence) => {
 switch (confidence) {
 case 'High': return 'text-status-success bg-status-success/10 ';
 case 'Medium': return 'text-status-warning bg-status-warning/10 ';
 case 'Low': return 'text-risk-critical bg-risk-critical/10 ';
 }
 };

 return (
 <Card className="shadow-sm border-accent/25 relative overflow-hidden">
 {/* Decorative AI Accent */}
 <div aria-hidden="true" className="absolute top-0 left-0 w-1 h-full bg-accent"></div>
 
 <CardHeader className="border-b border-border px-5 py-4 bg-accent-muted/30">
 <div className="flex items-center justify-between">
 <div className="flex items-center gap-2">
 <div aria-hidden="true" className="p-1.5 rounded-md bg-accent-muted/40 text-accent">
 <Sparkles size={16} />
 </div>
 <CardTitle className="text-sm font-bold text-text-primary m-0">
 {result.taskTitle}
 </CardTitle>
 </div>
 <div className={`px-2.5 py-1 rounded text-[10px] font-semibold uppercase tracking-wider border flex items-center gap-1 ${getConfidenceColor(result.confidence)}`}>
 {result.confidence === 'High' && <CheckCircle2 aria-hidden="true" size={12} />}
 {result.confidence === 'Medium' && <AlertTriangle aria-hidden="true" size={12} />}
 {result.confidence === 'Low' && <ShieldAlert aria-hidden="true" size={12} />}
 <span>{result.confidence} Confidence</span>
 </div>
 </div>
 </CardHeader>

 <CardContent className="p-0 divide-y divide-border-subtle text-sm">
 
 {/* Reasoning & Context */}
 <div className="p-5 space-y-2">
 <h4 className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary m-0">Analysis & Reasoning</h4>
 <div className="text-text-primary leading-relaxed text-sm">
 {result.reasoning}
 </div>
 {result.extractedData && (
 <div className="mt-3 p-3 bg-surface-hover rounded-lg border border-border">
 {result.extractedData}
 </div>
 )}
 </div>

 {/* Provenance / Sources */}
 <div className="p-5 space-y-3 bg-surface">
 <h4 className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary m-0">Verified Evidence Sources</h4>
 <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
 {result.sources.map(source => (
 <div key={source.id} className="p-3 bg-base border border-border rounded-lg flex items-start gap-3">
 <FileText aria-hidden="true" size={16} className="text-text-tertiary shrink-0 mt-0.5" />
 <div className="min-w-0">
 <div className="font-semibold text-text-primary text-xs truncate">{source.name}</div>
 <div className="text-[11px] font-mono text-info mt-1 flex items-center gap-1">
 <Link aria-hidden="true" size={10} />
 <span>{source.citation}</span>
 </div>
 </div>
 </div>
 ))}
 </div>
 </div>

 {/* Human Review & Action */}
 <div className="p-5 bg-surface-hover/50">
 <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
 <div className="flex items-start gap-2 max-w-lg">
 <UserCheck aria-hidden="true" size={18} className="text-status-warning shrink-0 mt-0.5" />
 <div>
 <h4 className="text-xs font-bold text-text-primary m-0">Human Review Required</h4>
 <p className="text-[11px] text-text-secondary mt-0.5 m-0">
 AI analysis must be validated by an analyst before applying to the official record.
 </p>
 </div>
 </div>
 
 <div className="flex items-center gap-2 shrink-0">
 <Button type="button" variant="secondary" size="sm" onClick={() => onReject(result.id)} className="h-9 hover:text-risk-critical">
 <X aria-hidden="true" size={14} className="mr-1.5" /> Discard
 </Button>
 <Button type="button" variant="secondary" size="sm" onClick={() => onEdit(result.id)} className="h-9">
 <Edit3 aria-hidden="true" size={14} className="mr-1.5" /> Modify
 </Button>
 <Button type="button" variant="primary" size="sm" onClick={() => onApprove(result.id)} className="h-9 gap-1.5">
 <Check aria-hidden="true" size={14} /> Accept & Apply
 </Button>
 </div>
 </div>
 </div>

 </CardContent>
 </Card>
 );
}