import {Component, inject, signal, OnInit, OnDestroy, NgZone} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Subscription} from 'rxjs';

export interface TestCase {key:string;project:string;suite:string;name:string;state:string;source:string}
export interface TestPage {items:TestCase[];total:number;offset:number;pageSize:number;counts:Record<string,number>}
@Component({selector:'dev-test-catalog', standalone:true, template:`
  <section class="scenario-panel" aria-labelledby="scenarios-title">
    <header><div><h2 id="scenarios-title">Scenarios</h2><p>Latest result for each discovered test.</p></div>
      <label class="scenario-search"><i class="bi bi-search" aria-hidden="true"></i><input type="search" placeholder="Find a scenario" aria-label="Find a scenario" maxlength="256" [value]="query()" (input)="search($event)"/></label>
    </header>
    <div class="scenario-filters" role="group" aria-label="Filter test results">
      @for(option of filters; track option.key) {
        <button type="button" [class.selected]="filter() === option.key" [attr.aria-pressed]="filter() === option.key" (click)="select(option.key)">
          {{option.label}}@if(page(); as data) {<span>{{count(option.key, data)}}</span>}
        </button>
      }
    </div>
    @if(error()) {<p role="alert">{{error()}} <button class="icon-button" type="button" (click)="refresh()">Retry</button></p>}
    <div class="scenario-results" [attr.aria-busy]="loading()">
      @if(page(); as data) {
        @for(test of data.items; track test.key) {
          <div class="scenario-row">
            <i class="bi scenario-status" [class.bi-check-circle]="test.state === 'passed'" [class.bi-x-circle]="test.state === 'failed'" [class.bi-dash-circle]="test.state === 'skipped'" [class.bi-clock]="test.state === 'pending'" [class.passed]="test.state === 'passed'" [class.failed]="test.state === 'failed'" aria-hidden="true"></i>
            <div class="scenario-name"><strong>{{test.name}}</strong><small [title]="test.source">{{test.suite}} · {{test.project}}</small></div>
            <span class="scenario-state">{{label(test.state)}}</span>
          </div>
        } @empty {
          <div class="scenario-empty">
            @if(query()) {<p>No matching scenarios in this filter.</p>}
            @else if(count('all', data) === 0) {<p>Test names will appear when the test runner reports them.</p>}
            @else if(filter() === 'attention') {
              <i class="bi bi-check2-circle" aria-hidden="true"></i><strong>No tests need attention</strong>
              <p>@if(data.counts['passed']) {{{data.counts['passed']}} tests passed. }@if(data.counts['skipped']) {{{data.counts['skipped']}} skipped. }</p>
              @if(data.counts['passed']) {<button type="button" class="icon-button" (click)="select('passed')">Show passed tests <i class="bi bi-chevron-right" aria-hidden="true"></i></button>}
            } @else {<p>No {{filter()}} tests.</p>}
          </div>
        }
        @if(data.total > data.pageSize) {
          <div class="scenario-pagination"><button type="button" class="icon-button" [disabled]="loading() || !data.offset" (click)="go(data.offset - data.pageSize)">Previous</button>
            <span>{{data.offset + 1}}–{{data.offset + data.items.length}} of {{data.total}}</span>
            <button type="button" class="icon-button" [disabled]="loading() || data.offset + data.items.length >= data.total" (click)="go(data.offset + data.pageSize)">Next</button></div>
        }
      } @else if(loading()) {<p class="scenario-empty" role="status">Loading scenarios…</p>}
    </div>
  </section>`, styles:`
    .scenario-panel {border:1px solid var(--dashboard-border);border-radius:18px;background:var(--dashboard-surface);padding:24px;}
    header {display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:20px;}
    h2 {font-size:17px;margin:0;} header p {font-size:12px;color:var(--dashboard-muted);margin:5px 0 0;}
    .scenario-search {display:flex;align-items:center;gap:8px;color:var(--dashboard-muted);border:1px solid var(--dashboard-border);border-radius:8px;padding:8px 10px;}
    input {background:transparent;border:0;color:var(--dashboard-text);font:inherit;font-size:13px;min-width:0;width:180px;}
    .scenario-search:focus-within {outline:2px solid var(--dashboard-active);} input:focus {outline:0;}
    .scenario-filters {display:flex;flex-wrap:wrap;gap:6px;margin-bottom:14px;}
    .scenario-filters button {border:0;border-radius:6px;padding:7px 10px;background:transparent;color:var(--dashboard-muted);font-size:12px;}
    .scenario-filters button.selected {background:var(--dashboard-active-soft);color:var(--dashboard-active);}
    .scenario-filters span {margin-left:6px;font-variant-numeric:tabular-nums;}
    .scenario-row {display:flex;align-items:center;gap:12px;padding:14px 0;border-top:1px solid var(--dashboard-border);}
    .scenario-status {flex:0 0 auto;color:var(--dashboard-muted);}.scenario-status.passed {color:var(--dashboard-success-text);}.scenario-status.failed {color:var(--dashboard-danger-text);}
    .scenario-name {flex:1;min-width:0;overflow-wrap:anywhere;}.scenario-name strong {display:block;font-size:14px;font-weight:500;}
    .scenario-name small {display:block;color:var(--dashboard-muted);font-size:11px;margin-top:4px;}
    .scenario-state {font-size:12px;color:var(--dashboard-muted);white-space:nowrap;}
    .scenario-empty {padding:24px 0;text-align:center;color:var(--dashboard-muted);font-size:13px;}
    .scenario-empty>i {display:block;font-size:26px;color:var(--dashboard-success-text);margin-bottom:10px;}
    .scenario-empty strong {display:block;color:var(--dashboard-text);font-size:15px;font-weight:500;}.scenario-empty p {margin:8px 0;}
    .icon-button {width:auto;height:auto;min-height:30px;padding:6px 10px;gap:6px;white-space:nowrap;}
    .scenario-pagination {display:flex;justify-content:space-between;align-items:center;gap:12px;margin-top:12px;font-size:12px;color:var(--dashboard-muted);}
    @media(max-width:650px) {.scenario-panel {padding:18px;}header {flex-direction:column;align-items:stretch;}.scenario-search input {width:100%;}.scenario-row {flex-wrap:wrap;gap:8px;}.scenario-name {flex-basis:calc(100% - 32px);}.scenario-state {padding-left:24px;}.scenario-filters button {padding:7px 8px;}}
  `})
