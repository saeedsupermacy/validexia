import { useState, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import {
  Building2, FileCheck, ClipboardList, MessageSquare, Upload, ShieldCheck,
  Clock, CheckCircle2, AlertCircle, Send, X, FileText
} from 'lucide-react';
import { Card, CardHeader, CardTitle, CardContent, Button } from '../../shared/ui/core';
import { getVendorsWithStatus } from '../../data/vendors';
import { getNavParams, clearNavParams } from '../../shared/navigation';

interface PortalRequest {
  id: string;
  type: 'questionnaire' | 'evidence';
  title: string;
  due: string;
  status: 'pending' | 'in-progress' | 'submitted';
  framework?: string;
  submittedFile?: string;
}

interface PortalDocument {
  name: string;
  type: string;
  uploaded: string;
  status: 'Verified' | 'Under Review' | 'Expired';
}

const DEMO_REQUESTS: PortalRequest[] = [
  { id: 'req-1', type: 'questionnaire', title: 'Annual Cloud Security Assessment (SIG Core)', due: 'Oct 24, 2026', status: 'in-progress', framework: 'SIG Core v2025' },
  { id: 'req-2', type: 'evidence', title: 'SOC 2 Type II Report — 2026 renewal', due: 'Oct 31, 2026', status: 'pending' },
  { id: 'req-3', type: 'evidence', title: 'Penetration Test Report (external)', due: 'Nov 15, 2026', status: 'pending' },
];

const DEMO_DOCS: PortalDocument[] = [
  { name: 'SOC 2 Type II Report 2025', type: 'Attestation', uploaded: 'Mar 12, 2026', status: 'Expired' },
  { name: 'ISO 27001 Certificate', type: 'Certification', uploaded: 'Jan 28, 2026', status: 'Verified' },
  { name: 'SIG Lite Questionnaire — 2025', type: 'Questionnaire', uploaded: 'Sep 14, 2026', status: 'Verified' },
];

const DEMO_MESSAGES = [
  { from: 'Sarah Jenkins', role: 'TPRM Analyst', text: 'Hi — your SOC 2 report expired Oct 1. Could you upload the renewed version when ready?', time: 'Oct 7, 10:24 AM', mine: false },
  { from: 'You', role: 'Vendor', text: 'On it — our auditors are finalizing it this week. Will upload by Oct 20.', time: 'Oct 7, 2:15 PM', mine: true },
];

export function VendorPortalWorkspace() {
  const vendors = getVendorsWithStatus().filter(v => v.status === 'Active');
  const [selectedVendorId, setSelectedVendorId] = useState(vendors[0]?.id || '');

 // Deep link: pre-select the vendor when arriving with ?vendorId= (exact ID match first, then name)
 useEffect(() => {
 const params = getNavParams();
 const vid = params.vendorId || params.vendorName;
 if (vid && typeof vid === 'string') {
 const match = vendors.find(v => v.id === vid)
 || vendors.find(v => v.name.toLowerCase() === vid.toLowerCase());
 if (match) setSelectedVendorId(match.id);
 }
 if (vid) clearNavParams();
 // eslint-disable-next-line react-hooks/exhaustive-deps
 }, []);
  const [requests, setRequests] = useState(DEMO_REQUESTS);
  const [docs, setDocs] = useState<PortalDocument[]>(DEMO_DOCS);
  const [messageText, setMessageText] = useState('');
  const [messages, setMessages] = useState(DEMO_MESSAGES);
  const [uploadToast, setUploadToast] = useState(false);

  // Questionnaire modal (per-request wizard)
  const [openQuestionnaireId, setOpenQuestionnaireId] = useState<string | null>(null);
  const [answers, setAnswers] = useState<{ encryptAtRest: string; incidentResponse: string; notes: string }>({
    encryptAtRest: '', incidentResponse: '', notes: '',
  });

  // Real file inputs: vault upload + per-request evidence upload
  const vaultFileRef = useRef<HTMLInputElement | null>(null);
  const requestFileRef = useRef<HTMLInputElement | null>(null);
  const [requestFileTarget, setRequestFileTarget] = useState<string | null>(null);

  const vendor = vendors.find(v => v.id === selectedVendorId) || vendors[0];
  if (!vendor) return null;

  const pendingCount = requests.filter(r => r.status !== 'submitted').length;

  const markSubmitted = (id: string, submittedFile?: string) => {
    setRequests(prev => prev.map(r => r.id === id ? { ...r, status: 'submitted' as const, submittedFile } : r));
  };

  const sendMessage = () => {
    if (!messageText.trim()) return;
    setMessages(prev => [...prev, { from: 'You', role: 'Vendor', text: messageText.trim(), time: 'Just now', mine: true }]);
    setMessageText('');
  };

  const flashUploadToast = () => {
    setUploadToast(true);
    setTimeout(() => setUploadToast(false), 3000);
  };

  // Vault: add the actually-chosen file to the document list
  const handleVaultUpload = () => {
    vaultFileRef.current?.click();
  };
  const onVaultFileChosen = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    setDocs(prev => [{ name: file.name, type: 'Upload', uploaded: 'Just now', status: 'Under Review' }, ...prev]);
    flashUploadToast();
  };

  // Evidence request: pick a file -> mark that request submitted with the file name
  const handleRequestUpload = (id: string) => {
    setRequestFileTarget(id);
    requestFileRef.current?.click();
  };
  const onRequestFileChosen = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    const target = requestFileTarget;
    setRequestFileTarget(null);
    if (!file || !target) return;
    markSubmitted(target, file.name);
  };

  // Questionnaire modal
  const openQuestionnaire = (id: string) => {
    setAnswers({ encryptAtRest: '', incidentResponse: '', notes: '' });
    setOpenQuestionnaireId(id);
  };
  const closeQuestionnaire = () => setOpenQuestionnaireId(null);
  const questionnaireValid = answers.encryptAtRest !== '' && answers.incidentResponse !== '';
  const submitQuestionnaire = () => {
    if (!questionnaireValid || !openQuestionnaireId) return;
    markSubmitted(openQuestionnaireId);
    setOpenQuestionnaireId(null);
  };
  useEffect(() => {
    if (!openQuestionnaireId) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') closeQuestionnaire(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [openQuestionnaireId]);

  return (
    <div className="space-y-6">
      {/* Page title for accessibility — breadcrumb is hidden on mobile */}
      <h1 className="text-page-title">Vendor Portal</h1>
      {/* Vendor context banner */}
      <div className="bg-info/5 border border-info/20 rounded-xl px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Building2 size={18} className="text-info" />
          <div>
            <p className="text-xs text-text-tertiary m-0">Viewing portal as</p>
            <p className="text-sm font-bold text-text-primary m-0">{vendor.name}</p>
          </div>
        </div>
        <select
          value={selectedVendorId}
          onChange={(e) => setSelectedVendorId(e.target.value)}
          className="bg-surface border border-border rounded-lg px-3 py-1.5 text-xs text-text-primary focus:outline-none focus:ring-2 focus:ring-info"
          aria-label="Switch vendor view"
        >
          {vendors.map(v => <option key={v.id} value={v.id}>{v.name}</option>)}
        </select>
      </div>

      {/* Header stats */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <Card>
          <CardContent className="p-5">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-info/10 border border-info/20 flex items-center justify-center text-info">
                <ShieldCheck size={20} />
              </div>
              <div>
                <p className="text-2xl font-bold text-text-primary m-0">{vendor.assessmentStatus}</p>
                <p className="text-xs text-text-tertiary m-0">Assessment status</p>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-5">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-status-warning/10 border border-status-warning/20 flex items-center justify-center text-status-warning">
                <Clock size={20} />
              </div>
              <div>
                <p className="text-2xl font-bold text-text-primary m-0 tabular-nums">{pendingCount}</p>
                <p className="text-xs text-text-tertiary m-0">Pending requests</p>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-5">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-status-success/10 border border-status-success/20 flex items-center justify-center text-status-success">
                <FileCheck size={20} />
              </div>
              <div>
                <p className="text-2xl font-bold text-text-primary m-0 tabular-nums">{docs.filter(d => d.status === 'Verified').length}</p>
                <p className="text-xs text-text-tertiary m-0">Verified documents</p>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 items-start">
        {/* Pending requests */}
        <Card>
          <CardHeader className="border-b border-border px-6 py-4">
            <CardTitle className="flex items-center gap-2 text-sm font-bold uppercase tracking-wider">
              <ClipboardList size={18} className="text-text-secondary" />
              Action Required
            </CardTitle>
          </CardHeader>
          <CardContent className="p-6 space-y-3">
            {requests.map(req => (
              <div key={req.id} className="p-4 rounded-xl border border-border bg-base space-y-2">
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <p className="text-sm font-bold text-text-primary m-0">{req.title}</p>
                    <p className="text-xs text-text-tertiary mt-0.5 m-0">
                      {req.type === 'questionnaire' ? 'Questionnaire' : 'Evidence upload'} • Due {req.due}
                      {req.framework && ` • ${req.framework}`}
                    </p>
                  </div>
                  {req.status === 'submitted'
                    ? <span className="flex items-center gap-1 text-[11px] font-bold uppercase text-status-success"><CheckCircle2 size={14} /> Submitted</span>
                    : req.status === 'in-progress'
                      ? <span className="flex items-center gap-1 text-[11px] font-bold uppercase text-info"><Clock size={14} /> In progress</span>
                      : <span className="flex items-center gap-1 text-[11px] font-bold uppercase text-status-warning"><AlertCircle size={14} /> Pending</span>
                  }
                </div>
                {req.status !== 'submitted' ? (
                  <Button size="sm" variant="primary" onClick={() => req.type === 'questionnaire' ? openQuestionnaire(req.id) : handleRequestUpload(req.id)}>
                    {req.type === 'questionnaire' ? (req.status === 'in-progress' ? 'Continue Questionnaire' : 'Start Questionnaire') : 'Upload Document'}
                  </Button>
                ) : (
                  req.submittedFile && (
                    <p className="text-xs text-text-tertiary m-0 flex items-center gap-1.5">
                      <FileText size={12} className="shrink-0" /> <span className="truncate">{req.submittedFile}</span>
                    </p>
                  )
                )}
              </div>
            ))}
            {pendingCount === 0 && (
              <p className="text-sm text-status-success font-medium text-center py-4">All caught up — nothing pending.</p>
            )}
          </CardContent>
        </Card>

        {/* Document vault */}
        <Card>
          <CardHeader className="border-b border-border px-6 py-4">
            <CardTitle className="flex items-center gap-2 text-sm font-bold uppercase tracking-wider">
              <FileCheck size={18} className="text-text-secondary" />
              Your Document Vault
            </CardTitle>
          </CardHeader>
          <CardContent className="p-6 space-y-3">
            {docs.map((doc, idx) => (
              <div key={idx} className="flex items-center justify-between p-3.5 rounded-lg border border-border bg-surface">
                <div>
                  <p className="text-sm font-bold text-text-primary m-0">{doc.name}</p>
                  <p className="text-xs text-text-tertiary mt-0.5 m-0">{doc.type} • Uploaded {doc.uploaded}</p>
                </div>
                <span className={`text-[10px] font-semibold uppercase px-2.5 py-1 rounded border ${
                  doc.status === 'Verified' ? 'bg-status-success/10 text-status-success border-status-success/20'
                  : doc.status === 'Expired' ? 'bg-risk-critical/10 text-risk-critical border-risk-critical/20'
                  : 'bg-status-warning/10 text-status-warning border-status-warning/20'
                }`}>
                  {doc.status}
                </span>
              </div>
            ))}
            <Button size="sm" variant="secondary" onClick={handleVaultUpload} className="w-full justify-center gap-2">
              <Upload size={14} /> Upload New Document
            </Button>
            {uploadToast && (
              <p className="text-xs text-status-success font-medium text-center animate-in fade-in">Document received — under review.</p>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Messages */}
      <Card>
        <CardHeader className="border-b border-border px-6 py-4">
          <CardTitle className="flex items-center gap-2 text-sm font-bold uppercase tracking-wider">
            <MessageSquare size={18} className="text-text-secondary" />
            Messages with Your Assessor
          </CardTitle>
        </CardHeader>
        <CardContent className="p-6 space-y-4">
          <div className="space-y-3 max-h-64 overflow-y-auto">
            {messages.map((msg, idx) => (
              <div key={idx} className={`flex ${msg.mine ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[75%] p-3 rounded-xl ${msg.mine ? 'bg-info text-white' : 'bg-base border border-border'}`}>
                  <p className={`text-[11px] font-semibold mb-1 ${msg.mine ? 'text-white/80' : 'text-text-tertiary'}`}>
                    {msg.from} • {msg.role}
                  </p>
                  <p className={`text-sm m-0 ${msg.mine ? 'text-white' : 'text-text-primary'}`}>{msg.text}</p>
                  <p className={`text-[10px] mt-1 m-0 ${msg.mine ? 'text-white/60' : 'text-text-tertiary'}`}>{msg.time}</p>
                </div>
              </div>
            ))}
          </div>
          <div className="flex items-center gap-2">
            <input
              type="text"
              value={messageText}
              onChange={(e) => setMessageText(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && sendMessage()}
              placeholder="Write a message to your assessor..."
              className="flex-1 bg-base border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info"
            />
            <Button size="sm" variant="primary" onClick={sendMessage} disabled={!messageText.trim()} aria-label="Send message">
              <Send size={14} />
            </Button>
          </div>
        </CardContent>
      </Card>

      {/* Hidden real file inputs */}
      <input ref={vaultFileRef} type="file" className="hidden" aria-hidden="true" tabIndex={-1} onChange={onVaultFileChosen} />
      <input ref={requestFileRef} type="file" className="hidden" aria-hidden="true" tabIndex={-1} onChange={onRequestFileChosen} />

      {/* Questionnaire modal */}
      {openQuestionnaireId && createPortal(
        <div role="dialog" aria-modal="true" aria-labelledby="questionnaire-title" className="fixed inset-0 z-[100] flex items-center justify-center bg-black/40 p-4 animate-in fade-in duration-200" onClick={closeQuestionnaire}>
          <div className="bg-surface rounded-2xl max-w-lg w-full p-6 space-y-5 shadow-[0_8px_30px_rgb(0,0,0,0.12)] dark:shadow-[0_8px_30px_rgb(0,0,0,0.5)]" onClick={(e) => e.stopPropagation()}>
            <div className="flex items-start justify-between">
              <div>
                <p className="text-[11px] font-bold uppercase tracking-wider text-text-tertiary">Questionnaire</p>
                <h2 id="questionnaire-title" className="text-base font-bold text-text-primary mt-1 m-0">
                  {requests.find(r => r.id === openQuestionnaireId)?.title}
                </h2>
              </div>
              <button type="button" onClick={closeQuestionnaire} className="p-1.5 rounded-lg hover:bg-surface-hover text-text-tertiary hover:text-text-primary focus:outline-none focus:ring-2 focus:ring-info" aria-label="Close questionnaire">
                <X size={18} />
              </button>
            </div>

            <fieldset className="space-y-2">
              <legend className="text-sm font-semibold text-text-primary">1. Is customer data encrypted at rest?</legend>
              <div className="flex gap-4">
                {['Yes', 'No'].map(opt => (
                  <label key={opt} className="flex items-center gap-2 text-sm text-text-secondary cursor-pointer">
                    <input
                      type="radio"
                      name="encryptAtRest"
                      value={opt}
                      checked={answers.encryptAtRest === opt}
                      onChange={(e) => setAnswers(a => ({ ...a, encryptAtRest: e.target.value }))}
                      className="accent-info"
                    />
                    {opt}
                  </label>
                ))}
              </div>
            </fieldset>

            <fieldset className="space-y-2">
              <legend className="text-sm font-semibold text-text-primary">2. Do you maintain a tested incident response plan?</legend>
              <div className="flex gap-4">
                {['Yes', 'No'].map(opt => (
                  <label key={opt} className="flex items-center gap-2 text-sm text-text-secondary cursor-pointer">
                    <input
                      type="radio"
                      name="incidentResponse"
                      value={opt}
                      checked={answers.incidentResponse === opt}
                      onChange={(e) => setAnswers(a => ({ ...a, incidentResponse: e.target.value }))}
                      className="accent-info"
                    />
                    {opt}
                  </label>
                ))}
              </div>
            </fieldset>

            <div className="space-y-2">
              <label htmlFor="questionnaire-notes" className="text-sm font-semibold text-text-primary block">3. Additional notes (optional)</label>
              <textarea
                id="questionnaire-notes"
                value={answers.notes}
                onChange={(e) => setAnswers(a => ({ ...a, notes: e.target.value }))}
                rows={3}
                placeholder="Anything the assessor should know..."
                className="w-full bg-base border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-tertiary focus:outline-none focus:ring-2 focus:ring-info"
              />
            </div>

            <div className="flex justify-end gap-2 pt-1">
              <Button size="sm" variant="secondary" onClick={closeQuestionnaire}>Cancel</Button>
              <Button size="sm" variant="primary" onClick={submitQuestionnaire} disabled={!questionnaireValid}>
                <CheckCircle2 size={14} className="mr-1.5" /> Submit Responses
              </Button>
            </div>
            {!questionnaireValid && (
              <p className="text-xs text-text-tertiary m-0">Answer questions 1 and 2 to enable submission.</p>
            )}
          </div>
        </div>,
        document.body
      )}
    </div>
  );
}
