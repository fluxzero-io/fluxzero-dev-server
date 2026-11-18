import {Component, computed, inject, input, output} from '@angular/core';
import {CloudConnection} from './cloud-api';
import {AccountConnection} from './account-connection';
import {dashboardDestination} from './cloud-page.component';

@Component({selector:'dev-cloud-resources',standalone:true,template:`
  <section class="cloud-resources"><header><div><span class="eyebrow">Fluxzero Cloud</span><h1 tabindex="-1">{{title()}}</h1>
    @if(data()?.account==='signed_in' && data()?.availability==='available'){@if(organization();as org){<div class="organization-context"><span>{{org.name}}</span>@if(plan();as label){<span class="plan-badge" [attr.aria-label]="'Organization plan: ' + label"><i class="bi bi-cloud" aria-hidden="true"></i>{{label}}</span>}</div>}}
    </div>@if(section()==='overview' && data()?.account==='signed_in'){@if(dashboardUrl();as url){<a class="secondary-button dashboard-button" [href]="url" target="_blank" rel="noopener noreferrer">Dashboard<i class="bi bi-box-arrow-up-right" aria-hidden="true"></i></a>}@else{<button class="secondary-button dashboard-button" disabled title="Dashboard link is not configured for this preview">Dashboard<i class="bi bi-box-arrow-up-right" aria-hidden="true"></i></button>}}</header>
  @if(cloud.error()){<p class="notice" role="alert">{{cloud.error()}}</p>}
  @if(data()?.account!=='signed_in'){<div class="resource-card"><h2>Connect your account</h2><p>Sign in to view your Cloud setup.</p><button class="primary-button" (click)="pageChanged.emit('publish')">Publish live</button></div>}
  @else if(data()?.availability!=='available'){<div class="resource-card"><h2>Cloud is unavailable</h2><p>Your local project is still available.</p><button class="secondary-button" [disabled]="cloud.busy()" (click)="cloud.call({action:'status'})">Try again</button></div>}
  @else if(!organization()){<div class="resource-card"><h2>Your live app starts here</h2><p>Choose an organization and set up your app in Publish live.</p><button class="primary-button" (click)="pageChanged.emit('publish')">Set up publishing</button></div>}
  @else{
    <div class="resource-grid">
      @if(section()==='overview' || section()==='applications'){<article class="resource-card"><div class="card-heading"><h2><i class="bi bi-box" aria-hidden="true"></i>Application</h2><span class="status" [class.running]="data()?.deployment?.state==='healthy'">{{data()?.deployment?.state==='healthy' ? 'Running' : data()?.deployment?.state==='running' ? 'Deployment reported' : data()?.deployment?.state==='failed' ? 'Needs attention' : 'Waiting for first deployment'}}</span></div><h3>{{data()?.application}}</h3>
        @if(data()?.deployment;as deployment){<dl><dt>Version</dt><dd><code>{{deployment.commit}}</code></dd><dt>Cluster</dt><dd>{{data()?.cluster}}</dd></dl>@if(deployment.state==='healthy'){<code class="application-url">{{deployment.url}}</code>}}
        @else{<p>Cloud has not reported a deployment for this app yet.</p>@if(data()?.setup?.applicationConfigured){<p class="hint">{{data()?.setup?.size}} · destination {{data()?.cluster}}</p>}}
      </article>}
      @if(section()==='overview' || section()==='clusters'){<article class="resource-card"><h2><i class="bi bi-diagram-3" aria-hidden="true"></i>Cluster</h2>@if(data()?.availableClusters?.length){@for(cluster of data()?.availableClusters;track cluster){<h3>{{cluster}}</h3>}<p>Available in {{organization()?.name}}.</p>}@if(data()?.setup?.applicationConfigured && !data()?.availableClusters?.includes(data()!.cluster)){<h3>{{data()?.cluster}}</h3><span class="status">Planned</span><p>Your GitHub workflow can create this cluster on the first deployment.</p>}@else if(!data()?.availableClusters?.length){<p>Choose a cluster or let your GitHub workflow create one on the first deployment.</p>}</article>}
      @if(section()==='overview' || section()==='integrations'){<article class="resource-card"><h2><i class="bi bi-robot" aria-hidden="true"></i>GitHub workflow</h2>@if(data()?.setup?.pipelineConfigured){<h3>{{data()?.repository}}</h3><p>{{data()?.branch}} · GitHub Actions</p><code>{{data()?.setup?.workflowPath}}</code><p class="hint">Build and push status is not connected yet.</p>}@else{<p>Connect the repository and workflow in Publish live.</p>}</article>}
    </div>
  }
  </section>
`,styles:`:host{display:block}.cloud-resources{max-width:1120px;margin:auto;padding:28px 32px 48px}header{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:28px}h1{font-size:32px;margin:6px 0 0}.eyebrow,.hint{font-size:12px;color:var(--dashboard-muted)}.organization-context{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-top:12px;font-size:13px;color:var(--dashboard-muted)}.plan-badge{display:inline-flex;align-items:center;gap:6px;padding:4px 9px;border:1px solid var(--dashboard-border);border-radius:20px;background:var(--dashboard-surface);color:var(--dashboard-text);font-size:12px}.plan-badge i{font-size:14px;color:var(--dashboard-control-icon)}.dashboard-button{flex-shrink:0}.dashboard-button:disabled{opacity:.65}.resource-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,340px),1fr));gap:20px}.resource-card{background:var(--dashboard-surface);border:1px solid var(--dashboard-border);border-radius:12px;padding:24px}h2{display:flex;align-items:center;gap:8px;font-size:14px;margin:0 0 18px}h2 i{color:var(--dashboard-control-icon);font-size:18px}h3{font-size:18px;margin:16px 0;overflow-wrap:anywhere}p{color:var(--dashboard-muted);font-size:13px;line-height:1.6}.card-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.card-heading h2{margin:0}.status{display:inline-flex;padding:4px 8px;border:1px solid var(--dashboard-border);border-radius:20px;font-size:11px;color:var(--dashboard-muted)}.status.running{background:var(--dashboard-status-success-bg);color:var(--dashboard-success-text)}dl{display:grid;grid-template-columns:auto 1fr;gap:10px 16px;font-size:12px}dt{color:var(--dashboard-muted)}dd{margin:0}code{font-size:12px;overflow-wrap:anywhere}.application-url{display:block;margin-top:20px}.notice{padding:14px;border:1px solid var(--dashboard-border);border-radius:8px}@media(max-width:600px){.cloud-resources{padding:22px 16px}header{align-items:flex-start;flex-direction:column}}`})
export class CloudResourcesComponent {
  readonly section=input('overview');
  readonly pageChanged=output<string>();
  readonly cloud=inject(CloudConnection);
  readonly account=inject(AccountConnection);
  readonly data=this.cloud.state;
  readonly organization=computed(()=>this.data()?.organizations.find(o=>o.id===this.data()?.organizationId));
  readonly plan=computed(()=>this.data()?.account==='signed_in' && this.data()?.availability==='available' && ['Free','Starter','Pro'].includes(this.organization()?.plan || '') ? this.organization()!.plan : undefined);
  readonly dashboardUrl=computed(()=>dashboardDestination(this.account.account()?.dashboardUrl));
  readonly title=computed(()=>({overview:'Overview',applications:'Applications',clusters:'Clusters',integrations:'Integrations'}[this.section()] || 'Overview'));
  constructor(){this.cloud.initialize();}
}
