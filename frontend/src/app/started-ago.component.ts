import {Component, computed, DestroyRef, inject, input, NgZone, signal} from '@angular/core';

export function startedAgo(start:number, now:number):string {
  const minutes=Math.max(0, Math.floor((now-start)/60000));
  if(minutes < 1) return 'Started just now';
  const [value, unit]=minutes < 60 ? [minutes,'minute'] : minutes < 1440 ? [Math.floor(minutes/60),'hour'] : [Math.floor(minutes/1440),'day'];
  return `Started ${value} ${unit}${value === 1 ? '' : 's'} ago`;
}

@Component({selector:'dev-started-ago', standalone:true, template:`
  @if(valid()) {<time [attr.datetime]="iso()" [title]="exact()">{{relative()}}</time>}
`, styles:`:host {color:var(--dashboard-muted);font-size:12px;white-space:nowrap;}`})
export class StartedAgoComponent {
  readonly startedAt = input<number>();
  readonly now = signal(Date.now());
  readonly valid = computed(() => !!this.startedAt() && Number.isFinite(new Date(this.startedAt()!).getTime()));
  readonly iso = computed(() => this.valid() ? new Date(this.startedAt()!).toISOString() : '');
  readonly exact = computed(() => this.valid() ? new Date(this.startedAt()!).toLocaleString(undefined, {dateStyle:'full',timeStyle:'long'}) : '');
  readonly relative = computed(() => startedAgo(this.startedAt() || this.now(),this.now()));
  constructor() {
    const zone=inject(NgZone);
    const timer=zone.runOutsideAngular(() => setInterval(() => zone.run(() => this.now.set(Date.now())),30000));
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }
}
