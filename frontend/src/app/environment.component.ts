import {Component, ElementRef, inject, input, signal, ViewChild, ChangeDetectorRef, computed, DestroyRef, effect} from '@angular/core';
import {NgTemplateOutlet} from '@angular/common';
import {ProjectPathComponent} from './project-path.component';
import {EnvironmentNameComponent} from './environment-name.component';
import {Status} from './models';
import {ResourceDetailComponent} from './resource-detail.component';
import {ResourceChartComponent} from './resource-chart.component';
import {TestOutputComponent} from './test-output.component';
import {totalMemory, usedMemory} from './resource-history';
import {formatBytes} from './format-bytes';
import {Handler, HandleQuery, sendCommand} from './dom-handlers';

@Component({selector: 'dev-environment', standalone: true, imports: [NgTemplateOutlet, EnvironmentNameComponent, ProjectPathComponent, ResourceDetailComponent, ResourceChartComponent, TestOutputComponent], template: `
  @if(status(); as state) {<section [class.page]="!embedded()" [class.current-project]="embedded()" aria-label="Current project"><div class="page-heading"><div>
    <div class="environment-eyebrow"><i class="bi bi-terminal" aria-hidden="true"></i> YOUR WORKSPACE</div>
    <div class="project-title-row">
      @if(embedded()) {<h3 class="current-project-title"><a href="#projects" (click)="openProjects($event)">{{displayName() || state.project}}</a></h3>}
      @else {<h1>{{displayName() || state.project}}</h1>}
      @if(projectId()) {<dev-environment-name [id]="projectId()" [name]="displayName() || state.project" [directory]="state.projectDirectory"/>}
    </div>
    <div class="project-path"><dev-project-path [path]="state.projectDirectory" [id]="projectId()" [exists]="directoryExists()"/></div>
    </div></div>
    <ng-template #componentCard let-component>
    <div class="component-table-scroll"><table role="table" class="component-table" [attr.aria-label]="component.application ? 'Application resources' : 'Infrastructure resources'">
      <thead role="rowgroup"><tr role="row"><th role="columnheader" scope="col">Component</th><th role="columnheader" scope="col">Status</th><th role="columnheader" scope="col">Memory</th>@if(!component.application) {<th role="columnheader" scope="col">Monitoring storage</th>}@if(!component.application) {<th role="columnheader" scope="col" aria-label="Restart"></th><th role="columnheader" scope="col" aria-label="Actions"></th>}</tr></thead>
      <tbody role="rowgroup">
        <tr role="row" [class.application-component]="component.application">
          <th role="rowheader" scope="row" class="component-name"><i class="bi" [class.bi-window]="component.application" [class.bi-hdd-stack]="!component.application" aria-hidden="true"></i>{{component.name}}
          </th>
          <td role="cell" class="component-status">
            @if(component.application) {<span class="badge" [class.running]="component.state === 'running'" [class.starting]="component.state === 'starting'" [class.degraded]="component.state === 'degraded'" [class.failed]="component.state === 'failed'">{{component.state}}</span>}
            @else {<dev-resource-detail [components]="component.components" kind="status"><span class="badge" [class.running]="component.state === 'running'" [class.starting]="component.state === 'starting'" [class.degraded]="component.state === 'degraded'" [class.failed]="component.state === 'failed'">{{component.state}}</span></dev-resource-detail>}
          </td>
          <td role="cell" class="component-memory" [attr.data-label]="component.application ? 'Memory' : 'Total memory'">
            @if(component.application) {<dev-resource-chart [samples]="state.resourceHistory || []" metric="applicationMemory" [componentId]="component.id"/><span class="resource-value">{{formatBytes(component.memoryBytes)}} / {{formatBytes(component.memoryMaxBytes)}}</span>}
            @else {<dev-resource-detail [components]="component.components" [samples]="state.resourceHistory || []" kind="memory"><dev-resource-chart [samples]="state.resourceHistory || []" metric="devserverMemory"/><span class="resource-value">{{formatBytes(component.memoryBytes)}} / {{formatBytes(component.memoryMaxBytes)}}</span></dev-resource-detail>}
          </td>
          @if(!component.application) {<td role="cell" class="component-storage" data-label="Monitoring storage">
            @if(component.id === 'devserver' && state.monitoring.resources) {
              <dev-resource-chart [samples]="state.resourceHistory || []" metric="monitoringStorage"/>
              <span class="resource-value">{{formatBytes(state.monitoring.resources?.storageDiskBytes)}} / {{formatBytes(state.monitoring.resources?.diskRetentionThresholdBytes)}}</span>

            } @else {—}
          </td>}
          @if(!component.application) {<td role="cell" class="component-restart">
            <button class="icon-button" type="button" aria-label="Restart dev server" title="Restart" [attr.aria-busy]="maintenanceAction() === 'restart-devserver'" [disabled]="busy() || !state.maintenance?.restartSupported" (click)="maintain('restart-devserver')">
              @if(maintenanceAction() === 'restart-devserver') {<span class="spinner-border spinner-border-sm" role="status" aria-label="Restarting dev server"></span>}
              @else {<i class="bi bi-arrow-clockwise" aria-hidden="true"></i>}<span>Restart</span>
            </button>
          </td>
          <td role="cell" class="component-actions">
            <button class="icon-button" type="button" aria-label="Truncate data" title="truncate data" [attr.aria-busy]="maintenanceAction() === 'truncate-data'" [disabled]="busy() || !state.maintenance?.resetSupported" (click)="requestMaintenance('truncate-data')">
              @if(maintenanceAction() === 'truncate-data') {<span class="spinner-border spinner-border-sm" role="status" aria-label="Truncating data"></span>}
              @else {<i class="bi bi-trash" aria-hidden="true"></i>}
            </button>
          </td>}
        </tr>
      </tbody>
    </table></div>
    </ng-template>
    <dialog #confirmation class="maintenance-confirm" aria-labelledby="maintenance-title" aria-describedby="maintenance-description" (cancel)="cancelConfirmation()">
    @if(confirmAction(); as action) {
      <h2 id="maintenance-title">Truncate data?</h2>
      <p id="maintenance-description">Delete application and monitoring data and restore the initial application data?</p>
      <div class="dialog-actions"><button type="button" class="primary-button" [disabled]="busy()" (click)="confirmMaintenance(action)">Truncate data</button>
      <button type="button" class="secondary-button" (click)="cancelConfirmation()" autofocus>Cancel</button></div>
    }</dialog>
    @if(actionError() || state.maintenance?.error) {<p role="alert">{{actionError() || state.maintenance?.error}}</p>}
    <section class="applications-section" aria-labelledby="applications-title">
      <header class="applications-heading"><h2 id="applications-title">Applications</h2>
        <div class="application-actions">
          <button class="icon-button" type="button" aria-label="Restart all applications" [attr.aria-busy]="maintenanceAction() === 'restart-application'" [disabled]="busy() || !state.maintenance?.applicationRestartSupported" (click)="maintain('restart-application')">
            @if(maintenanceAction() === 'restart-application') {<span class="spinner-border spinner-border-sm" role="status" aria-label="Restarting applications"></span>}
            @else {<i class="bi bi-arrow-clockwise" aria-hidden="true"></i>}<span>Restart all</span>
          </button>
          @if(applicationUrl()) {<a class="icon-button application-link" href="#application" (click)="openApplication($event)" aria-label="Open app preview"><span>App preview</span><i class="bi bi-arrow-up-right" aria-hidden="true"></i></a>}
        </div>
      </header>
      <div class="application-overview">
        @for(application of applications(); track application.id) {
          <ng-container [ngTemplateOutlet]="componentCard" [ngTemplateOutletContext]="{$implicit: application}"/>
        } @empty {<p class="applications-empty">No applications configured for this environment.</p>}
      </div>
    </section>
    <section class="environment-tests" aria-label="Tests"><div class="test-summary">
      <div class="test-heading"><i class="bi bi-check2-circle" aria-hidden="true"></i><div><h3>Tests</h3><p>Test results for this workspace.</p></div></div>
      <div class="test-controls">
        <button class="icon-button" type="button" aria-label="Run tests" title="Run tests"
          [disabled]="startingTests() || state.testResults?.running || !state.testResults?.runnable || busy()" (click)="runTests()"><i class="bi bi-rocket-takeoff" aria-hidden="true"></i></button>
        <div class="test-bar" [class.empty-tests]="!state.testResults?.available" [class.running-tests]="state.testResults?.available && state.testResults.running" role="img"
          [attr.aria-label]="state.testResults?.available ? state.testResults.passed + ' passed, ' + state.testResults.failed + ' failed, ' + testTotal(state.testResults) + ' total' + (state.testResults.skipped ? ', ' + state.testResults.skipped + ' skipped' : '') : 'No test results available'">
          @if(!state.testResults?.available) {<span class="test-empty-label">No test results yet</span>}
          @if(state.testResults; as results) {
            @if(results.available) {
              <span class="test-passed" [style.width.%]="percentage(results.passed, results.totalKnown === false ? 0 : results.total)"></span><span class="test-failed" [style.width.%]="percentage(results.failed, results.totalKnown === false ? 0 : results.total)"></span>
              <div class="test-counts" aria-hidden="true">
                <span class="passed-count"><strong>{{results.passed}}</strong> passed</span><span>/</span>
                <span class="failed-count"><strong>{{results.failed}}</strong> failed</span><span>/</span>
                <span [title]="results.skipped ? results.skipped + ' skipped' : ''"><strong>{{testTotal(results)}}</strong> total</span>
              </div>
            }
          }
        </div>
      </div>
      @if(state.testResults; as results) {
        @if(results.incomplete && !results.running) {<small>Run interrupted or incomplete.</small>}
        @if(results.running) {<small>{{results.label}}@if(results.expectedTotal) { · {{results.live ? '' : '~'}}{{results.expectedTotal}} {{results.live ? 'discovered' : 'expected'}}} @else { · total not yet known}</small>}
      }
      @if(testError()) {<small role="alert">{{testError()}}</small>}
    </div>
    <dev-test-output [lines]="state.testOutput || []"/></section>
    <section class="infrastructure-section" aria-labelledby="infrastructure-title">
      <header><h2 id="infrastructure-title">Development infrastructure</h2>
        <p>Combined usage of the dev server, Fluxzero runtime, monitoring and supporting services.</p></header>
      <ng-container [ngTemplateOutlet]="componentCard" [ngTemplateOutletContext]="{$implicit: infrastructure()}"/>
    @if(state.monitoring.resources; as r) {
      @if(r.diskThresholdExceeded) {<p role="status">Disk retention threshold exceeded; recent partitions are retained.</p>}
      @if(r.error || r.auditlog?.storage?.retentionError) {<p role="alert">{{r.error || r.auditlog.storage.retentionError}}</p>}
    }
    @if(state.monitoring.droppedLogLines) {<p role="status">{{state.monitoring.droppedLogLines}} log lines dropped</p>}
    </section></section>}`})
