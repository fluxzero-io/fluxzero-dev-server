import {ChangeDetectorRef, Component, computed, ElementRef, inject, input, output, signal, ViewChild} from '@angular/core';
import {formatBytes} from './format-bytes';
import {HISTORY_BUCKETS, resourceChart, ResourceMetric, ResourceSample, SAMPLE_INTERVAL} from './resource-history';

@Component({selector: 'dev-resource-graph', standalone: true, template: `
  <button #trigger class="icon-button graph-button" type="button" [attr.aria-label]="'Show ' + title() + ' graph'"
    title="Show graph" aria-haspopup="dialog" (click)="open()"><i class="bi bi-graph-up" aria-hidden="true"></i></button>
  <dialog #dialog class="resource-graph-dialog" [attr.aria-labelledby]="titleId" (close)="closed()" (cancel)="$event.preventDefault(); $event.stopPropagation(); close()">
    @if(visible()) {
      <header><div><h2 [id]="titleId">{{title()}}</h2><p>Last 5 minutes · Updates every 5 seconds</p></div>
        <button class="icon-button" type="button" aria-label="Close graph" autofocus (click)="close()"><i class="bi bi-x-lg" aria-hidden="true"></i></button></header>
      <dl class="graph-summary"><div><dt>Current</dt><dd>{{formatBytes(current())}}</dd></div>
        <div><dt>Peak</dt><dd>{{formatBytes(peak())}}</dd></div><div><dt>Limit</dt><dd>{{formatBytes(limit())}}</dd></div></dl>
      @if(peak() !== null) {
        <figure [attr.aria-label]="title() + ' over the last 5 minutes'">
          <div class="graph-plot">
            <div class="graph-y-axis" aria-hidden="true">@for(tick of ticks(); track $index) {<span>{{formatBytes(tick)}}</span>}</div>
            <svg viewBox="0 0 60 100" preserveAspectRatio="none" role="img" [attr.aria-label]="'Usage from 0 B to ' + formatBytes(ceiling())">
              @for(y of [0,25,50,75,100]; track y) {<line x1="0" x2="60" [attr.y1]="y" [attr.y2]="y" class="graph-grid" vector-effect="non-scaling-stroke"/>}
              @for(segment of segments(); track $index) {
                <path class="graph-area" [attr.d]="segment.area"/>
                <path class="graph-line" [attr.d]="segment.line" vector-effect="non-scaling-stroke"/>
              }
            </svg>
            <div class="graph-x-axis" aria-hidden="true">@for(time of times(); track $index) {<span>{{time}}</span>}</div>
          </div>
          <figcaption>Auto-scaled to measured usage. Gaps indicate missing measurements.</figcaption>
        </figure>
      } @else {<p class="graph-empty" role="status">No measurements available yet.</p>}
    }
  </dialog>
`, styles: `
  :host {display:inline-flex;flex:0 0 auto;}
  .graph-button {color:var(--dashboard-active);border:1px solid var(--dashboard-border);border-radius:8px;width:30px;height:30px;}
  .graph-button:hover {background:var(--dashboard-active-soft);}
  .resource-graph-dialog {width:min(760px,calc(100vw - 32px));max-height:calc(100dvh - 32px);box-sizing:border-box;padding:28px;border:1px solid var(--dashboard-border);border-radius:20px;background:var(--dashboard-surface);color:var(--dashboard-text);box-shadow:0 24px 90px #0004;text-align:left;white-space:normal;}
  .resource-graph-dialog::backdrop {background:#101d35a6;}
  header {display:flex;justify-content:space-between;align-items:flex-start;gap:16px;}
  h2 {font-size:20px;overflow-wrap:anywhere;margin:0;}
  p,figcaption {color:var(--dashboard-muted);font-size:12px;margin:6px 0 0;}
  .graph-summary {display:flex;flex-wrap:wrap;gap:24px 40px;margin:28px 0;}
  dt {font-size:12px;font-weight:400;color:var(--dashboard-muted);}
  dd {font-size:19px;font-weight:600;font-variant-numeric:tabular-nums;margin:6px 0 0;}
  figure {margin:0;}
  .graph-plot {display:grid;grid-template-columns:76px minmax(0,1fr);grid-template-rows:240px auto;gap:10px 12px;}
  .graph-y-axis {display:flex;flex-direction:column;justify-content:space-between;text-align:right;font-size:11px;color:var(--dashboard-muted);}
  svg {width:100%;height:100%;overflow:visible;color:var(--dashboard-active);}
  .graph-grid {stroke:var(--dashboard-border);stroke-width:1;}
  .graph-area {fill:currentColor;fill-opacity:.1;}
  .graph-line {fill:none;stroke:currentColor;stroke-width:2.5;stroke-linejoin:round;}
  .graph-x-axis {grid-column:2;display:flex;justify-content:space-between;gap:8px;font-size:11px;color:var(--dashboard-muted);font-variant-numeric:tabular-nums;}
  figcaption {margin-top:20px;}
  .graph-empty {padding:64px 0;text-align:center;}
  @media(max-width:550px) {.resource-graph-dialog {padding:20px;}.graph-plot {grid-template-columns:60px minmax(0,1fr);grid-template-rows:190px auto;gap:10px 8px;}dd {font-size:16px;}.graph-summary {gap:16px;}h2 {font-size:18px;}}
`})
export class ResourceGraphComponent {
  private static nextId = 0;
  readonly titleId = 'resource-graph-title-' + ++ResourceGraphComponent.nextId;
  readonly formatBytes = formatBytes;
  readonly title = input.required<string>();
  readonly samples = input<ResourceSample[]>([]);
  readonly metric = input<ResourceMetric>('devserverMemory');
  readonly componentId = input<string>();
  readonly current = input<number | null>();
  readonly limit = input<number | null>();
  readonly visible = signal(false);
  readonly openedChange = output<boolean>();
  @ViewChild('dialog') dialog!: ElementRef<HTMLDialogElement>;
  @ViewChild('trigger') trigger!: ElementRef<HTMLButtonElement>;
  private readonly changeDetector = inject(ChangeDetectorRef);
  readonly history = computed(() => {
    const samples = this.samples();
    const latest = Math.floor((samples.at(-1)?.at ?? 0) / SAMPLE_INTERVAL);
    return samples.filter(s => Math.floor(s.at / SAMPLE_INTERVAL) > latest - HISTORY_BUCKETS).map(s => ({
      ...s, applicationMemory: this.componentId() ? s.componentMemory?.[this.componentId()!] ?? null : s[this.metric()]
    }));
  });
  readonly peak = computed(() => {
    const values = this.history().map(s => s.applicationMemory).filter((v): v is number => v != null && Number.isFinite(v) && v >= 0);
    return values.length ? Math.max(...values) : null;
  });
  readonly ceiling = computed(() => {
    const peak = this.peak() || 1024;
    const step = 2 ** Math.floor(Math.log2(peak));
    return Math.ceil(peak * 1.15 / step) * step;
  });
  readonly ticks = computed(() => [1, .75, .5, .25, 0].map(n => this.ceiling() * n));
  readonly segments = computed(() => resourceChart(this.history().map(s => ({...s, applicationMemoryMax: this.ceiling()})), 'applicationMemory'));
  readonly times = computed(() => {
    const latest = Math.floor((this.samples().at(-1)?.at ?? Date.now()) / SAMPLE_INTERVAL) * SAMPLE_INTERVAL;
    return [HISTORY_BUCKETS - 1, (HISTORY_BUCKETS - 1) / 2, 0].map(offset =>
      new Date(latest - offset * SAMPLE_INTERVAL).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit'}));
  });
  open() {
    this.openedChange.emit(true);
    this.visible.set(true);
    this.changeDetector.detectChanges();
    this.dialog.nativeElement.showModal();
  }
  close() {
    this.dialog.nativeElement.close();
    this.closed();
  }
  closed() {
    if (!this.visible()) return;
    this.visible.set(false);
    this.trigger.nativeElement.focus();
    this.openedChange.emit(false);
  }
}
