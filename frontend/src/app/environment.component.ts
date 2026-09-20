import {Component, ElementRef, inject, input, signal, ViewChild, ChangeDetectorRef, computed, DestroyRef, effect} from '@angular/core';
import {NgTemplateOutlet} from '@angular/common';
import {ProjectPathComponent} from './project-path.component';
import {Status} from './models';
import {WorkspaceStopComponent} from './workspace-stop.component';
import {WorkspaceRestartComponent} from './workspace-restart.component';
import {ResourceDetailComponent} from './resource-detail.component';
import {ResourceGraphComponent} from './resource-graph.component';
import {totalMemory, usedMemory} from './resource-history';
import {formatBytes} from './format-bytes';
import {Handler, HandleQuery, sendCommand} from './dom-handlers';
import {ProjectRenameComponent} from './project-rename.component';
import {StartedAgoComponent} from './started-ago.component';

@Component({selector: 'dev-environment', standalone: true, imports: [ProjectRenameComponent, StartedAgoComponent, WorkspaceStopComponent, WorkspaceRestartComponent, NgTemplateOutlet, ProjectPathComponent, ResourceDetailComponent, ResourceGraphComponent], template: `
  @if(status(); as state) {<section [class.page]="!embedded()" [class.current-project]="embedded()" aria-label="Current project"><div class="page-heading workspace-heading"><div class="workspace-summary">
    <div class="project-title-row">
      @if(embedded()) {<h3 class="current-project-title">{{displayName() || state.project}}</h3>}
      @else {<h1>{{displayName() || state.project}}</h1>}
      <dev-project-rename [id]="projectId()" [name]="displayName() || state.project"/>
    </div>
    <div class="project-path"><dev-project-path [path]="state.projectDirectory" [id]="projectId()" [exists]="directoryExists()"/>@if(!state.maintenance?.workspaceStopped && !fullyStopped()) {<dev-started-ago [startedAt]="state.startedAt"/>}</div>
    </div>
    <div class="workspace-actions">
      <div class="workspace-action-buttons">
        @if(!state.maintenance?.workspaceStopped && !fullyStopped()) {<dev-workspace-restart [busy]="busy()" [action]="maintenanceAction()" [appsSupported]="!!state.maintenance?.applicationRestartSupported" [environmentSupported]="!!state.maintenance?.restartSupported" (restart)="requestMaintenance($event)"/>}
        @if(state.maintenance?.stopSupported && !fullyStopped()) {<dev-workspace-stop [working]="!!maintenanceAction()?.startsWith('stop-') || maintenanceAction() === 'start-workspace'" [busy]="busy()" [stopped]="!!state.maintenance?.workspaceStopped" (actionRequested)="requestMaintenance($event)"/>}
      </div>
    </div></div>
    @if(state.workspaceIssue && !state.maintenance?.workspaceStopped && !fullyStopped()) {<p class="workspace-issue" role="status"><i class="bi bi-exclamation-circle" aria-hidden="true"></i>{{state.workspaceIssue}}</p>}
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
            <button class="icon-button dashboard-action" type="button" [attr.aria-label]="'Restart ' + component.name" title="Restart only this app from its last successful build" [disabled]="busy() || !component.restartSupported" [attr.aria-busy]="maintenanceAction() === 'restart-app:' + component.id" (click)="maintain('restart-app:' + component.id)">
              @if(maintenanceAction() === 'restart-app:' + component.id) {<span class="spinner-border spinner-border-sm" role="status" aria-label="Restarting app"></span>}
              @else {<i class="bi bi-arrow-clockwise" aria-hidden="true"></i>}<span>Restart</span>
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
    @if(fullyStopped()) {<p class="workspace-stopped" role="status">Project stopped. Ask your agent to start it again when you need it.</p>}
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
    return this.confirmAction() === 'stop-workspace' ? 'Stop apps'
      : this.confirmAction() === 'stop-devserver' ? 'Stop project' : 'Restart project';
  }
  confirmationDescription() {
    return this.confirmAction() === 'stop-workspace'
      ? 'This stops your apps and resets their local data. Devboard stays open, so you can start again here. Your code and progress are kept.'
      : this.confirmAction() === 'stop-devserver'
      ? 'This stops your apps and closes Devboard. Local app data will be reset. Your code and progress are kept. Ask your agent to start the project again when you need it.'
      : 'This restarts your project. Local app data and activity history will be cleared, and startup data will be loaded again. Your code and progress are kept.';
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
  readonly formatBytes = formatBytes;
}
