/**
 * Lightweight cross-tab navigation.
 * Lets any component jump to another tab, optionally carrying context
 * (e.g. navigateTo('vendors', { vendorId: 'VND-002' }) highlights that vendor).
 * Registered once by App.tsx; no prop drilling needed.
 */

export type ViewKey =
  | 'command' | 'vendors' | 'assessments' | 'evidence' | 'evidence-inbox'
  | 'extracted-facts' | 'confidence' | 'risk-events' | 'monitoring'
  | 'ai-playground' | 'remediation' | 'dependencies' | 'fourth-parties'
  | 'decisions' | 'audit-log' | 'reports' | 'integrations' | 'team' | 'settings'
  | 'vendor-portal';

export type NavParams = Record<string, string | number | boolean | undefined>;

type NavHandler = (view: ViewKey, params?: NavParams) => void;

let handler: NavHandler | null = null;
let currentParams: NavParams = {};
/** Params parsed from the URL hash on load / back-button. Module-scoped (not a window global). */
let hashParams: NavParams = {};

export function registerNavigator(h: NavHandler): void {
  handler = h;
}

export function navigateTo(view: ViewKey, params?: NavParams): void {
  currentParams = params || {};
  // Sync to URL hash for shareable/bookmarkable links and back-button support
  try {
    const hash = '#' + view + (params && Object.keys(params).length
      ? '?' + new URLSearchParams(
          Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined).map(([k, v]) => [k, String(v)]))
        ).toString()
      : '');
    if (window.location.hash !== hash) {
      window.history.pushState(null, '', hash);
    }
  } catch { /* noop — hash sync is best-effort */ }
  if (handler) {
    handler(view, params);
  }
  requestAnimationFrame(() => {
    document.getElementById('main-content')?.scrollTo({ top: 0 });
  });
}

/** Parse the current URL hash into a view + params. Returns null if no valid view. */
export function parseHash(): { view: ViewKey; params: NavParams } | null {
  try {
    const raw = window.location.hash.replace(/^#\/?/, '');
    if (!raw) return null;
    const [view, query] = raw.split('?');
    const validViews: ViewKey[] = ['command', 'vendors', 'assessments', 'evidence', 'evidence-inbox',
      'extracted-facts', 'confidence', 'risk-events', 'monitoring', 'ai-playground', 'remediation',
      'dependencies', 'fourth-parties', 'decisions', 'audit-log', 'reports', 'integrations', 'team',
      'settings', 'vendor-portal'];
    if (!validViews.includes(view as ViewKey)) return null;
    const params: NavParams = {};
    if (query) {
      new URLSearchParams(query).forEach((v, k) => { params[k] = v; });
    }
    return { view: view as ViewKey, params };
  } catch { return null; }
}

/** Read params passed by the last navigateTo call (or from URL hash on load). */
export function getNavParams(): NavParams {
  // URL hash params (page load / back-button) take precedence on first read
  if (Object.keys(hashParams).length > 0) {
    return hashParams;
  }
  return currentParams;
}

/** Stash params parsed from the URL hash so the destination view can consume them. */
export function stashHashParams(p: NavParams): void {
  hashParams = p || {};
}

/** Clear params after the destination view has consumed them. */
export function clearNavParams(): void {
  currentParams = {};
  hashParams = {};
}
