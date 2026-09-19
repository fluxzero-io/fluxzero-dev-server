import {Component, signal, ViewChild} from '@angular/core';
import {ScenarioListComponent, TestPage} from './scenario-list.component';
export type {TestCase, TestPage} from './scenario-list.component';
@Component({selector:'dev-test-catalog', standalone:true, imports:[ScenarioListComponent], template:`
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
      <dev-scenario-list [filter]="filter()" [query]="query()" (changed)="page.set($event)"/>
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
    @media(max-width:650px) {
      header {position:relative;min-height:138px;align-items:flex-start;gap:10px;}
      header.showing-output {min-height:86px;}
      .scenario-heading {display:contents;} h2 {padding-top:10px;}
      .scenario-search {position:absolute;bottom:0;left:0;right:0;width:100%;height:38px;box-sizing:border-box;}
      .scenario-search input {width:100%;}
      .scenario-filters {gap:8px;}.scenario-filters button {padding:10px 0;font-size:12px;}
      .scenario-filters span {margin-left:3px;padding:1px 3px;font-size:10px;}
    }
  `})
export class TestCatalogComponent {
  readonly page = signal<TestPage | undefined>(undefined);
  readonly filter = signal('all');
  readonly query = signal('');
  readonly filters = [{key:'all',label:'All'},{key:'passed',label:'Passed'},{key:'failed',label:'Failed'},{key:'skipped',label:'Skipped'},{key:'output',label:'Output'}];
  @ViewChild(ScenarioListComponent) list?:ScenarioListComponent;
  select(filter:string) {this.filter.set(filter);}
  search(event:Event) {this.query.set((event.target as HTMLInputElement).value);}
  refresh() {if(this.filter() !== 'output') this.list?.refresh();}
  loadMore() {this.list?.loadMore();}
  count(filter:string, page:TestPage) {return filter === 'all' ? Object.values(page.counts).reduce((a,b)=>a+b,0) : page.counts[filter]||0;}
}
