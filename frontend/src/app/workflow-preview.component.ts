import {Component, ElementRef, ViewChild, computed, input, signal} from '@angular/core';
import {CloudState} from './cloud-api';

@Component({selector:'dev-workflow-preview',standalone:true,template:`
  <button #trigger class="workflow-preview-link" type="button" (click)="open()" title="Preview deployment steps and proposed GitHub Actions variable changes"><i class="bi bi-file-earmark-code" aria-hidden="true"></i>{{path()}}</button>
  <dialog #dialog class="workflow-dialog" aria-labelledby="workflow-preview-title" aria-describedby="workflow-preview-note" (cancel)="close()">
    <header class="workflow-dialog-heading"><div><h2 id="workflow-preview-title">Deployment workflow</h2><p>{{path()}}</p></div><button class="icon-button" type="button" aria-label="Close workflow preview" (click)="close()"><i class="bi bi-x-lg" aria-hidden="true"></i></button></header>
    <div class="workflow-views" role="group" aria-label="Workflow preview view"><button type="button" class="secondary-button" [attr.aria-pressed]="view()==='workflow'" (click)="view.set('workflow')">Workflow</button><button type="button" class="secondary-button" [attr.aria-pressed]="view()==='changes'" (click)="view.set('changes')">Changes @if(changes().length){({{changes().length}})}</button></div>
    @if(view()==='workflow'){
      <p>{{source()==='suggested' ? 'Suggested deployment steps. Keep your existing build and image publication steps.' : 'Workflow contents for the selected deployment configuration.'}}</p>
      <pre class="workflow-code"><code>{{contents()}}</code></pre>
    }@else{
      <p>App and cluster names are passed through GitHub Actions variables. These changes keep the deployment steps the same.</p>
      @if(changes().length){<pre class="workflow-diff" aria-label="Proposed Actions variable changes"><code><span class="diff-heading">{{hasBaseline() ? '--- Saved publishing setup' : '--- No saved publishing setup'}}
+++ Proposed publishing setup
</span>@for(change of changes();track change.key){@if(change.before){<span class="diff-removed">- {{change.key}}: {{change.before}}
</span>}<span class="diff-added">+ {{change.key}}: {{change.after}}
</span>}</code></pre>}
      @else{<p class="workflow-no-changes"><i class="bi bi-check2" aria-hidden="true"></i>No variable changes from the saved publishing setup.</p>}
    }
    <p id="workflow-preview-note" class="workflow-preview-note">{{note()}}</p>
  </dialog>
`,styles:`:host{display:block;margin:12px 0}.workflow-preview-link{display:inline-flex;align-items:baseline;gap:8px;max-width:100%;padding:0;border:0;background:none;color:var(--dashboard-active);font:inherit;font-size:12px;text-align:left;overflow-wrap:anywhere}.workflow-preview-link:hover{text-decoration:underline}.workflow-dialog{width:min(760px,calc(100vw - 32px));max-height:calc(100dvh - 32px);box-sizing:border-box;padding:24px;border:1px solid var(--dashboard-border);border-radius:14px;background:var(--dashboard-surface);color:var(--dashboard-text)}.workflow-dialog::backdrop{background:#0007}.workflow-dialog-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.workflow-dialog-heading h2{font-size:20px;margin:0}.workflow-dialog p{font-size:13px;line-height:1.6;margin:12px 0;color:var(--dashboard-muted)}.workflow-dialog-heading p{margin:6px 0 0;overflow-wrap:anywhere}.workflow-views{display:flex;gap:8px;margin:24px 0 16px}.workflow-views [aria-pressed=true]{background:var(--dashboard-action-hover);color:var(--dashboard-active)}pre{max-height:50dvh;overflow:auto;padding:16px;border:1px solid var(--dashboard-border);border-radius:8px;background:var(--dashboard-panel-soft);font-size:12px;line-height:1.7;text-align:left}.workflow-diff span{display:block;white-space:pre}.diff-heading{color:var(--dashboard-muted)}.diff-removed{color:var(--dashboard-danger-text);background:var(--dashboard-danger-bg)}.diff-added{color:var(--dashboard-success-text);background:var(--dashboard-success-bg)}.workflow-no-changes{display:flex;align-items:center;gap:8px}.workflow-dialog .workflow-preview-note{font-size:12px;margin:20px 0 0}button:focus-visible{outline:2px solid var(--dashboard-active);outline-offset:4px}@media(max-width:500px){.workflow-dialog{padding:20px}pre{padding:12px;font-size:11px}}`})
export class WorkflowPreviewComponent {
  readonly state=input<CloudState>();
  readonly path=computed(()=>this.state()?.setup?.workflowPath || 'Deployment workflow');
  readonly contents=computed(()=>this.state()?.setup?.workflowContents || '');
  readonly source=computed(()=>this.state()?.setup?.workflowSource || 'suggested');
  readonly note=computed(()=>this.source()==='local' ? 'Read-only local workflow. Proposed changes have not been applied.' : this.source()==='github' ? 'Read-only workflow from GitHub. Proposed changes have not been applied.' : 'Proposed configuration. Review it before applying changes to your workflow or GitHub settings.');
  readonly view=signal<'workflow'|'changes'>('workflow');
  readonly hasBaseline=computed(()=>!!this.state()?.setup?.workflowApplication && !!this.state()?.setup?.workflowCluster);
  readonly changes=computed(()=>[
    {key:'FLUXZERO_APPLICATION_NAME',before:this.state()?.setup?.workflowApplication || '',after:this.state()?.application || ''},
    {key:'FLUXZERO_CLUSTER_NAME',before:this.state()?.setup?.workflowCluster || '',after:this.state()?.cluster || ''}
  ].filter(change=>change.after && change.before!==change.after));
  @ViewChild('dialog') dialog?:ElementRef<HTMLDialogElement>;
  @ViewChild('trigger') trigger?:ElementRef<HTMLButtonElement>;
  open(){this.view.set(this.changes().length ? 'changes' : 'workflow');this.dialog?.nativeElement.showModal();}
  close(){this.dialog?.nativeElement.close();this.trigger?.nativeElement.focus();}
}
