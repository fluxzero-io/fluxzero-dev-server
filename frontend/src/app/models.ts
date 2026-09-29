import type {ResourceSample} from './resource-history';
export interface Environment {
  id: string;
  directoryExists: boolean;
  projectName: string;
  projectDirectory: string;
  status: 'running' | 'stopped';
  port: number | null;
  consoleUrl: string | null;
  detail?: string;
}
export interface TestOutputLine {sequence:number; module:string; text:string;}
export type ProgressState = 'planned' | 'in_progress' | 'done';
export interface ProgressFeature {
  id:string; title:string; kind:'feature'|'bug'; status:ProgressState; description:string; acceptance:string[];
  createdAt:string; updatedAt:string; history:{at:string; status:ProgressState; verification:string}[];
}
export interface ProgressMilestone {id:string; title:string; description:string; features:ProgressFeature[];}
export interface ProjectProgress {revision:string|null; data:{version:number; milestones:ProgressMilestone[]}|null; error:string|null;}
export interface PreviewFrontend {id:string; path:string; state:string;}
export interface Status {
  publicApplicationUrl?:string;
  frontends?: PreviewFrontend[];
  update?: {status?:string; currentVersion?:string; latestVersion?:string; attemptId?:string; phase?:string; error?:string};
  progress?:ProjectProgress;
  versions?: {devServer?: string; fluxzero?: string};
  workspaceIssue?: string;
  startedAt?: number;
  startup?: {state:string; sessionId?:string; actions:{id:string; name:string; state:string; hash?:string}[]};
  profiles?: {active: string | null; available: string[]; switchSupported: boolean; error?: string};
  testOutput?: TestOutputLine[];
  resourceHistory?: ResourceSample[];
  project: string;
  projectDirectory: string;
  state: string;
  /** Historical protocol field: status of the local Test Server. */
  runtime: string;
  applications: string;
  tests: string;
  frontend: string;
  components?: {version?: string | null; restartSupported?: boolean; runningProcesses?: number; totalProcesses?: number; id: string; name: string; state: string; application: boolean; memoryBytes: number | null; memoryUsedBytes?: number | null; memoryMaxBytes?: number | null; port?: number | null; url?: string | null}[];
  maintenance?: {stopSupported?: boolean; workspaceStopped?: boolean; restartSupported?: boolean; applicationRestartSupported?: boolean; resetSupported?: boolean; busy: boolean; error: string};
  testResults?: {paused?: boolean; totalKnown?: boolean; runnable?: boolean; live?: boolean; incomplete?: boolean; state?: string; running?: boolean; expectedTotal?: number; available: boolean; passed: number; failed: number; skipped: number; total: number; label: string};
  monitoring: {enabled: boolean; storage?: string; state?: string; detail?: string; droppedLogLines?: number; resources?: any};
}
export const monitoringViews = [
  {key: 'messages', label: 'Audit trail', icon: 'chat-square-text'},
  {key: 'logs', label: 'Logs', icon: 'card-text'},
  {key: 'trace', label: 'Traces', icon: 'diagram-3'},
  {key: 'issues', label: 'Issues', icon: 'exclamation-circle'},
  {key: 'documents', label: 'Documents', icon: 'file-earmark-text'},
  {key: 'insights', label: 'Insights', icon: 'speedometer2'},
  {key: 'visualize', label: 'Visualize', icon: 'bar-chart'}
];
export function monitoringPath(value: unknown): string | null {
  if (typeof value !== 'string' || !/^\/[a-z]+(?:[/?#]|$)/.test(value)) return null;
  return monitoringViews.some(v => value.split(/[/?#]/)[1] === v.key) ? value : null;
}

/** Keep registry-provided navigation on a local dev console, including modified clicks. */
export function environmentConsoleUrl(environment: Environment): string | null {
  if (environment.status !== 'running' || !environment.consoleUrl) return null;
  try {
    const url = new URL(environment.consoleUrl);
    if (url.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
      || url.username || url.password || url.pathname !== '/_fluxzero/dev/' || url.search) return null;
    url.hash = 'application';
    return url.href;
  } catch { return null; }
}

/** Preview the configured public application origin and its validated mount paths. */
export function applicationUrl(status: Status | undefined, origin = location.origin): string | null {
  origin = applicationOrigin(status, origin);
  if (status?.frontends?.length) {
    const frontends = previewFrontends(status);
    const frontend = frontends.find(f => f.path === '/') || frontends[0];
    return frontend ? new URL(frontend.path, origin).href : null;
  }
  const components = status?.components || [];
  const frontends = components.filter(component => component.application && component.id.startsWith('frontend-'));
  // The backend can be running before the gateway can serve the UI.
  if (frontends.length && status?.frontend !== 'running') return null;
  for (const component of components) {
    if (!component.application || !component.url) continue;
    try {
      const url = new URL(component.url);
      if (url.origin !== origin || !['http:', 'https:'].includes(url.protocol) || url.username || url.password
        || decodeURIComponent(url.pathname).startsWith('/_fluxzero/')) continue;
      return url.href;
    } catch { /* Invalid or incomplete status cannot become a frame URL. */ }
  }
  return null;
}

/** Public mount paths are the complete preview contract; upstream URLs never enter this model. */
export function previewFrontends(status:Status|undefined):PreviewFrontend[] {
  return (status?.frontends || []).filter(frontend => {
    const path=frontend.path;
    if(!path.startsWith('/') || path.startsWith('//') || /[\\?#]/.test(path)) return false;
    try {
      const decoded=decodeURIComponent(path);
      return !decoded.startsWith('/_fluxzero') && !decoded.startsWith('//')
        && !/[\\?#]/.test(decoded) && !decoded.split('/').some(part=>part==='.' || part==='..');
    } catch {return false;}
  });
}

export function matchingFrontend(frontends:PreviewFrontend[], address:string):PreviewFrontend|undefined {
  try {
    const path=new URL(address).pathname;
    return [...frontends].sort((a,b)=>b.path.length-a.path.length).find(frontend =>
      frontend.path==='/' || path===frontend.path || path.startsWith(frontend.path.replace(/\/$/,'')+'/'));
  } catch {return undefined;}
}

/** The server supplies a distinct public origin only when the profile selects an ingress service. */
export function applicationOrigin(status:Status|undefined, fallback=location.origin):string {
  if(!status?.publicApplicationUrl) return fallback;
  try {
    const url=new URL(status.publicApplicationUrl);
    if(['http:','https:'].includes(url.protocol) && !url.username && !url.password
      && url.pathname==='/' && !url.search && !url.hash) return url.origin;
  } catch { /* Invalid status cannot provide a navigation origin. */ }
  return fallback;
}
