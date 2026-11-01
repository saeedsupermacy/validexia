// src/features/evidence/EvidenceInspectorWorkspace.tsx
import { useState, useEffect } from 'react';
import { 
 FileText, AlertTriangle, ShieldCheck, 
 ArrowLeft, Download, Check, X, Layers 
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Button } from '../../shared/ui/core';

interface EvidenceInspectorProps {
 documentId?: string;
 documentName?: string;
 vendorName?: string;
 onBack?: () => void;
}

interface InspectionModel {
 metadata: {
  fileName: string;
  fileSize: string;
  sha256Hash: string;
  receivedDate: string;
  owner: string;
 };
 extractedFacts: { id: string; fact: string; confidence: string }[];
 controlMappings: { controlId: string; description: string; status: string }[];
 findings: { id: string; severity: string; description: string }[];
 confidenceScore: string;
 citationRef: string;
 previewHeading: string;
 previewScope: string;
 previewOpinion: string;
 previewHighlight: string;
 pageCount: string;
}

const EVIDENCE_MODELS: Record<string, InspectionModel> = {
 'inbox-01': {
  metadata: {
   fileName: 'CloudScale_SOC2_TypeII_2026.pdf',
   fileSize: '4.8 MB (PDF Document)',
   sha256Hash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
   receivedDate: 'Today, 09:14 AM via Secure Vendor Portal',
   owner: 'Sarah Jenkins'
  },
  extractedFacts: [
   { id: 'f-1', fact: 'Attestation period covers Oct 1, 2025 through Sep 30, 2026.', confidence: 'High Confidence' },
   { id: 'f-2', fact: 'Auditing firm: Deloitte & Touche LLP (SOC 2 Practice Group).', confidence: 'High Confidence' },
   { id: 'f-3', fact: 'Perimeter firewall security exceptions noted in Section 4.2.', confidence: 'Medium Confidence' }
  ],
  controlMappings: [
   { controlId: 'CC6.1', description: 'Logical Access Controls & Perimeter Encryption', status: 'Mapped & Verified' },
   { controlId: 'CC7.2', description: 'Vulnerability Monitoring & Patch SLA Enforcement', status: 'Exception Noted' }
  ],
  findings: [
   { id: 'find-1', severity: 'High', description: 'SOC 2 report expiration lapsed 4 days ago prior to annual renewal receipt.' },
   { id: 'find-2', severity: 'Moderate', description: 'Unpatched CVE-2026-1045 mentioned in auditor exception log.' }
  ],
  confidenceScore: '91.7% (Extraction Confidence)',
  citationRef: 'SOC2-2026-CS-04',
  previewHeading: "INDEPENDENT SERVICE AUDITOR'S REPORT",
  previewScope: 'Cloud infrastructure, managed container services, and secure gateway routing.',
  previewOpinion: 'In our opinion, the description presents fairly, in all material respects, the controls...',
  previewHighlight: '[HIGHLIGHTED EXTRACTION]: CC6.1 Logical Access Controls enforcement verified with 1 exception noted regarding perimeter gateway patch cadence.',
  pageCount: 'Page 1 of 42'
 },
 'inbox-02': {
  metadata: {
   fileName: 'SecureAuth_ISO27001_Audit_Q4.pdf',
   fileSize: '6.1 MB (PDF Document)',
   sha256Hash: '7d4f9a2c1e5b8036a9c4d7e21f5a8b306c4d2e9f1a5b7c8d3e6f0a2b4c5d7e8f',
   receivedDate: 'Yesterday, 04:30 PM via Secure Vendor Portal',
   owner: 'Automated Pipeline'
  },
  extractedFacts: [
   { id: 'f-1', fact: 'Certification scope covers the ISMS for identity and authentication services (Annex A, 2022 revision).', confidence: 'High Confidence' },
   { id: 'f-2', fact: 'Certification body: TÜV Rheinland Cert GmbH.', confidence: 'High Confidence' },
   { id: 'f-3', fact: 'Surveillance audit due within 12 months of initial certification date.', confidence: 'Medium Confidence' }
  ],
  controlMappings: [
   { controlId: 'A.5.1', description: 'Policies for Information Security — Reviewed & Approved', status: 'Mapped & Verified' },
   { controlId: 'A.8.13', description: 'Information Backup & Restore Testing', status: 'Exception Noted' }
  ],
  findings: [
   { id: 'find-1', severity: 'Moderate', description: 'Two failed backup-restore tests recorded during the Q4 surveillance window.' },
   { id: 'find-2', severity: 'Low', description: 'Asset inventory missing approximately 14% of contractor endpoints.' }
  ],
  confidenceScore: '94.2% (Extraction Confidence)',
  citationRef: 'ISO27001-2026-SA-11',
  previewHeading: 'ISO/IEC 27001:2022 CERTIFICATE OF CONFORMITY',
  previewScope: 'Information Security Management System covering identity, SSO, and MFA services.',
  previewOpinion: 'The management system has been found to conform to the requirements of ISO/IEC 27001:2022...',
  previewHighlight: '[HIGHLIGHTED EXTRACTION]: A.8.13 Information Backup — 2 failed restore tests noted; corrective action plan requested from SecureAuth.',
  pageCount: 'Page 1 of 28'
 },
 'inbox-03': {
  metadata: {
   fileName: 'ApexGateway_SIG_Lite_v4.xlsx',
   fileSize: '0.9 MB (XLSX Workbook)',
   sha256Hash: 'c91a04f2d77b3e6a8f10d5c9b2e4a6f71c3d8e5f0a1b9c6d4e7f3a2b8c0d5e6f',
   receivedDate: 'Oct 3, 2026 via Secure Vendor Portal',
   owner: 'Unassigned'
  },
  extractedFacts: [
   { id: 'f-1', fact: '287 of 312 SIG Lite questions completed by the vendor respondent.', confidence: 'High Confidence' },
   { id: 'f-2', fact: 'Scope: payment processing and card-data handling environments.', confidence: 'High Confidence' },
   { id: 'f-3', fact: 'Full control mapping will run after the remaining responses are received.', confidence: 'Medium Confidence' }
  ],
  controlMappings: [
   { controlId: 'SIG-01', description: 'Vendor Response Completeness — 92% complete', status: 'Mapped & Verified' }
  ],
  findings: [
   { id: 'find-1', severity: 'Moderate', description: '12 unanswered questions flagged for follow-up before control mapping can begin.' }
  ],
  confidenceScore: '78.4% (Extraction Confidence)',
  citationRef: 'SIGLITE-2026-AG-02',
  previewHeading: 'STANDARDIZED INFORMATION GATHERING (SIG) LITE',
  previewScope: 'Self-assessment responses covering payment processing security controls.',
  previewOpinion: 'This questionnaire represents vendor self-attestation and is subject to independent validation...',
  previewHighlight: '[HIGHLIGHTED EXTRACTION]: 25 questions unanswered (section 4: encryption key management) — follow-up required.',
  pageCount: 'Tab 1 of 9'
 },
 'inbox-04': {
  metadata: {
   fileName: 'DataFlow_PenTest_Report_2026.pdf',
   fileSize: '11.3 MB (PDF Document)',
   sha256Hash: '5be8d2a4f6c1e9037b9d4f2a6c8e0b1d3f5a7c9e2b4d6f8a0c1e3b5d7f9a2c4',
   receivedDate: 'Sep 28, 2026 via Secure Vendor Portal',
   owner: 'Alex Rivera'
  },
  extractedFacts: [
   { id: 'f-1', fact: 'Test window: Sep 8–22, 2026 (black-box and grey-box methodology).', confidence: 'High Confidence' },
   { id: 'f-2', fact: '3 critical and 7 high findings; all verified remediated in retest.', confidence: 'High Confidence' },
   { id: 'f-3', fact: 'Retest confirms a 92% remediation rate across all severity levels.', confidence: 'High Confidence' }
  ],
  controlMappings: [
   { controlId: 'CC7.1', description: 'Vulnerability Detection & Remediation SLA', status: 'Mapped & Verified' },
   { controlId: 'CC6.6', description: 'Encryption of Data in Transit', status: 'Mapped & Verified' }
  ],
  findings: [
   { id: 'find-1', severity: 'Critical', description: 'Remote code execution via unpatched API gateway (CVE-2026-8821) — remediated.' },
   { id: 'find-2', severity: 'High', description: 'Excessive session timeout on the analyst console — remediated.' }
  ],
  confidenceScore: '96.1% (Extraction Confidence)',
  citationRef: 'PENTEST-2026-DF-07',
  previewHeading: 'PENETRATION TEST REPORT — CONFIDENTIAL',
  previewScope: 'External network perimeter, API gateway, and analyst console application.',
  previewOpinion: 'Testing was performed using a combination of automated scanning and manual exploitation techniques...',
  previewHighlight: '[HIGHLIGHTED EXTRACTION]: CVE-2026-8821 RCE on API gateway — verified remediated in Sep 22 retest. No residual criticals.',
  pageCount: 'Page 1 of 64'
 },
 'inbox-05': {
  metadata: {
   fileName: 'SynapseAI_DPA_Exhibit_B.pdf',
   fileSize: '2.4 MB (PDF Document)',
   sha256Hash: 'a04f7c1d9e2b5083f6a1c7d4e9b0f2a5c8d3e6f1a4b7c0d9e2f5a8b1c4d7e0f3',
   receivedDate: 'Sep 20, 2026 via Secure Vendor Portal',
   owner: 'Marcus Chen'
  },
  extractedFacts: [
   { id: 'f-1', fact: 'Exhibit B governs processing of EU personal data under GDPR Art. 28.', confidence: 'High Confidence' },
   { id: 'f-2', fact: 'Vendor counter-signature missing on page 7 of 9.', confidence: 'High Confidence' },
   { id: 'f-3', fact: 'Standard contractual clauses: 2021/914, module two (controller-to-processor).', confidence: 'Medium Confidence' }
  ],
  controlMappings: [
   { controlId: 'LEGAL-28', description: 'GDPR Art. 28 Processor Terms & Sub-processor Disclosure', status: 'Exception Noted' }
  ],
  findings: [
   { id: 'find-1', severity: 'High', description: 'Document rejected: legal counter-signature missing — vendor re-submission requested.' },
   { id: 'find-2', severity: 'Moderate', description: 'Data retention schedule conflicts with definitions in Exhibit A.' }
  ],
  confidenceScore: '88.9% (Extraction Confidence)',
  citationRef: 'DPA-2026-SY-03',
  previewHeading: 'DATA PROCESSING ADDENDUM — EXHIBIT B',
  previewScope: 'Processing of EU personal data under GDPR Article 28 processor terms.',
  previewOpinion: 'The Processor shall process Personal Data only on documented instructions from the Controller...',
  previewHighlight: '[HIGHLIGHTED EXTRACTION]: Signature block on page 7 unsigned — document rejected pending vendor counter-signature.',
  pageCount: 'Page 1 of 9'
 },
 'inbox-06': {
  metadata: {
   fileName: 'Legacy_CloudHost_SOC2_2025.pdf',
   fileSize: '4.2 MB (PDF Document)',
   sha256Hash: 'e7c3a9f5b1d40682e8c4f7a1d5b9c2e6f0a3d7b9c1e5f2a8d4c6b0e9f1a3c5d7',
   receivedDate: 'Oct 1, 2025 via Secure Vendor Portal',
   owner: 'Sarah Jenkins'
  },
  extractedFacts: [
   { id: 'f-1', fact: 'Attestation period covered Oct 1, 2024 through Sep 30, 2025.', confidence: 'High Confidence' },
   { id: 'f-2', fact: 'Report expired Oct 1, 2026 — renewal not received from vendor.', confidence: 'High Confidence' },
   { id: 'f-3', fact: 'Historical reference only; do not rely on this report for current assessments.', confidence: 'High Confidence' }
  ],
  controlMappings: [
   { controlId: 'CC6.1', description: 'Logical Access Controls — Historical Mapping Only', status: 'Expired' }
  ],
  findings: [
   { id: 'find-1', severity: 'Critical', description: 'SOC 2 attestation expired 7 days ago; current assurance gap for Legacy Host Corp.' },
   { id: 'find-2', severity: 'High', description: 'Vendor renewal request sent Oct 2, 2026 — no response received yet.' }
  ],
  confidenceScore: '99.0% (Extraction Confidence)',
  citationRef: 'SOC2-2025-LH-09',
  previewHeading: "INDEPENDENT SERVICE AUDITOR'S REPORT (EXPIRED)",
  previewScope: 'Historical attestation for legacy cloud hosting infrastructure.',
  previewOpinion: 'In our opinion, the description presents fairly, in all material respects, the controls...',
  previewHighlight: '[HIGHLIGHTED EXTRACTION]: Report expired Oct 1, 2026. Current assurance gap — do not rely on this attestation.',
  pageCount: 'Page 1 of 38'
 }
};

