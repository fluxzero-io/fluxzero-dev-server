import {Component, inject, signal, OnInit, OnDestroy, NgZone} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Subscription} from 'rxjs';

export interface TestCase {key:string;project:string;suite:string;name:string;state:string;source:string}
export interface TestPage {items:TestCase[];total:number;offset:number;pageSize:number;counts:Record<string,number>}
@Component({selector:'dev-test-catalog', standalone:true, template:`
  <section class="scenario-panel" aria-labelledby="scenarios-title">
    <header [class.showing-output]="filter() === 'output'"><div class="scenario-heading"><h2 id="scenarios-title">Scenarios</h2>@if(filter() !== 'output') {<label class="scenario-search"><i class="bi bi-search" aria-hidden="true"></i><input type="search" placeholder="Find a scenario" aria-label="Find a scenario" maxlength="256" [value]="query()" (input)="search($event)"/></label>}</div>
      <ng-content select="[test-actions]"/>
    </header>
    <ng-content select="[test-status]"/>
    <div class="scenario-toolbar">
    <div class="scenario-filters" role="group" aria-label="Filter test results">
      @for(option of filters; track option.key) {
        <button type="button" [class.output-tab]="option.key === 'output'" [class.selected]="filter() === option.key" [attr.aria-pressed]="filter() === option.key" (click)="select(option.key)">
          {{option.label}}@if(option.key !== 'output' && page(); as data) {<span>{{count(option.key, data)}}</span>}
        </button>
      }
    </div>
    </div>
    <div class="output-panel" [hidden]="filter() !== 'output'"><ng-content select="[test-output]"/></div>
    @if(filter() !== 'output') {
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
            @else {<p>No {{filter()}} tests.</p>}
          </div>
        }
        @if(data.total > data.pageSize) {
          <div class="scenario-pagination"><button type="button" class="icon-button" [disabled]="loading() || !data.offset" (click)="go(data.offset - data.pageSize)">Previous</button>
            <span>{{data.offset + 1}}–{{data.offset + data.items.length}} of {{data.total}}</span>
            <button type="button" class="icon-button" [disabled]="loading() || data.offset + data.items.length >= data.total" (click)="go(data.offset + data.pageSize)">Next</button></div>
        }
      } @else if(loading()) {<p class="scenario-empty" role="status">Loading scenarios…</p>}
    </div>
    }
  </section>`, styles:`
    :host {display:block;}
    header {display:flex;justify-content:space-between;align-items:center;gap:24px;margin-bottom:20px;}
    .scenario-heading {display:flex;align-items:center;gap:24px;flex:1;min-width:0;}
    h2 {font-size:17px;margin:0;} header p {font-size:12px;color:var(--dashboard-muted);margin:5px 0 0;}
    .output-panel {padding-top:16px;}
    .scenario-toolbar {display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:0;}
    .scenario-search {height:34px;min-width:0;display:flex;align-items:center;gap:8px;color:var(--dashboard-muted);border:1px solid var(--dashboard-border);border-radius:8px;padding:8px 10px;}
    input {background:transparent;border:0;color:var(--dashboard-text);font:inherit;font-size:13px;min-width:0;width:180px;}
    .scenario-search:focus-within {outline:2px solid var(--dashboard-active);} input:focus {outline:0;}
    .scenario-filters {display:flex;width:100%;gap:18px;border-bottom:1px solid var(--dashboard-border);overflow-x:auto;}
    .scenario-filters button {border:0;border-radius:0;border-bottom:2px solid transparent;padding:11px 3px 13px;background:transparent;color:var(--dashboard-muted);font-size:13px;white-space:nowrap;}
    .scenario-filters button.selected {border-bottom-color:var(--dashboard-active);color:var(--dashboard-active);}
    .scenario-filters button:hover {background:var(--dashboard-action-bg);color:var(--dashboard-active);}
    .scenario-filters span {display:inline-block;margin-left:6px;padding:1px 5px;border-radius:5px;font-size:11px;background:var(--dashboard-action-bg);font-variant-numeric:tabular-nums;}
    .scenario-filters .selected span {background:var(--dashboard-active-soft);color:var(--dashboard-active);}
    .scenario-row:first-child {border-top:0;}
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
    @media(max-width:650px) {
      header {position:relative;min-height:138px;align-items:flex-start;gap:10px;}
      header.showing-output {min-height:86px;}
      .scenario-heading {display:contents;} h2 {padding-top:10px;}
      .scenario-search {position:absolute;bottom:0;left:0;right:0;width:100%;height:38px;box-sizing:border-box;}
      .scenario-search input {width:100%;}
      .scenario-row {flex-wrap:wrap;gap:8px;}.scenario-name {flex-basis:calc(100% - 32px);}.scenario-state {padding-left:24px;}
      .scenario-filters {gap:8px;}.scenario-filters button {padding:10px 0;font-size:12px;}
      .scenario-filters span {margin-left:3px;padding:1px 3px;font-size:10px;}
    }
  `})
export class TestCatalogComponent implements OnInit, OnDestroy {
  readonly page = signal<TestPage | undefined>(undefined);
  readonly filter = signal('all');
  readonly query = signal('');
  readonly loading = signal(false);
  readonly error = signal('');
  readonly filters = [{key:'all',label:'All'},{key:'passed',label:'Passed'},{key:'failed',label:'Failed'},{key:'skipped',label:'Skipped'},{key:'output',label:'Output'}];
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
  select(filter:string) {this.filter.set(filter);if(filter === 'output') {this.request?.unsubscribe();this.loading.set(false);return;}this.offset=0;this.page.set(undefined);this.refresh();}
  search(event:Event) {this.query.set((event.target as HTMLInputElement).value);this.offset=0;this.page.set(undefined);this.refresh();}
  go(offset:number) {this.offset=offset;this.refresh();}
  refresh() {
    if(this.filter() === 'output') return;
    this.request?.unsubscribe();this.loading.set(true);this.error.set('');
    this.request = this.http.get<TestPage>('tests.json', {params:{state:this.filter(),q:this.query(),offset:this.offset},timeout:10000}).subscribe({
      next:data => {this.page.set(data);this.offset=data.offset;this.loading.set(false);},
      error:() => {this.error.set('Unable to refresh scenarios. Results may be out of date.');this.loading.set(false);}
    });
  }
  count(filter:string, page:TestPage) {return filter === 'all' ? Object.values(page.counts).reduce((a,b)=>a+b,0) : page.counts[filter]||0;}
  label(state:string) {return ({passed:'Passed',failed:'Failed',skipped:'Skipped',pending:'Not completed'} as Record<string,string>)[state] || state;}
}
