import {Component, computed, input, signal} from '@angular/core';
import {Status} from './models';

@Component({selector:'dev-startup', standalone:true, template:`
  @if(startup(); as data) {
    @if(data.actions.length || data.state === 'failed') {
      <section aria-labelledby="startup-title">
        <h2 id="startup-title">Startup</h2>
        <div class="startup-card">
          @if(data.actions.length) {
            <button class="startup-summary" type="button" [attr.aria-expanded]="open()" aria-controls="startup-actions" (click)="expanded.set(!open())">
              <i class="bi" [class.bi-check-circle]="completed() === data.actions.length" [class.bi-exclamation-circle]="hasFailure()" [class.bi-clock]="!hasFailure() && completed() !== data.actions.length" [class.success]="completed() === data.actions.length" [class.failure]="hasFailure()" aria-hidden="true"></i>
              <span>{{completed()}} / {{data.actions.length}} completed@if(hasFailure()) {<span class="failure"> · Needs attention</span>}</span>
              <i class="bi chevron" [class.bi-chevron-down]="open()" [class.bi-chevron-right]="!open()" aria-hidden="true"></i>
            </button>
            <div id="startup-actions" [hidden]="!open()">
              @for(action of data.actions; track action.id) {
                <div class="startup-row">
                  <i class="bi" [class.bi-check-circle]="action.state === 'succeeded'" [class.bi-x-circle]="action.state === 'failed'" [class.bi-clock]="action.state !== 'succeeded' && action.state !== 'failed'" [class.success]="action.state === 'succeeded'" [class.failure]="action.state === 'failed'" aria-hidden="true"></i>
                  <span class="action-name">{{action.name}}</span><span class="action-state" [class.failure]="action.state === 'failed'">{{label(action.state)}}</span>
                </div>
              }
            </div>
          } @else {<p class="failure" role="status">Startup actions could not be loaded.</p>}
        </div>
      </section>
    }
  }`, styles:`
    :host {display:block;}
    section {margin-top:24px;} h2 {font-size:17px;margin:0 0 16px;}
    .startup-card {padding:8px 22px;background:var(--dashboard-surface);border:1px solid var(--dashboard-border);border-radius:16px;}
    .startup-summary,.startup-row {display:flex;align-items:center;gap:12px;padding:14px 0;font-size:13px;color:var(--dashboard-text);}
    .startup-summary {width:100%;border:0;background:transparent;text-align:left;font:inherit;font-size:13px;cursor:pointer;}
    .chevron {margin-left:auto;font-size:12px;color:var(--dashboard-muted);}
    .startup-row {border-top:1px solid var(--dashboard-border);}
    .action-name {flex:1;min-width:0;overflow-wrap:anywhere;}
    .action-state {font-size:12px;color:var(--dashboard-muted);}
    .success {color:var(--dashboard-success-text);}.failure {color:var(--dashboard-danger-text);}
    p {font-size:13px;}
    @media(max-width:650px) {.startup-card {padding:6px 18px;}.startup-row {gap:8px;}}
  `})
export class StartupComponent {
  readonly startup = input<Status['startup']>();
  readonly expanded = signal<boolean|null>(null);
  readonly completed = computed(() => this.startup()?.actions.filter(a => a.state === 'succeeded').length || 0);
  readonly hasFailure = computed(() => this.startup()?.state === 'failed' || !!this.startup()?.actions.some(a => a.state === 'failed' || a.state === 'blocked'));
  readonly open = computed(() => this.expanded() ?? this.hasFailure());
  label(state:string) {return ({succeeded:'Completed', failed:'Failed', blocked:'Blocked', running:'Running'} as Record<string,string>)[state] || 'Pending';}
}
