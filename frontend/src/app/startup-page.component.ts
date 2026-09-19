import {Component, input} from '@angular/core';
import {Status} from './models';
import {StartupComponent} from './startup.component';

@Component({selector:'dev-startup-page', standalone:true, imports:[StartupComponent], template:`
  <section class="page startup-page" aria-labelledby="startup-title">
    <header class="page-heading"><div><h1 id="startup-title">Startup data</h1><p>The data prepared when your workspace starts.</p></div></header>
    @if(status(); as state) {
      @if(state.maintenance?.workspaceStopped || state.state === 'shutdown') {
        <p class="workspace-stopped" role="status">Workspace stopped. Start it from <a href="#projects">Workspace</a> to prepare startup data.</p>
      } @else if(state.startup?.actions?.length || state.startup?.state === 'failed') {
        <dev-startup [startup]="state.startup"/>
      } @else {<p class="startup-empty">{{state.state === 'starting' ? 'Waiting for startup actions…' : 'No startup data configured for this workspace.'}}</p>}
    } @else {<p>Connecting to the workspace…</p>}
  </section>`, styles:`
    header {margin-bottom:28px;}
    h1 {font-size:32px;font-weight:800;margin:0;}
    header p {color:var(--dashboard-muted);margin:4px 0 0;font-size:14px;}
    .startup-empty {color:var(--dashboard-muted);font-size:14px;}
    @media(max-width:650px) {h1 {font-size:28px;}}
  `})
export class StartupPageComponent {readonly status=input<Status>();}
