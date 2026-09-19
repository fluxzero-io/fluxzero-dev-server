import {Component, computed, input, signal} from '@angular/core';
import {Status} from './models';

@Component({selector:'dev-startup', standalone:true, template:`
  @if(startup(); as data) {
    @if(data.actions.length || data.state === 'failed') {
      <section aria-labelledby="startup-title">
        <h2 id="startup-title">Startup data</h2>
        <div class="startup-card">
          @if(data.actions.length) {
            <label class="startup-search"><i class="bi bi-search" aria-hidden="true"></i><input type="search" aria-label="Find a startup action" placeholder="Find an action" maxlength="256" [value]="query()" (input)="search($event)"/></label>
            <div class="startup-filters" role="group" aria-label="Filter startup actions">
              @for(option of filters; track option.key) {
                <button type="button" [class.selected]="filter() === option.key" [attr.aria-pressed]="filter() === option.key" (click)="select(option.key)">{{option.label}}<span>{{count(option.key)}}</span></button>
              }
            </div>
            <div id="startup-actions">
              @for(action of visibleActions(); track action.id) {
                <div class="startup-row">
                  <i class="bi" [class.bi-check-circle]="action.state === 'succeeded'" [class.bi-x-circle]="action.state === 'failed'" [class.bi-clock]="action.state !== 'succeeded' && action.state !== 'failed'" [class.success]="action.state === 'succeeded'" [class.failure]="action.state === 'failed'" aria-hidden="true"></i>
                  <span class="action-name">{{action.name}}</span><span class="action-state" [class.failure]="action.state === 'failed'">{{label(action.state)}}</span>
                </div>
              } @empty {<p class="startup-empty">{{query() ? 'No matching actions in this filter.' : 'No ' + filter() + ' actions.'}}</p>}
            </div>
            @if(matchingActions().length > 50) {
              <div class="startup-pagination"><span role="status">{{visibleActions().length}} of {{matchingActions().length}} actions shown</span>
                @if(visibleActions().length < matchingActions().length) {<button type="button" class="dashboard-action" (click)="limit.set(limit() + 50)">Show more</button>}
              </div>
            }
          } @else {<p class="failure" role="status">Startup actions could not be loaded.</p>}
        </div>
      </section>
    }
  }`, styles:`
    :host {display:block;}
    section {margin-top:24px;} h2 {font-size:17px;margin:0 0 16px;}
    .startup-card {padding:22px 24px 10px;background:var(--dashboard-surface);border:1px solid var(--dashboard-border);border-radius:16px;}
    .startup-search {height:34px;width:max-content;max-width:100%;box-sizing:border-box;display:flex;align-items:center;gap:8px;color:var(--dashboard-muted);border:1px solid var(--dashboard-border);border-radius:8px;padding:8px 10px;margin-bottom:16px;}
    input {background:transparent;border:0;color:var(--dashboard-text);font:inherit;font-size:13px;min-width:0;width:180px;}
    .startup-search:focus-within {outline:2px solid var(--dashboard-active);} input:focus {outline:0;}
    .startup-filters {display:flex;gap:18px;border-bottom:1px solid var(--dashboard-border);overflow-x:auto;}
    .startup-filters button {border:0;border-radius:0;border-bottom:2px solid transparent;padding:11px 3px 13px;background:transparent;color:var(--dashboard-muted);font-size:13px;white-space:nowrap;}
    .startup-filters button.selected {border-bottom-color:var(--dashboard-active);color:var(--dashboard-active);}
    .startup-filters button:hover {background:var(--dashboard-action-bg);color:var(--dashboard-active);}
    .startup-filters span {display:inline-block;margin-left:6px;padding:1px 5px;border-radius:5px;font-size:11px;background:var(--dashboard-action-bg);font-variant-numeric:tabular-nums;}
    .startup-filters .selected span {background:var(--dashboard-active-soft);color:var(--dashboard-active);}
    .startup-row {display:flex;align-items:center;gap:12px;padding:14px 0;font-size:14px;color:var(--dashboard-text);}
    .startup-row + .startup-row {border-top:1px solid var(--dashboard-border);}
    .action-name {flex:1;min-width:0;overflow-wrap:anywhere;}
    .action-state {font-size:12px;color:var(--dashboard-muted);}
    .success {color:var(--dashboard-success-text);}.failure {color:var(--dashboard-danger-text);}
    p {font-size:13px;}.startup-empty {padding:16px 0;text-align:center;color:var(--dashboard-muted);}
    .startup-pagination {display:flex;justify-content:space-between;align-items:center;gap:12px;margin:12px 0;font-size:12px;color:var(--dashboard-muted);}
    @media(max-width:650px) {
      .startup-card {padding:18px 18px 6px;}.startup-row {gap:8px;}.startup-search {width:100%;}.startup-search input {width:100%;}
      .startup-filters {gap:8px;}.startup-filters button {padding:10px 0;font-size:12px;}.startup-filters span {margin-left:3px;padding:1px 3px;font-size:10px;}
    }
  `})
export class StartupComponent {
  readonly startup = input<Status['startup']>();
  readonly query = signal('');
  readonly filter = signal('all');
  readonly limit = signal(50);
  readonly filters = [{key:'all',label:'All'},{key:'completed',label:'Completed'},{key:'failed',label:'Failed'},{key:'pending',label:'Pending'}];
  readonly matchingActions = computed(() => (this.startup()?.actions || []).filter(action =>
    (this.filter() === 'all' || this.category(action.state) === this.filter()) && action.name.toLocaleLowerCase().includes(this.query().trim().toLocaleLowerCase())));
  readonly visibleActions = computed(() => this.matchingActions().slice(0,this.limit()));
  select(filter:string) {this.filter.set(filter);this.limit.set(50);}
  search(event:Event) {this.query.set((event.target as HTMLInputElement).value);this.limit.set(50);}
  count(filter:string) {return (this.startup()?.actions || []).filter(action => filter === 'all' || this.category(action.state) === filter).length;}
  private category(state:string) {return state === 'succeeded' ? 'completed' : state === 'failed' ? 'failed' : 'pending';}
  label(state:string) {return ({succeeded:'Completed', failed:'Failed', blocked:'Blocked', running:'Running'} as Record<string,string>)[state] || 'Pending';}
}
