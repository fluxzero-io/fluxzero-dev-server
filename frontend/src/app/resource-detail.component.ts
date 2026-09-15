import {Component, ElementRef, inject, input, signal} from '@angular/core';
import {ResourceChartComponent} from './resource-chart.component';
import {ResourceSample, usedMemory} from './resource-history';
import {Status} from './models';
import {formatBytes} from './format-bytes';

@Component({selector: 'dev-resource-detail', standalone: true, imports: [ResourceChartComponent], host: {
  'tabindex': '0', '(mouseenter)': 'show()', '(mouseleave)': 'leave()', '(focus)': 'show()', '(blur)': 'visible.set(false)',
  '(keydown.escape)': 'visible.set(false)', '(window:resize)': 'visible.set(false)', '(window:scroll)': 'visible.set(false)',
  '[attr.aria-describedby]': 'visible() ? tooltipId : null'
}, template: `
  <ng-content/>
  @if(visible()) {
    <span class="resource-tooltip" [class.above]="above()" [id]="tooltipId" role="tooltip" [style.left.px]="left()" [style.top.px]="top()">
      <span class="resource-tooltip-content">
        @for(component of components(); track component.id) {
          <span class="resource-tooltip-row"><span>{{component.name}}</span>
            @if(kind() === 'status') {<span class="badge" [class.running]="component.state === 'running'" [class.starting]="component.state === 'starting'" [class.failed]="component.state === 'failed'">{{component.state}}</span>}
            @else {<span class="resource-tooltip-memory"><dev-resource-chart [samples]="samples()" [componentId]="component.id"/><span class="resource-value">{{formatBytes(usedMemory(component))}} / {{formatBytes(component.memoryMaxBytes)}}</span></span>}
          </span>
        } @empty {<span>No managed application processes</span>}
      </span>
    </span>
  }`
})
export class ResourceDetailComponent {
  readonly formatBytes = formatBytes;
  readonly usedMemory = usedMemory;
  private static nextId = 0;
  readonly tooltipId = 'resource-detail-' + ++ResourceDetailComponent.nextId;
  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
  components = input.required<NonNullable<Status['components']>>();
  kind = input.required<'status' | 'memory'>();
  samples = input<ResourceSample[]>([]);
  visible = signal(false);
  left = signal(0);
  top = signal(0);
  above = signal(false);
  show() {
    const rect = this.element.nativeElement.getBoundingClientRect();
    const viewportWidth = document.documentElement.clientWidth;
    this.left.set(Math.max(8, Math.min(rect.left, viewportWidth - Math.min(360, viewportWidth - 16) - 8)));
    const above = window.innerHeight - rect.bottom < this.components().length * (this.kind() === 'memory' ? 42 : 34) + 30 && rect.top > window.innerHeight / 2;
    this.above.set(above);
    this.top.set(above ? rect.top : rect.bottom);
    this.visible.set(true);
  }
  leave() { if (document.activeElement !== this.element.nativeElement) this.visible.set(false); }
}