function buildFallbackModel(documentId: string | undefined, documentName: string, vendorName: string): InspectionModel {
 return {
  metadata: {
   fileName: documentName,
   fileSize: 'Pending ingestion',
   sha256Hash: 'pending-extraction',
   receivedDate: 'Pending ingestion',
   owner: 'Unassigned'
  },
  extractedFacts: [
   { id: 'g-1', fact: 'Document ingestion has not completed extraction for this file yet. Facts will appear here once processing finishes.', confidence: 'Pending' }
  ],
  controlMappings: [],
  findings: [],
  confidenceScore: '—',
  citationRef: documentId ?? 'N/A',
  previewHeading: 'DOCUMENT PREVIEW UNAVAILABLE',
  previewScope: `No preview content extracted yet for documents associated with ${vendorName}.`,
  previewOpinion: 'The extraction pipeline has not processed this file.',
  previewHighlight: '[HIGHLIGHTED EXTRACTION]: No extracted passages available yet for this document.',
  pageCount: 'Page 1 of 1'
 };
}

function getControlStatusClass(status: string): string {
 if (status === 'Exception Noted') return 'text-[10px] font-bold text-risk-moderate';
 if (status === 'Expired') return 'text-[10px] font-bold text-risk-high';
 return 'text-[10px] font-bold text-status-success';
}

