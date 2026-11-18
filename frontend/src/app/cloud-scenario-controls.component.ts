import {Component, inject} from '@angular/core';
import {CloudConnection} from './cloud-api';

@Component({selector:'dev-cloud-scenarios',standalone:true,template:`
  <details><summary>Preview scenarios</summary><div class="scenario-controls" aria-label="Cloud preview controls"><span>Cloud preview</span><label>Starting point<select [value]="cloud.state()?.scenario || 'local'" (change)="select($event)" [disabled]="cloud.busy()">@for(scenario of cloud.state()?.scenarios;track scenario.id){<option [value]="scenario.id">{{scenario.label}}</option>}</select></label><button type="button" (click)="cloud.call({action:'scenario',scenario:cloud.state()?.scenario || 'local'})" [disabled]="cloud.busy()">Reset</button></div></details>
`,styles:`details{margin-bottom:20px}summary{cursor:pointer;color:var(--dashboard-muted);font-size:11px}.scenario-controls{display:flex;align-items:center;flex-wrap:wrap;gap:12px;margin-bottom:24px;padding:10px 14px;border:1px dashed var(--dashboard-border);border-radius:8px;color:var(--dashboard-muted);font-size:12px}.scenario-controls>span{margin-right:auto}label{display:flex;align-items:center;gap:8px}select{max-width:220px;padding:6px;border:1px solid var(--dashboard-border);border-radius:5px;background:var(--dashboard-surface);color:var(--dashboard-text);font:inherit}button{border:0;background:none;color:var(--dashboard-active);font:inherit}`})
export class CloudScenarioControlsComponent {
  readonly cloud=inject(CloudConnection);
  select(event:Event){void this.cloud.call({action:'scenario',scenario:(event.target as HTMLSelectElement).value});}
}
