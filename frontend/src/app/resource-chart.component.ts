import {Component, computed, input} from '@angular/core';
import {resourceChart, ResourceMetric, ResourceSample, HISTORY_BUCKETS} from './resource-history';

@Component({selector: 'dev-resource-chart', standalone: true, host: {'aria-hidden':'true'}, template: `
  <svg [attr.viewBox]="viewBox" preserveAspectRatio="none" focusable="false">
    @for(segment of segments(); track $index) {
      <path class="resource-chart-area" [attr.d]="segment.area"/>
      <path class="resource-chart-line" [attr.d]="segment.line" vector-effect="non-scaling-stroke"/>
    }
  </svg>
`})
export class ResourceChartComponent {
  readonly viewBox = '0 0 ' + HISTORY_BUCKETS + ' 100';
  samples = input<ResourceSample[]>([]);
  metric = input<ResourceMetric>('devserverMemory');
  componentId = input<string>();
  segments = computed(() => resourceChart(this.samples(), this.metric(), this.componentId()));
}
