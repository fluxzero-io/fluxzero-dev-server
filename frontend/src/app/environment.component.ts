import {Component, ElementRef, inject, input, signal, ViewChild, ChangeDetectorRef, computed, DestroyRef, effect} from '@angular/core';
import {ProjectPathComponent} from './project-path.component';
import {EnvironmentNameComponent} from './environment-name.component';
import {Status} from './models';
import {ResourceDetailComponent} from './resource-detail.component';
import {ResourceChartComponent} from './resource-chart.component';
import {TestOutputComponent} from './test-output.component';
import {totalMemory} from './resource-history';
import {formatBytes} from './format-bytes';
import {Handler, HandleQuery, sendCommand} from './dom-handlers';

@Component({selector: 'dev-environment', standalone: true, imports: [EnvironmentNameComponent, ProjectPathComponent, ResourceDetailComponent, ResourceChartComponent, TestOutputComponent], template: `
  @if(status(); as state) {<section [class.page]="!embedded()" [class.current-project]="embedded()" aria-label="Current project"><div class="page-heading"><div>
    <div class="project-title-row">
      @if(embedded()) {<h3 class="current-project-title"><a href="#projects" (click)="openProjects($event)">{{displayName() || state.project}}</a></h3>}
      @else {<h1>{{displayName() || state.project}}</h1>}
      @if(projectId()) {<dev-environment-name [id]="projectId()" [name]="displayName() || state.project" [directory]="state.projectDirectory"/>}
    </div>
    <div class="project-path"><dev-project-path [path]="state.projectDirectory" [id]="projectId()" [exists]="directoryExists()"/></div>
    </div></div>
    <div class="component-table-scroll"><table role="table" class="component-table" aria-label="Environment components">
      <thead role="rowgroup"><tr role="row"><th role="columnheader" scope="col">Component</th><th role="columnheader" scope="col">Status</th><th role="columnheader" scope="col">Memory</th><th role="columnheader" scope="col">Storage</th><th role="columnheader" scope="col" aria-label="Restart"></th><th role="columnheader" scope="col" aria-label="Actions"></th></tr></thead>
      <tbody role="rowgroup">@for(component of groups(); track component.id) {
        <tr role="row">
          <th role="rowheader" scope="row" class="component-name">{{component.name}}
          </th>
          <td role="cell">
            @if(component.application) {<span class="badge" [class.running]="component.state === 'running'" [class.starting]="component.state === 'starting'" [class.degraded]="component.state === 'degraded'" [class.failed]="component.state === 'failed'">{{component.state}}</span>}
            @else {<dev-resource-detail [components]="component.components" kind="status"><span class="badge" [class.running]="component.state === 'running'" [class.starting]="component.state === 'starting'" [class.degraded]="component.state === 'degraded'" [class.failed]="component.state === 'failed'">{{component.state}}</span></dev-resource-detail>}
          </td>
          <td role="cell" class="component-memory" data-label="Memory">
            @if(component.application) {<dev-resource-chart [samples]="state.resourceHistory || []" metric="applicationMemory"/><span class="resource-value">{{formatBytes(component.memoryBytes)}} / {{formatBytes(component.memoryMaxBytes)}}</span>}
            @else {<dev-resource-detail [components]="component.components" [samples]="state.resourceHistory || []" kind="memory"><dev-resource-chart [samples]="state.resourceHistory || []" metric="devserverMemory"/><span class="resource-value">{{formatBytes(component.memoryBytes)}} / {{formatBytes(component.memoryMaxBytes)}}</span></dev-resource-detail>}
          </td>
          <td role="cell" class="component-storage" data-label="Storage">
            @if(component.id === 'devserver' && state.monitoring.resources) {
              <dev-resource-chart [samples]="state.resourceHistory || []" metric="monitoringStorage"/>
              <span class="resource-value">{{formatBytes(state.monitoring.resources?.storageDiskBytes)}} / {{formatBytes(state.monitoring.resources?.diskRetentionThresholdBytes)}}</span>

            } @else {—}
          </td>
          <td role="cell" class="component-restart">
            @let restartAction = component.application ? 'restart-application' : 'restart-devserver';
            <button class="icon-button" type="button" [attr.aria-label]="component.application ? 'Restart application' : 'Restart dev server'" title="Restart" [attr.aria-busy]="maintenanceAction() === restartAction" [disabled]="busy() || !(component.application ? state.maintenance?.applicationRestartSupported : state.maintenance?.restartSupported)" (click)="maintain(restartAction)">
              @if(maintenanceAction() === restartAction) {<span class="spinner-border spinner-border-sm" role="status" [attr.aria-label]="component.application ? 'Restarting application' : 'Restarting dev server'"></span>}
              @else {<i class="bi bi-arrow-clockwise" aria-hidden="true"></i>}
            </button>
          </td>
          <td role="cell" class="component-actions">
            @if(component.application && component.url) {<a class="icon-button application-link" href="#application" (click)="openApplication($event)" [attr.aria-label]="'Open ' + component.name" title="Open application"><i class="bi bi-box-arrow-up-right" aria-hidden="true"></i></a>}
            @if(!component.application) {<button class="icon-button" type="button" aria-label="Truncate data" title="truncate data" [attr.aria-busy]="maintenanceAction() === 'truncate-data'" [disabled]="busy() || !state.maintenance?.resetSupported" (click)="requestMaintenance('truncate-data')">
              @if(maintenanceAction() === 'truncate-data') {<span class="spinner-border spinner-border-sm" role="status" aria-label="Truncating data"></span>}
              @else {<i class="bi bi-trash" aria-hidden="true"></i>}
            </button>}
          </td>
        </tr>
      }</tbody>
    </table></div>
    <dialog #confirmation class="maintenance-confirm" aria-labelledby="maintenance-title" aria-describedby="maintenance-description" (cancel)="cancelConfirmation()">
    @if(confirmAction(); as action) {
      <h2 id="maintenance-title">Truncate data?</h2>
      <p id="maintenance-description">Delete application and monitoring data and restore the initial application data?</p>
      <div class="dialog-actions"><button type="button" class="primary-button" [disabled]="busy()" (click)="confirmMaintenance(action)">Truncate data</button>
      <button type="button" class="secondary-button" (click)="cancelConfirmation()" autofocus>Cancel</button></div>
    }</dialog>
    @if(actionError() || state.maintenance?.error) {<p role="alert">{{actionError() || state.maintenance?.error}}</p>}
    <div class="test-summary">
      <div class="test-heading"><h3>Tests</h3></div>
      <div class="test-controls">
        <button class="icon-button" type="button" aria-label="Run tests" title="Run tests"
          [disabled]="startingTests() || state.testResults?.running || !state.testResults?.runnable || busy()" (click)="runTests()"><i class="bi bi-rocket-takeoff" aria-hidden="true"></i></button>
        <div class="test-bar" [class.empty-tests]="!state.testResults?.available" [class.running-tests]="state.testResults?.available && state.testResults.running" role="img"
          [attr.aria-label]="state.testResults?.available ? state.testResults.passed + ' passed, ' + state.testResults.failed + ' failed, ' + testTotal(state.testResults) + ' total' + (state.testResults.skipped ? ', ' + state.testResults.skipped + ' skipped' : '') : 'No test results available'">
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
    <dev-test-output [lines]="state.testOutput || []"/>
    @if(state.monitoring.resources; as r) {
      @if(r.diskThresholdExceeded) {<p role="status">Disk retention threshold exceeded; recent partitions are retained.</p>}
      @if(r.error || r.auditlog?.storage?.retentionError) {<p role="alert">{{r.error || r.auditlog.storage.retentionError}}</p>}
    }
    @if(state.monitoring.droppedLogLines) {<p role="status">{{state.monitoring.droppedLogLines}} log lines dropped</p>}
    </section>}`})
@Handler()
export class EnvironmentComponent {
  readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  status = input<Status>();
  displayName = input('');
  readonly groups = computed(() => {
    const state = this.status();
    if (!state) return [];
    return [true, false].map(application => {
      const components = (state.components || []).filter(c => c.application === application);
      const count = (c: NonNullable<Status['components']>[number]) => ({
        running: c.runningProcesses ?? (c.state === 'running' ? 1 : 0), total: c.totalProcesses ?? 1
      });
      const running = components.reduce((sum, c) => sum + count(c).running, 0);
      const total = components.reduce((sum, c) => sum + count(c).total, 0);
      return {
        id: application ? 'application' : 'devserver', application, components,
        name: application ? components.find(c => !c.id.startsWith('frontend-'))?.name || state.project : 'Fluxzero dev server',
        runningProcesses: running, totalProcesses: total,
        state: running > 0 && running < total ? 'degraded' : components.some(c => c.state === 'failed') ? 'failed' : components.some(c => c.state === 'starting') ? 'starting' : total > 0 && running === total ? 'running' : 'stopped',
        memoryBytes: totalMemory(components),
        memoryMaxBytes: totalMemory(components, true),
        url: components.find(c => c.url)?.url
      };
    });
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
