import {Component, computed, inject} from '@angular/core';
import {AccountConnection} from './account-connection';
import {CloudConnection} from './cloud-api';

@Component({selector:'dev-cloud-context',standalone:true,template:`
  <div class="cloud-context" aria-label="Fluxzero Cloud context">
    @if(organization();as org){
      @if(plan();as label){<span class="plan-badge dashboard-disc" [title]="'Current plan for ' + org.name + ': ' + label" [attr.aria-label]="'Organization plan: ' + label"><i class="bi bi-cloud" aria-hidden="true"></i>{{label}}</span>}
      <span class="organization-name" [title]="'Current Cloud organization: ' + org.name">{{org.name}}</span>
      @if((cloud.state()?.organizations?.length || 0)>1){<span class="organization-switch"><i class="bi bi-chevron-down" aria-hidden="true"></i><select aria-label="Choose Cloud organization" title="Choose Cloud organization" [value]="org.id" [disabled]="cloud.busy() || !cloud.supports('select-organization')" (change)="chooseOrganization($event)">@for(option of cloud.state()?.organizations;track option.id){<option [value]="option.id" [selected]="option.id===org.id">{{option.name}}</option>}</select></span>}
    }@else{<span class="context-label">Fluxzero Cloud</span>}
  </div>
`,styles:`:host{display:block;min-width:0}.cloud-context{display:flex;align-items:center;gap:10px;min-width:0;color:var(--dashboard-muted);font-size:13px;line-height:18px}.organization-name{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.plan-badge.dashboard-disc{flex-shrink:0;min-height:30px;padding:5px 10px;gap:6px;font-size:13px;line-height:18px}.plan-badge>i{font-size:14px;line-height:18px}.context-label{white-space:nowrap}.organization-switch{position:relative;flex:0 0 24px;width:24px;height:28px;border-radius:5px}.organization-switch>i{position:absolute;inset:0;display:grid;place-items:center;font-size:11px;pointer-events:none}.organization-switch select{position:absolute;inset:0;width:100%;height:100%;padding:0;appearance:none;border:0;background:transparent;color:transparent;cursor:pointer;border-radius:5px;font:inherit}.organization-switch select option{color:var(--dashboard-text);background:var(--dashboard-surface)}.organization-switch:has(select:hover:not(:disabled)){background:var(--dashboard-action-hover)}select:focus-visible{outline:2px solid var(--dashboard-active);outline-offset:2px}select:disabled{cursor:default;opacity:.5}`})
export class CloudContextComponent {
  readonly cloud=inject(CloudConnection);
  private readonly account=inject(AccountConnection);
  readonly organization=computed(()=>this.account.signedIn() && !this.account.account()?.issue && this.cloud.state()?.availability==='available'
    ? this.cloud.state()?.organizations.find(org=>org.id===this.cloud.state()?.organizationId) : undefined);
  readonly plan=computed(()=>['Free','Starter','Pro'].includes(this.organization()?.plan || '') ? this.organization()!.plan : undefined);
  constructor(){this.cloud.initialize();}
  async chooseOrganization(event:Event){
    const select=event.target as HTMLSelectElement;
    await this.cloud.call({action:'select-organization',organizationId:select.value});
    select.value=this.organization()?.id || '';
  }
}
