import {Component, input} from '@angular/core';
import {Environment, Status} from './models';
import {EnvironmentComponent} from './environment.component';

@Component({selector: 'dev-projects', standalone: true, imports: [EnvironmentComponent], template: `
  <section class="page project-page">
    <header class="page-heading"><div><h1>Workspace</h1><p>Your app and services at a glance.</p></div></header>
    @if(status()) {
      <dev-environment [status]="status()" [embedded]="true" [displayName]="current()?.projectName || ''"
        [projectId]="current()?.id || ''" [directoryExists]="current()?.directoryExists || false"/>
    }
  </section>`})
export class ProjectsComponent {
  readonly current = input<Environment>();
  readonly status = input<Status>();
}
