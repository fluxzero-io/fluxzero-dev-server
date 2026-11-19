import {Component, computed, inject} from '@angular/core';
import {AccountConnection} from './account-connection';
import {CloudConnection} from './cloud-api';

@Component({selector:'dev-cloud-context',standalone:true,template:`
  <div class="cloud-context" aria-label="Fluxzero Cloud context">
    @if(organization();as org){
      <span class="organization-name" [title]="'Current Cloud organization: ' + org.name">{{org.name}}</span>
      @if(plan();as label){<span class="plan-badge dashboard-disc" [title]="'Current plan for ' + org.name + ': ' + label" [attr.aria-label]="'Organization plan: ' + label"><i class="bi bi-cloud" aria-hidden="true"></i>{{label}}</span>}
    }@else{<span class="context-label">Fluxzero Cloud</span>}
  </div>
`,styles:`:host{display:block;min-width:0}.cloud-context{display:flex;align-items:center;gap:12px;min-width:0;color:var(--dashboard-muted);font-size:13px}.organization-name{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.plan-badge{flex-shrink:0}.context-label{white-space:nowrap}`})
export class CloudContextComponent {
  private readonly cloud=inject(CloudConnection);
  private readonly account=inject(AccountConnection);
  readonly organization=computed(()=>this.account.signedIn() && this.cloud.state()?.account==='signed_in' && this.cloud.state()?.availability==='available'
    ? this.cloud.state()?.organizations.find(org=>org.id===this.cloud.state()?.organizationId) : undefined);
  readonly plan=computed(()=>['Free','Starter','Pro'].includes(this.organization()?.plan || '') ? this.organization()!.plan : undefined);
  constructor(){this.cloud.initialize();}
}