@Handler()
export class EnvironmentComponent {
  readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  status = input<Status>();
  displayName = input('');
  readonly applications = computed(() => (this.status()?.components || []).filter(c => c.application).map(c => ({
    ...c, memoryBytes: usedMemory(c)
  })));
  readonly applicationUrl = computed(() => this.applications().find(c => c.url)?.url);
  readonly infrastructure = computed(() => {
    const components = (this.status()?.components || []).filter(c => !c.application);
    const running = components.reduce((sum, c) => sum + (c.runningProcesses ?? (c.state === 'running' ? 1 : 0)), 0);
    const total = components.reduce((sum, c) => sum + (c.totalProcesses ?? 1), 0);
    return {
      id: 'devserver', application: false, components, name: 'All services',
      state: running > 0 && running < total ? 'degraded' : components.some(c => c.state === 'failed') ? 'failed' : components.some(c => c.state === 'starting') ? 'starting' : total > 0 && running === total ? 'running' : 'stopped',
      memoryBytes: totalMemory(components), memoryMaxBytes: totalMemory(components, true)
    };
  });
  embedded = input(false);
  port = input<number | null>(null);
  projectId = input('');
  directoryExists = input(false);
  @HandleQuery('getCurrentEnvironment') currentEnvironment() { return this.status(); }
  @ViewChild('confirmation') confirmation?: ElementRef<HTMLDialogElement>;
  private readonly changeDetector = inject(ChangeDetectorRef);
  confirmAction = signal<string | null>(null);
  actionError = signal('');
  submitting = signal(false);
  readonly maintenanceAction = signal<string | null>(null);
  private maintenanceStatus?: Status;
  private maintenanceTimeout?: ReturnType<typeof setTimeout>;
  constructor() {
    effect(() => {
      // The command response only acknowledges the request. Wait for a subsequent
      // push (including a reconnect snapshot) to report that the operation is done.
      if (this.maintenanceAction() && !this.submitting()
          && this.status() !== this.maintenanceStatus && !this.status()?.maintenance?.busy) {
        this.finishMaintenance();
      }
    });
    inject(DestroyRef).onDestroy(() => this.finishMaintenance());
  }
  private finishMaintenance() {
    clearTimeout(this.maintenanceTimeout);
    this.maintenanceAction.set(null);
  }
  busy() { return !!this.maintenanceAction() || this.submitting() || !!this.status()?.maintenance?.busy; }
  requestMaintenance(action: string) {
    if (this.busy()) return;
    this.confirmAction.set(action);
    this.changeDetector.detectChanges();
    this.confirmation?.nativeElement.showModal();
  }
  cancelConfirmation() { this.confirmation?.nativeElement.close(); this.confirmAction.set(null); }
  confirmMaintenance(action: string) {
    if (this.busy() || this.confirmAction() !== action || !this.confirmation?.nativeElement.open) return;
    this.confirmation?.nativeElement.close();
    void this.maintain(action);
  }
  async maintain(action: string) {
    if (this.busy()) return;
    this.maintenanceAction.set(action);
    this.maintenanceTimeout = setTimeout(() => this.finishMaintenance(), 60_000);
    this.submitting.set(true); this.actionError.set(''); this.confirmAction.set(null);
    try { await sendCommand<Promise<void>>(this.elementRef.nativeElement, 'maintainEnvironment', action); }
    catch (error: any) {
      this.actionError.set(error?.error?.error || 'Maintenance could not be started.');
      this.finishMaintenance();
    }
    finally { this.maintenanceStatus = this.status(); this.submitting.set(false); }
  }
  testState() { const state = this.status(); return ['idle', 'stopped'].includes(state?.tests || '') && state?.testResults?.available ? state.testResults.state || state.tests : state?.tests; }
  readonly startingTests = signal(false);
  readonly testError = signal('');
  async runTests() {
    this.startingTests.set(true);this.testError.set('');
    try {await sendCommand<Promise<void>>(this.elementRef.nativeElement, 'runTests');}
    catch(error:any) {this.testError.set(error?.error?.error || 'Unable to start tests.');}
    finally {this.startingTests.set(false);}
  }
  testTotal(results: NonNullable<Status['testResults']>) {
    return results.totalKnown === false ? '?' : String(results.total);
  }
  percentage(value: number, total: number) { return total > 0 ? value * 100 / total : 0; }
  openApplication(event: MouseEvent) {
    if (event.button || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    sendCommand(event.currentTarget as Element, 'navigate', 'application');
  }
  openProjects(event: MouseEvent) {
    if (event.button || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    sendCommand(this.elementRef.nativeElement, 'navigate', 'projects');
  }
  readonly formatBytes = formatBytes;
}
