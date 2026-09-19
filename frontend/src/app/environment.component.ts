import {Component, ElementRef, inject, input, signal, ViewChild, ChangeDetectorRef, computed, DestroyRef, effect} from '@angular/core';
import {NgTemplateOutlet} from '@angular/common';
import {ProjectPathComponent} from './project-path.component';
import {Status} from './models';
import {WorkspaceStopComponent} from './workspace-stop.component';
import {WorkspaceRestartComponent} from './workspace-restart.component';
import {ResourceDetailComponent} from './resource-detail.component';
import {ResourceGraphComponent} from './resource-graph.component';
import {TestOutputComponent} from './test-output.component';
import {totalMemory, usedMemory} from './resource-history';
import {formatBytes} from './format-bytes';
import {Handler, HandleQuery, sendCommand} from './dom-handlers';

@Component({selector: 'dev-environment', standalone: true, imports: [WorkspaceStopComponent, WorkspaceRestartComponent, NgTemplateOutlet, ProjectPathComponent, ResourceDetailComponent, ResourceGraphComponent, TestOutputComponent], template: `
  @if(status(); as state) {<section [class.page]="!embedded()" [class.current-project]="embedded()" aria-label="Current project"><div class="page-heading workspace-heading"><div class="workspace-summary">
    <div class="environment-eyebrow"><i class="bi bi-terminal" aria-hidden="true"></i> YOUR WORKSPACE</div>
    <div class="project-title-row">
      @if(embedded()) {<h3 class="current-project-title"><a href="#projects" (click)="openProjects($event)">{{displayName() || state.project}}</a></h3>}
      @else {<h1>{{displayName() || state.project}}</h1>}
    </div>
    <div class="project-path"><dev-project-path [path]="state.projectDirectory" [id]="projectId()" [exists]="directoryExists()"/></div>
    </div>
    <div class="workspace-actions">
      <div class="workspace-action-buttons">
        @if(!state.maintenance?.workspaceStopped && !fullyStopped()) {<dev-workspace-restart [busy]="busy()" [action]="maintenanceAction()" [appsSupported]="!!state.maintenance?.applicationRestartSupported" [environmentSupported]="!!state.maintenance?.restartSupported" (restart)="requestMaintenance($event)"/>}
        @if(state.maintenance?.stopSupported && !fullyStopped()) {<dev-workspace-stop [working]="!!maintenanceAction()?.startsWith('stop-') || maintenanceAction() === 'start-workspace'" [busy]="busy()" [stopped]="!!state.maintenance?.workspaceStopped" (actionRequested)="requestMaintenance($event)"/>}
      </div>
    </div></div>
    <ng-template #componentCard let-component>
    <div class="component-table-scroll"><table role="table" class="component-table" [attr.aria-label]="component.application ? 'Application resources' : 'Infrastructure resources'">
      <thead role="rowgroup"><tr role="row"><th role="columnheader" scope="col">Component</th><th role="columnheader" scope="col">Status</th><th role="columnheader" scope="col">Memory</th>@if(component.application) {<th role="columnheader" scope="col" aria-label="Restart app"></th>}@if(!component.application) {<th role="columnheader" scope="col">Monitoring storage</th>}</tr></thead>
      <tbody role="rowgroup">
        <tr role="row" [class.application-component]="component.application">
          <th role="rowheader" scope="row" class="component-name"><i class="bi" [class.bi-window]="component.application" [class.bi-hdd-stack]="!component.application" aria-hidden="true"></i>{{component.name}}
          </th>
          <td role="cell" class="component-status">
            @if(component.application) {<span class="badge" [class.running]="component.state === 'running'" [class.starting]="component.state === 'starting'" [class.degraded]="component.state === 'degraded'" [class.failed]="component.state === 'failed'">{{component.state}}</span>}
            @else {<dev-resource-detail [components]="component.components" kind="status"><span class="badge" [class.running]="component.state === 'running'" [class.starting]="component.state === 'starting'" [class.degraded]="component.state === 'degraded'" [class.failed]="component.state === 'failed'">{{component.state}}</span></dev-resource-detail>}
          </td>
          <td role="cell" class="component-memory" [attr.data-label]="component.application ? 'Memory' : 'Total memory'">
            <div class="resource-reading">
              @if(component.application) {<span class="resource-value">{{formatBytes(component.memoryBytes)}}@if(component.memoryMaxBytes != null) { / {{formatBytes(component.memoryMaxBytes)}}}</span>}
              @else {<dev-resource-detail [components]="component.components" [samples]="state.resourceHistory || []" kind="memory"><span class="resource-value">{{formatBytes(component.memoryBytes)}}@if(component.memoryMaxBytes != null) { / {{formatBytes(component.memoryMaxBytes)}}}</span></dev-resource-detail>}
              <dev-resource-graph [title]="component.name + ' · Memory'" [samples]="state.resourceHistory || []" [metric]="component.application ? 'applicationMemory' : 'devserverMemory'" [componentId]="component.application ? component.id : undefined" [current]="component.memoryBytes" [limit]="component.memoryMaxBytes"/>
            </div>
          </td>
          @if(!component.application) {<td role="cell" class="component-storage" data-label="Monitoring storage">
            <div class="resource-reading"><span class="resource-value">{{formatBytes(state.monitoring.resources?.storageDiskBytes)}}@if(state.monitoring.resources?.diskRetentionThresholdBytes != null) { / {{formatBytes(state.monitoring.resources?.diskRetentionThresholdBytes)}}}</span>
              <dev-resource-graph title="Monitoring storage" [samples]="state.resourceHistory || []" metric="monitoringStorage" [current]="state.monitoring.resources?.storageDiskBytes" [limit]="state.monitoring.resources?.diskRetentionThresholdBytes"/>
            </div>
          </td>}

          @if(component.application) {<td role="cell" class="component-app-restart">
            <button class="icon-button" type="button" [attr.aria-label]="'Restart ' + component.name" title="Restart only this app from its last successful build" [disabled]="busy() || !component.restartSupported" [attr.aria-busy]="maintenanceAction() === 'restart-app:' + component.id" (click)="maintain('restart-app:' + component.id)">
              @if(maintenanceAction() === 'restart-app:' + component.id) {<span class="spinner-border spinner-border-sm" role="status" aria-label="Restarting app"></span>}
              @else {<i class="bi bi-arrow-clockwise" aria-hidden="true"></i>}<span>Restart app</span>
            </button>
          </td>}
        </tr>
      </tbody>
    </table></div>
    </ng-template>
    <dialog #confirmation class="maintenance-confirm" aria-labelledby="maintenance-title" aria-describedby="maintenance-description" (cancel)="cancelConfirmation()">
    @if(confirmAction(); as action) {
      <h2 id="maintenance-title">{{confirmationTitle()}}?</h2>
      <p id="maintenance-description">{{confirmationDescription()}}</p>
      <div class="dialog-actions">
      <button type="button" class="secondary-button dialog-cancel" (click)="cancelConfirmation()" autofocus>Cancel</button>
      <button type="button" class="primary-button" [disabled]="busy()" (click)="confirmMaintenance(action)">{{confirmationTitle()}}</button></div>
    }</dialog>
    @if(actionError() || state.maintenance?.error) {<p role="alert">{{actionError() || state.maintenance?.error}}</p>}
    @if(fullyStopped()) {<p class="workspace-stopped" role="status">Dev server shutting down. Start it again from the CLI with <code>fz dev</code> in this workspace, or from another active dashboard.</p>}
    @else if(state.maintenance?.workspaceStopped) {<p class="workspace-stopped" role="status">Workspace stopped. Only dashboard controls remain available. Choose Start to start the workspace again.</p>}
    @else {
    <section class="applications-section" aria-labelledby="applications-title">
      <header class="applications-heading"><h2 id="applications-title">Applications</h2>
      </header>
      <div class="application-overview">
        @for(application of applications(); track application.id) {
          <ng-container [ngTemplateOutlet]="componentCard" [ngTemplateOutletContext]="{$implicit: application}"/>
        } @empty {<p class="applications-empty">No applications configured for this environment.</p>}
      </div>
    </section>
    <section class="infrastructure-section" aria-labelledby="infrastructure-title">
      <header><h2 id="infrastructure-title">Dev resources</h2></header>
      <ng-container [ngTemplateOutlet]="componentCard" [ngTemplateOutletContext]="{$implicit: infrastructure()}"/>
    @if(state.monitoring.resources; as r) {
      @if(r.diskThresholdExceeded) {<p role="status">Disk retention threshold exceeded; recent partitions are retained.</p>}
      @if(r.error || r.auditlog?.storage?.retentionError) {<p role="alert">{{r.error || r.auditlog.storage.retentionError}}</p>}
    }
    @if(state.monitoring.droppedLogLines) {<p role="status">{{state.monitoring.droppedLogLines}} log lines dropped</p>}
    </section>
    <section class="environment-tests" aria-labelledby="tests-title">
      <header><h2 id="tests-title">Tests</h2></header>
      <div class="tests-card"><div class="test-summary">
      <div class="test-controls">
        <button class="icon-button" type="button" aria-label="Rerun tests" title="Rerun tests"
          [disabled]="startingTests() || state.testResults?.running || !state.testResults?.runnable || busy()" (click)="runTests()"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i></button>
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
    @if(state.testResults?.paused) {<p class="test-pause-status" role="status">Automatic tests paused.@if(state.testResults.running) { Current test run will finish.}</p>}
    <dev-test-output [lines]="state.testOutput || []" [paused]="!!state.testResults?.paused" [pauseBusy]="changingTestPause() || !state.testResults?.runnable" (toggleTests)="toggleTests()"/></div></section>
    }</section>}`})
