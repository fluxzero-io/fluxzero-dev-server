import {Component, ElementRef, inject, input, signal} from '@angular/core';
import {ResourceGraphComponent} from './resource-graph.component';
import {ResourceSample, usedMemory} from './resource-history';
import {Status} from './models';
import {formatBytes} from './format-bytes';

@Component({selector: 'dev-resource-detail', standalone: true, imports: [ResourceGraphComponent], host: {
  'tabindex': '0', '(mouseenter)': 'show()', '(mouseleave)': 'leave()', '(focus)': 'show()', '(focusout)': 'focusOut($event)',
  '(keydown.escape)': 'dismiss()', '(window:resize)': 'dismiss()', '(window:scroll)': 'dismiss()',
  '[attr.aria-describedby]': 'visible() ? tooltipId : null'
}, template: `
  <ng-content/>
  @if(visible()) {
    <span class="resource-tooltip" [class.above]="above()" [id]="tooltipId" [attr.role]="kind() === 'memory' ? 'dialog' : 'tooltip'" [attr.aria-label]="kind() === 'memory' ? 'Memory by service' : null" [style.left.px]="left()" [style.top.px]="top()">
      <span class="resource-tooltip-content" [style.max-height.px]="maxHeight()">
        @for(component of components(); track component.id) {
          <span class="resource-tooltip-row"><span>{{component.name}}</span>
            @if(kind() === 'status') {<span class="badge" [class.running]="component.state === 'running'" [class.starting]="component.state === 'starting'" [class.degraded]="component.state === 'degraded'" [class.failed]="component.state === 'failed'">{{component.state}}</span>}
            @else {<span class="resource-tooltip-memory"><span class="resource-value">{{formatBytes(usedMemory(component))}}@if(component.memoryMaxBytes != null) { / {{formatBytes(component.memoryMaxBytes)}}}</span><dev-resource-graph [title]="component.name + ' · Memory'" [samples]="samples()" [componentId]="component.id" [current]="usedMemory(component)" [limit]="component.memoryMaxBytes" (openedChange)="graphOpen.set($event)"/></span>}
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
  graphOpen = signal(false);
  left = signal(0);
  top = signal(0);
  above = signal(false);
  maxHeight = signal(400);
  show() {
    const rect = this.element.nativeElement.getBoundingClientRect();
    const viewportWidth = document.documentElement.clientWidth;
    this.left.set(Math.max(8, Math.min(rect.left, viewportWidth - Math.min(this.kind() === 'memory' ? 540 : 360, viewportWidth - 16) - 8)));
    const rowHeight = this.kind() === 'memory' ? (viewportWidth <= 450 ? 70 : 42) : 34;
    const above = window.innerHeight - rect.bottom < this.components().length * rowHeight + 30 && rect.top > window.innerHeight / 2;
    this.maxHeight.set(Math.max(64, Math.min(window.innerHeight / 2 - 24, (above ? rect.top : window.innerHeight - rect.bottom) - 24)));
    this.above.set(above);
    this.top.set(above ? rect.top : rect.bottom);
    this.visible.set(true);
  }
  dismiss() { if (!this.graphOpen()) this.visible.set(false); }
  focusOut(event: FocusEvent) {
    if (!this.element.nativeElement.contains(event.relatedTarget as Node | null)) this.dismiss();
  }
  leave() { if (!this.element.nativeElement.contains(document.activeElement)) this.dismiss(); }
}
