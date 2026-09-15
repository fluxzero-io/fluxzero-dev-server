import {Component, ElementRef, inject, input, computed} from '@angular/core';
import {Environment, Status} from './models';
import {ProjectPathComponent} from './project-path.component';
import {EnvironmentComponent} from './environment.component';
import {sendCommand} from './dom-handlers';

@Component({selector: 'dev-projects', standalone: true, imports: [EnvironmentComponent, ProjectPathComponent], template: `
  <section class="page">
    <div class="page-heading"><div><h1>Projects</h1><p>Development environments on this computer</p></div>
      </div>
    @if(status()) {<h2 class="project-section-heading">Current project</h2><dev-environment [status]="status()" [embedded]="true" [port]="current()?.port ?? null" [projectId]="current()?.id || ''" [directoryExists]="current()?.directoryExists || false"/>}
    <div class="other-projects-heading"><h2 class="project-section-heading">Other projects</h2>
    <div class="search"><i class="bi bi-search" aria-hidden="true"></i>
      <input #search type="search" placeholder="Search by name or folder" aria-label="Search dev servers" (input)="filter = search.value"></div></div>
    <div class="table-card project-table"><table role="table" aria-label="Other projects"><thead role="rowgroup"><tr role="row"><th role="columnheader">Project</th><th role="columnheader">Status</th><th role="columnheader">Port</th><th role="columnheader"><span class="sr-only">Open</span></th></tr></thead>
      <tbody role="rowgroup">@for (environment of filtered(); track environment.projectDirectory) {
        <tr role="row" [class.current]="environment.projectDirectory === currentProject()">
          <td role="cell" class="project-identity"><div class="project-name"><i class="bi bi-folder2" aria-hidden="true"></i>
            @if (environment.consoleUrl) {<a [href]="environment.consoleUrl + '#projects'" (click)="open(environment, $event)">{{environment.projectName}}</a>}
            @else {<strong>{{environment.projectName}}</strong>}
            @if (environment.projectDirectory === currentProject()) {<span class="current-label">Current</span>}</div>
            <div class="project-path"><dev-project-path [path]="environment.projectDirectory" [id]="environment.id" [exists]="environment.directoryExists"/></div></td>
          <td role="cell" class="project-status"><span class="badge" [class.running]="environment.status === 'running'">{{environment.status === 'running' ? 'running' : 'gestopt'}}</span>
            @if (environment.detail) {<small class="detail">{{environment.detail}}</small>}</td>
          <td role="cell" class="port">{{environment.port ?? '—'}}</td>
          <td role="cell" class="project-actions">@if(environment.consoleUrl) {<a class="icon-button" [href]="environment.consoleUrl + '#projects'" (click)="open(environment, $event)" [attr.aria-label]="'Open ' + environment.projectName"><i class="bi bi-arrow-right" aria-hidden="true"></i></a>}
            <button class="icon-button remove-project" [disabled]="environment.status !== 'stopped'"
              [attr.aria-label]="'Remove ' + environment.projectName + ' from overview'"
              [title]="environment.status === 'stopped' ? 'Remove from overview; keep project files' : 'Stop the dev server before removing it'"
              (click)="forget(environment, $event)"><i class="bi bi-trash" aria-hidden="true"></i></button></td>
        </tr>
      } @empty {<tr><td colspan="4" class="empty">{{filter ? 'No matching dev servers' : 'No other known dev servers yet'}}</td></tr>}</tbody>
    </table></div>
  </section>`})
export class ProjectsComponent {
  readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  environments = input<Environment[]>([]);
  currentProject = input('');
  status = input<Status>();
  current = computed(() => this.environments().find(e => e.projectDirectory === this.currentProject()));
  filter = '';
  filtered() { return this.environments().filter(e => (!this.status() || e.projectDirectory !== this.currentProject()) && `${e.projectName} ${e.projectDirectory}`.toLowerCase().includes(this.filter.toLowerCase())); }
  forget(environment: Environment, event: MouseEvent) {
    sendCommand(event.currentTarget as Element, 'forgetProject', environment.id);
  }
  open(environment: Environment, event: MouseEvent) {
    if (event.button || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    sendCommand(this.elementRef.nativeElement, 'openEnvironment', environment);
  }
}