export class TestCatalogComponent implements OnInit, OnDestroy {
  readonly page = signal<TestPage | undefined>(undefined);
  readonly filter = signal('attention');
  readonly query = signal('');
  readonly loading = signal(false);
  readonly error = signal('');
  readonly filters = [{key:'attention',label:'Needs attention'},{key:'passed',label:'Passed'},{key:'skipped',label:'Skipped'},{key:'all',label:'All'}];
  private readonly http = inject(HttpClient);
  private readonly zone = inject(NgZone);
  private request?: Subscription;
  private timer?: ReturnType<typeof setInterval>;
  private offset = 0;
  ngOnInit() {
    this.refresh();
    this.zone.runOutsideAngular(() => {this.timer = setInterval(() => {
      if (!document.hidden && !this.loading()) this.zone.run(() => this.refresh());
    }, 3000);});
  }
  ngOnDestroy() {this.request?.unsubscribe();if(this.timer) clearInterval(this.timer);}
  select(filter:string) {this.filter.set(filter);this.offset=0;this.page.set(undefined);this.refresh();}
  search(event:Event) {this.query.set((event.target as HTMLInputElement).value);this.offset=0;this.page.set(undefined);this.refresh();}
  go(offset:number) {this.offset=offset;this.refresh();}
  refresh() {
    this.request?.unsubscribe();this.loading.set(true);this.error.set('');
    this.request = this.http.get<TestPage>('tests.json', {params:{state:this.filter(),q:this.query(),offset:this.offset},timeout:10000}).subscribe({
      next:data => {this.page.set(data);this.offset=data.offset;this.loading.set(false);},
      error:() => {this.error.set('Unable to refresh scenarios. Results may be out of date.');this.loading.set(false);}
    });
  }
  count(filter:string, page:TestPage) {return filter === 'all' ? Object.values(page.counts).reduce((a,b)=>a+b,0) : filter === 'attention' ? (page.counts['failed']||0)+(page.counts['pending']||0) : page.counts[filter]||0;}
  label(state:string) {return ({passed:'Passed',failed:'Failed',skipped:'Skipped',pending:'Not completed'} as Record<string,string>)[state] || state;}
}