@Handler()
export class EnvironmentComponent {
  readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  status = input<Status>();
  displayName = input('');
  readonly applications = computed(() => (this.status()?.components || []).filter(c => c.application && !c.id.startsWith('frontend-')).map(c => ({
    ...c, memoryBytes: usedMemory(c)
  })));
  readonly infrastructure = computed(() => {
    const components = (this.status()?.components || []).filter(c => !c.application || c.id.startsWith('frontend-'));
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
  readonly fullyStopped = computed(() => this.status()?.state === 'shutdown');
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
    if (!['restart-devserver', 'stop-workspace', 'stop-devserver'].includes(action)) { void this.maintain(action); return; }
    this.confirmAction.set(action);
    this.changeDetector.detectChanges();
    this.confirmation?.nativeElement.showModal();
  }
  confirmationTitle() {
    return this.confirmAction() === 'stop-workspace' ? 'Stop workspace'
      : this.confirmAction() === 'stop-devserver' ? 'Stop everything' : 'Restart environment';
  }
  confirmationDescription() {
    return this.confirmAction() === 'stop-workspace'
      ? 'This stops apps, UI servers, automatic builds and tests, and supporting services. In-memory application data is lost. Dashboard controls remain available so you can start again here. Monitoring history on disk is retained.'
      : this.confirmAction() === 'stop-devserver'
      ? 'This stops the entire workspace and closes the dev server, including this dashboard connection. In-memory application data is lost. To start again, run fz dev in this workspace or use another active dashboard. Monitoring history on disk is retained.'
      : 'This restarts all apps, UI servers and supporting services. In-memory application data will be reset and startup commands will run again. Monitoring history stored on disk is retained; resource graphs start fresh.';
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
    try { await sendCommand<Promise<void>>(this.elementRef.nativeElement, 'maintainEnvironment', action); if (action === 'stop-devserver') {this.finishMaintenance();} }
    catch (error: any) {
      this.actionError.set(error?.error?.error || 'Maintenance could not be started.');
      this.finishMaintenance();
    }
    finally { this.maintenanceStatus = this.status(); this.submitting.set(false); }
  }
  readonly changingTestPause = signal(false);
  async toggleTests() {
    if (this.changingTestPause()) return;
    this.changingTestPause.set(true); this.testError.set('');
    try { await sendCommand<Promise<void>>(this.elementRef.nativeElement, 'maintainEnvironment',
      this.status()?.testResults?.paused ? 'resume-tests' : 'pause-tests'); }
    catch (error: any) { this.testError.set(error?.error?.error || 'Unable to change automatic tests.'); }
    finally { this.changingTestPause.set(false); }
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
  openProjects(event: MouseEvent) {
    if (event.button || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    sendCommand(this.elementRef.nativeElement, 'navigate', 'projects');
  }
  readonly formatBytes = formatBytes;
}