function getFindingSeverityClass(severity: string): { badge: string; card: string } {
 switch (severity) {
  case 'Critical':
   return {
    badge: 'bg-risk-critical/10 text-risk-critical border ',
    card: 'border-risk-critical/20 bg-risk-critical/5'
   };
  case 'High':
   return {
    badge: 'bg-risk-high/10 text-risk-high border ',
    card: 'border-risk-high/20 bg-risk-high/5'
   };
  case 'Moderate':
   return {
    badge: 'bg-risk-moderate/10 text-risk-moderate border ',
    card: 'border-risk-moderate/20 bg-risk-moderate/5'
   };
  default:
   return {
    badge: 'bg-surface text-text-secondary border border-border-strong',
    card: 'border-border bg-base'
   };
 }
}

export function EvidenceInspectorWorkspace({
 documentId,
 documentName = 'CloudScale_SOC2_TypeII_2026.pdf', 
 vendorName = 'CloudScale Infrastructure',
 onBack 
}: EvidenceInspectorProps) {
 const [reviewStatus, setReviewStatus] = useState<'Pending' | 'Verified' | 'Rejected'>('Pending');

 useEffect(() => {
  setReviewStatus('Pending');
 }, [documentId]);

 useEffect(() => {
  const handleKeyDown = (e: KeyboardEvent) => {
   if (e.key === 'Escape' && onBack) {
    onBack();
   }
  };
  document.addEventListener('keydown', handleKeyDown);
  return () => document.removeEventListener('keydown', handleKeyDown);
 }, [onBack]);

 const inspectionModel = documentId && EVIDENCE_MODELS[documentId]
  ? EVIDENCE_MODELS[documentId]
  : buildFallbackModel(documentId, documentName, vendorName);

 return (
  <div className="space-y-6 max-w-7xl mx-auto pb-28 animate-in fade-in duration-300">
  
  <div aria-live="polite" className="sr-only">
   Evidence inspector workspace loaded for document {inspectionModel.metadata.fileName}. Review status: {reviewStatus}.
  </div>

  {/* Navigation Header */}
  <div className="flex items-center justify-between border-b border-border pb-4">
   <button 
    type="button"
    onClick={onBack ? onBack : () => window.history.back()}
    className="text-xs font-semibold text-text-tertiary hover:text-text-primary transition-colors flex items-center gap-1.5 focus:outline-none focus:ring-2 focus:ring-info rounded px-1"
   >
    <ArrowLeft aria-hidden="true" size={14} />
    <span>Back to Intake & Processing</span>
   </button>
   <div className="flex items-center gap-2">
    <Button type="button" variant="secondary" size="sm" onClick={() => {
 const summary = `Evidence Review Summary\nGenerated: ${new Date().toLocaleString()}\nStatus: ${reviewStatus}\n\n(Full document export connects to document storage on backend integration.)`;
 const blob = new Blob([summary], { type: 'text/plain' });
 const url = URL.createObjectURL(blob);
 const a = document.createElement('a');
 a.href = url; a.download = 'evidence-review-summary.txt'; a.click();
 URL.revokeObjectURL(url);
 }}>
     <Download aria-hidden="true" size={14} className="mr-1.5" />
     Download Summary
    </Button>
   </div>
  </div>

  {/* SPLIT INSPECTION LAYOUT */}
  <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
  
  {/* LEFT: Document Viewer / Preview (5 cols) */}
  <div className="lg:col-span-5 space-y-4">
   <Card className="shadow-sm overflow-hidden h-[760px] flex flex-col">
    <CardHeader className="bg-surface border-b border-border px-5 py-3.5 flex flex-row items-center justify-between shrink-0">
     <div className="flex items-center gap-2">
      <FileText aria-hidden="true" size={16} className="text-info" />
      <span className="text-xs font-bold text-text-primary truncate">{inspectionModel.metadata.fileName}</span>
     </div>
     <span className="text-[10px] font-mono text-text-tertiary bg-base px-2 py-0.5 rounded border border-border">{inspectionModel.pageCount}</span>
    </CardHeader>

    <div className="flex-1 bg-surface-hover/50 p-6 overflow-y-auto flex flex-col items-center justify-start text-left space-y-4 font-mono text-xs text-text-secondary select-none">
     <div className="w-full bg-surface p-6 rounded-xl border border-border shadow-sm space-y-4">
      <div className="border-b border-border-subtle pb-3">
       <h3 className="font-bold text-text-primary text-sm m-0">{inspectionModel.previewHeading}</h3>
       <p className="text-[11px] text-text-tertiary mt-0.5 m-0">{vendorName}</p>
      </div>

      <div className="space-y-2 text-[11px] leading-relaxed text-text-secondary">
       <p className="m-0"><strong className="text-text-primary">Scope:</strong> {inspectionModel.previewScope}</p>
       <p className="m-0"><strong className="text-text-primary">Opinion:</strong> {inspectionModel.previewOpinion}</p>
       <p className="bg-info/5 p-2.5 rounded border border-info/20 text-text-primary font-medium m-0">
        {inspectionModel.previewHighlight}
       </p>
      </div>
     </div>

     <div className="w-full bg-surface p-6 rounded-xl border border-border shadow-sm opacity-60 space-y-2">
      <span className="text-[10px] uppercase font-bold text-text-tertiary block">[Page 2 Preview - System Configuration Schedule]</span>
      <div className="h-4 w-3/4 bg-border rounded" />
      <div className="h-4 w-1/2 bg-border rounded" />
     </div>
    </div>

    <div className="p-3 bg-surface border-t border-border text-[11px] text-text-tertiary flex items-center justify-between shrink-0 font-mono">
     <span>SHA-256: {inspectionModel.metadata.sha256Hash.substring(0, 16)}...</span>
     <span className="text-status-success font-semibold">Integrity Verified</span>
    </div>
   </Card>
  </div>

  {/* RIGHT: Metadata, Facts, Controls, Findings, Citations & Actions (7 cols) */}
  <div className="lg:col-span-7 space-y-6">
  
  <Card className="shadow-sm">
   <CardContent className="p-5 space-y-4">
    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-border-subtle pb-4">
     <div>
      <span className="text-[10px] font-semibold uppercase tracking-wider text-text-tertiary block">Associated Vendor</span>
      <h2 className="text-base font-bold text-text-primary mt-0.5 m-0">{vendorName}</h2>
     </div>
     <div className="flex items-center gap-2">
      <span className="text-xs font-mono font-bold text-info bg-info/10 px-2.5 py-1 rounded border border-info/30">
       {inspectionModel.confidenceScore}
      </span>
      <span className="text-xs font-mono font-bold text-text-primary bg-base px-2.5 py-1 rounded border border-border">
       Ref: {inspectionModel.citationRef}
      </span>
     </div>
    </div>

    <div className="grid grid-cols-2 gap-4 text-xs">
     <div>
      <span className="text-text-tertiary block">Ingestion Source</span>
      <strong className="text-text-primary font-medium">{inspectionModel.metadata.receivedDate}</strong>
     </div>
     <div>
      <span className="text-text-tertiary block">Assigned Reviewer</span>
      <strong className="text-text-primary font-medium">{inspectionModel.metadata.owner}</strong>
     </div>
     <div>
      <span className="text-text-tertiary block">File Size</span>
      <strong className="text-text-primary font-medium">{inspectionModel.metadata.fileSize}</strong>
     </div>
     <div>
      <span className="text-text-tertiary block">Review Status</span>
      <strong className={`font-medium ${
       reviewStatus === 'Verified' ? 'text-status-success' :
       reviewStatus === 'Rejected' ? 'text-risk-critical' : 'text-text-primary'
      }`}>{reviewStatus}</strong>
     </div>
    </div>
   </CardContent>
  </Card>

  <Card className="shadow-sm">
   <CardHeader className="border-b border-border px-5 py-3.5">
    <CardTitle className="text-sm flex items-center gap-2">
     <Layers aria-hidden="true" size={16} className="text-text-secondary" />
     Extracted Facts & Telemetry
    </CardTitle>
   </CardHeader>
   <CardContent className="p-5 space-y-3">
    {inspectionModel.extractedFacts.map((fact) => (
     <div key={fact.id} className="p-3 rounded-lg border border-border bg-base flex items-start justify-between gap-3">
      <p className="text-xs text-text-primary font-medium leading-relaxed m-0">{fact.fact}</p>
      <span className="text-[10px] font-semibold uppercase px-2 py-0.5 rounded bg-surface border border-border-strong text-text-secondary shrink-0">
       {fact.confidence}
      </span>
     </div>
    ))}
   </CardContent>
  </Card>

  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
  
  <Card className="shadow-sm flex flex-col h-full">
   <CardHeader className="border-b border-border px-5 py-3.5">
    <CardTitle className="text-sm flex items-center gap-2">
     <ShieldCheck aria-hidden="true" size={16} className="text-status-success" />
     Control Mappings
    </CardTitle>
   </CardHeader>
   <CardContent className="p-5 space-y-3 flex-1">
    {inspectionModel.controlMappings.length > 0 ? (
     inspectionModel.controlMappings.map((ctrl, idx) => (
      <div key={idx} className="p-3 rounded-lg border border-border bg-base space-y-1">
       <div className="flex items-center justify-between">
        <span className="text-xs font-mono font-bold text-text-primary">{ctrl.controlId}</span>
        <span className={getControlStatusClass(ctrl.status)}>{ctrl.status}</span>
       </div>
       <p className="text-xs text-text-secondary m-0">{ctrl.description}</p>
      </div>
     ))
    ) : (
     <p className="text-xs text-text-tertiary m-0">No control mappings yet — extraction pending.</p>
    )}
   </CardContent>
  </Card>

  <Card className="shadow-sm flex flex-col h-full">
   <CardHeader className="border-b border-border px-5 py-3.5">
    <CardTitle className="text-sm flex items-center gap-2">
     <AlertTriangle aria-hidden="true" size={16} className="text-risk-critical" />
     Associated Findings
    </CardTitle>
   </CardHeader>
   <CardContent className="p-5 space-y-3 flex-1">
    {inspectionModel.findings.length > 0 ? (
     inspectionModel.findings.map((find) => {
      const severityStyles = getFindingSeverityClass(find.severity);
      return (
       <div key={find.id} className={`p-3 rounded-lg border space-y-1 ${severityStyles.card}`}>
        <div className="flex items-center justify-between">
         <span className={`text-[10px] font-semibold uppercase px-2 py-0.5 rounded border ${severityStyles.badge}`}>
          {find.severity} Severity
         </span>
        </div>
        <p className="text-xs text-text-primary font-medium leading-snug m-0">{find.description}</p>
       </div>
      );
     })
    ) : (
     <p className="text-xs text-text-tertiary m-0">No findings recorded for this document yet.</p>
    )}
   </CardContent>
  </Card>

  </div>

  <Card className="shadow-sm bg-surface-hover/50">
   <CardContent className="p-5 flex flex-col sm:flex-row items-center justify-between gap-4">
    <div>
     <span className="text-xs font-bold text-text-primary block">Analyst Governance Decision</span>
     <p className="text-xs text-text-secondary m-0">Verify document validity to push citation into the Evidence Repository.</p>
    </div>

    <div className="flex items-center gap-2.5 w-full sm:w-auto">
     <Button 
      type="button"
      variant="secondary" 
      size="sm" 
      onClick={() => { setReviewStatus('Rejected'); }}
      className="flex-1 sm:flex-none text-xs font-semibold text-risk-critical hover:bg-risk-critical/10"
     >
      <X aria-hidden="true" size={14} className="mr-1" /> Reject
     </Button>
     <Button 
      type="button"
      variant="primary" 
      size="sm" 
      onClick={() => { setReviewStatus('Verified'); }}
      className="flex-1 sm:flex-none text-xs font-semibold"
     >
      <Check aria-hidden="true" size={14} className="mr-1" /> Verify & Publish
     </Button>
    </div>
   </CardContent>
  </Card>

  </div>

  </div>

  </div>
 );
}
