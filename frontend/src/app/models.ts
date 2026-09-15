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
export interface Status {
  testOutput?: TestOutputLine[];
  resourceHistory?: ResourceSample[];
  project: string;
  projectDirectory: string;
  state: string;
  runtime: string;
  applications: string;
  tests: string;
  frontend: string;
  components?: {runningProcesses?: number; totalProcesses?: number; id: string; name: string; state: string; application: boolean; memoryBytes: number | null; memoryUsedBytes?: number | null; memoryMaxBytes?: number | null; port?: number | null; url?: string | null}[];
  maintenance?: {restartSupported?: boolean; applicationRestartSupported?: boolean; resetSupported?: boolean; busy: boolean; error: string};
  testResults?: {totalKnown?: boolean; runnable?: boolean; live?: boolean; incomplete?: boolean; state?: string; running?: boolean; expectedTotal?: number; available: boolean; passed: number; failed: number; skipped: number; total: number; label: string};
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
