import type {Status} from './models';

export interface ResourceSample {
  at: number;
  applicationMemory: number | null;
  devserverMemory: number | null;
  monitoringStorage: number | null;
  monitoringStorageMax?: number | null;
  componentMemory?: Record<string, number | null>;
  applicationMemoryMax?: number | null;
  devserverMemoryMax?: number | null;
  componentMemoryMax?: Record<string, number | null>;
}
export type ResourceMetric = 'applicationMemory' | 'devserverMemory' | 'monitoringStorage';
export const SAMPLE_INTERVAL = 5000;
export const HISTORY_BUCKETS = 60;

type ComponentMemory = NonNullable<Status['components']>[number];
export function usedMemory(component: ComponentMemory): number | null {
  // Never pair RSS with a known heap limit when a heap sample is temporarily unavailable.
  return component.memoryUsedBytes === undefined ? component.memoryBytes
    : component.memoryUsedBytes ?? (component.memoryMaxBytes == null ? component.memoryBytes : null);
}
export function totalMemory(components: ComponentMemory[], maximum = false): number | null {
  const value = (c: ComponentMemory) => maximum ? c.memoryMaxBytes : usedMemory(c);
  if (!components.length || components.some(c => value(c) == null && (c.runningProcesses ?? (c.state === 'running' ? 1 : 0)) > 0)) return null;
  return components.reduce((sum, c) => sum + (value(c) || 0), 0);
}

/** Apply one server sample; reconnect replaces this projection with the server snapshot. */
export function appendSample(samples: ResourceSample[], sample: ResourceSample): ResourceSample[] {
  const bucket = Math.floor(sample.at / SAMPLE_INTERVAL);
  return [...samples.filter(previous => {
    const previousBucket = Math.floor(previous.at / SAMPLE_INTERVAL);
    return previousBucket > bucket - HISTORY_BUCKETS && previousBucket < bucket;
  }), sample].slice(-HISTORY_BUCKETS);
}

export function resourceLevels(samples: ResourceSample[], metric: ResourceMetric, componentId?: string): (number | null)[] {
  if (!samples.length) return [];
  const latest = Math.floor(samples[samples.length - 1].at / SAMPLE_INTERVAL);
  const values = new Map(samples.map(sample => [Math.floor(sample.at / SAMPLE_INTERVAL), {
    used: componentId ? sample.componentMemory?.[componentId] ?? null : sample[metric],
    max: componentId ? sample.componentMemoryMax?.[componentId] : sample[`${metric}Max`]
  }]));
  const window = Array.from({length: HISTORY_BUCKETS}, (_, i) => values.get(latest - HISTORY_BUCKETS + 1 + i));
  // Older server snapshots have no resource ceiling; keep their existing observed-peak scale.
  const peak = Math.max(1, ...window.map(value => value?.used ?? 0));
  return window.map(value => {
    if (value?.used == null) return null;
    const max = value.max === undefined ? peak : value.max;
    if (max == null || max <= 0) return value.used === 0 ? 0 : null;
    return Math.min(100, Math.max(0, value.used) * 100 / max);
  });
}

export interface ResourceChartSegment {
  line: string;
  area: string;
}

/** Join measured buckets only; each gap starts a separate filled area. */
export function resourceChart(samples: ResourceSample[], metric: ResourceMetric, componentId?: string): ResourceChartSegment[] {
  const levels = resourceLevels(samples, metric, componentId);
  const segments: ResourceChartSegment[] = [];
  for (let start = 0; start < levels.length; start++) {
    if (levels[start] == null) continue;
    let end = start;
    while (end + 1 < levels.length && levels[end + 1] != null) end++;
    // Half a bucket at either end also makes a single sample visible.
    let line = `M ${start} ${100 - levels[start]!}`;
    for (let i = start; i <= end; i++) line += ` L ${i + 0.5} ${100 - levels[i]!}`;
    line += ` L ${end + 1} ${100 - levels[end]!}`;
    segments.push({line, area: `${line} L ${end + 1} 100 L ${start} 100 Z`});
    start = end;
  }
  return segments;
}
