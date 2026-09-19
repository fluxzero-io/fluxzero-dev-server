import {Component, input, output, signal, inject, forwardRef, OnInit, OnChanges, OnDestroy, NgZone} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {forkJoin, Subscription} from 'rxjs';

export interface TestCase {key:string;project:string;suite:string;name:string;state:string;source:string;variants?:Record<string,number>}
export interface TestPage {items:TestCase[];total:number;offset:number;pageSize:number;counts:Record<string,number>}

@Component({selector:'dev-scenario-list',standalone:true,imports:[forwardRef(() => ScenarioListComponent)],template:`
  @if(error()) {<p role="alert">{{error()}} <button class="icon-button" type="button" (click)="refresh()">Retry</button></p>}
  <div class="scenario-results" [attr.aria-busy]="loading()">
    @if(page(); as data) {
      @for(test of data.items; track test.key) {
        <div class="scenario-entry">
          @if(test.variants; as variants) {
            <button type="button" class="scenario-row scenario-group" [attr.aria-expanded]="expanded().has(test.key)"
              [attr.aria-controls]="'variants-' + test.key" (click)="toggle(test.key)">
              <i class="bi scenario-status" [class.bi-check-circle]="test.state === 'passed'" [class.bi-x-circle]="test.state === 'failed'" [class.bi-dash-circle]="test.state === 'skipped'" [class.bi-clock]="test.state === 'pending'" [class.passed]="test.state === 'passed'" [class.failed]="test.state === 'failed'" role="img" [attr.aria-label]="label(test.state)"></i>
              <i class="bi scenario-chevron" [class.bi-chevron-right]="!expanded().has(test.key)" [class.bi-chevron-down]="expanded().has(test.key)" aria-hidden="true"></i>
              <span class="scenario-name"><strong>{{test.name}}</strong><small>{{test.suite}} · {{test.project}}</small></span>
              <span class="scenario-group-summary">{{variants['passed'] || 0}} / {{total(variants)}} passed</span>
            </button>
            <div [id]="'variants-' + test.key" [hidden]="!expanded().has(test.key)" class="scenario-variants">
              @if(expanded().has(test.key)) {<dev-scenario-list [group]="test.key" [filter]="filter()" [query]="query()"/>}
            </div>
          } @else {
            <div class="scenario-row">
              <i class="bi scenario-status" [class.bi-check-circle]="test.state === 'passed'" [class.bi-x-circle]="test.state === 'failed'" [class.bi-dash-circle]="test.state === 'skipped'" [class.bi-clock]="test.state === 'pending'" [class.passed]="test.state === 'passed'" [class.failed]="test.state === 'failed'" aria-hidden="true"></i>
              <div class="scenario-name"><strong>{{test.name}}</strong>@if(!group()) {<small [title]="test.source">{{test.suite}} · {{test.project}}</small>}</div>
              <span class="scenario-state">{{label(test.state)}}</span>
            </div>
          }
        </div>
      } @empty {
        <div class="scenario-empty">
          @if(query()) {<p>No matching scenarios in this filter.</p>}
          @else if(total(data.counts) === 0) {<p>Test names will appear when the test runner reports them.</p>}
          @else {<p>No {{filter()}} tests.</p>}
        </div>
      }
      @if(data.total > 0 && (!group() || data.items.length < data.total)) {
        <div class="scenario-pagination"><span role="status" aria-live="polite">{{data.items.length}} of {{data.total}} {{group() ? (data.total === 1 ? 'scenario' : 'scenarios') : (data.total === 1 ? 'entry' : 'entries')}} shown</span>
          @if(data.items.length < data.total) {<button type="button" class="icon-button dashboard-action" [disabled]="loading()" (click)="loadMore()">Show more</button>}
        </div>
      }
    } @else if(loading()) {<p class="scenario-empty" role="status">Loading scenarios…</p>}
  </div>`,styles:`
  :host {display:block;min-width:0;}
  .scenario-entry + .scenario-entry {border-top:1px solid var(--dashboard-border);}
  .scenario-row {display:flex;align-items:center;gap:12px;padding:14px 0;width:100%;}
  .scenario-group {border:0;background:transparent;color:var(--dashboard-text);text-align:left;font:inherit;cursor:pointer;}
  .scenario-group:hover {background:var(--dashboard-action-bg);}
  .scenario-chevron {font-size:12px;color:var(--dashboard-muted);}
  .scenario-status {flex:0 0 auto;color:var(--dashboard-muted);}.passed {color:var(--dashboard-success-text);}.failed {color:var(--dashboard-danger-text);font-weight:600;}
  .scenario-name {flex:1;min-width:0;overflow-wrap:anywhere;}.scenario-name strong {display:block;font-size:14px;font-weight:500;}
  .scenario-name small {display:block;color:var(--dashboard-muted);font-size:11px;margin-top:4px;}
  .scenario-state,.scenario-group-summary {font-size:12px;color:var(--dashboard-muted);white-space:nowrap;}
  .scenario-group-summary {text-align:right;}
  .scenario-variants {padding:0 0 12px 52px;}
  .scenario-empty {padding:24px 0;text-align:center;color:var(--dashboard-muted);font-size:13px;}
  .scenario-empty p {margin:8px 0;}
  .icon-button {width:auto;height:auto;min-height:30px;padding:6px 10px;gap:6px;white-space:nowrap;}
  .scenario-pagination {display:flex;justify-content:space-between;align-items:center;gap:12px;margin-top:12px;font-size:12px;color:var(--dashboard-muted);}
  @media(max-width:650px) {
    .scenario-row {flex-wrap:wrap;gap:8px;}.scenario-name {flex-basis:calc(100% - 32px);}.scenario-state,.scenario-group-summary {padding-left:24px;}
    .scenario-group .scenario-name {flex-basis:calc(100% - 56px);}
    .scenario-group-summary {display:flex;gap:10px;text-align:left;flex-wrap:wrap;white-space:normal;}
    .scenario-variants {padding-left:14px;}
  }
`})
export class ScenarioListComponent implements OnInit, OnChanges, OnDestroy {
  readonly filter = input('all');
  readonly query = input('');
  readonly group = input('');
  readonly changed = output<TestPage>();
  readonly page = signal<TestPage | undefined>(undefined);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly expanded = signal(new Set<string>());
  private readonly http = inject(HttpClient);
  private readonly zone = inject(NgZone);
  private request?:Subscription;
  private timer?:ReturnType<typeof setInterval>;
  private visiblePages = 1;
  private initialized = false;
  ngOnInit() {
    this.initialized = true;this.refresh();
    this.zone.runOutsideAngular(() => {this.timer = setInterval(() => {
      if(!document.hidden && !this.loading()) this.zone.run(() => this.refresh());
    },3000);});
  }
  ngOnChanges() {
    if(this.initialized) {this.visiblePages=1;this.page.set(undefined);this.expanded.set(new Set());this.refresh();}
  }
  ngOnDestroy() {this.request?.unsubscribe();if(this.timer) clearInterval(this.timer);}
  toggle(key:string) {this.expanded.update(current => {const next=new Set(current);if(next.has(key)) next.delete(key);else next.add(key);return next;});}
  loadMore() {
    const data=this.page();
    if(this.loading() || !data || data.items.length >= data.total) return;
    this.visiblePages++;this.refresh();
  }
  refresh() {
    this.request?.unsubscribe();this.loading.set(true);this.error.set('');
    const pageSize=this.page()?.pageSize || 50;
    this.request=forkJoin(Array.from({length:this.visiblePages},(_,index)=>this.http.get<TestPage>('tests.json', {
      params:{state:this.filter(),q:this.query(),offset:index*pageSize,grouped:'true',...(this.group() ? {group:this.group()} : {})},timeout:10000
    }))).subscribe({
      next:pages=>{
        const first=pages[0];
        // Refresh the complete visible prefix, deduplicating offsets clamped after a catalog shrinks.
        const items=[...new Map(pages.flatMap(page=>page.items).map(test=>[test.key,test])).values()].slice(0,first.total);
        const data={...first,items};this.page.set(data);this.visiblePages=Math.max(1,Math.ceil(items.length/first.pageSize));
        this.loading.set(false);this.changed.emit(data);
      },
      error:()=>{this.error.set('Unable to refresh scenarios. Results may be out of date.');this.loading.set(false);}
    });
  }
  total(counts:Record<string,number>) {return Object.values(counts).reduce((a,b)=>a+b,0);}
  label(state:string) {return ({passed:'Passed',failed:'Failed',skipped:'Skipped',pending:'Not completed'} as Record<string,string>)[state] || state;}
}
