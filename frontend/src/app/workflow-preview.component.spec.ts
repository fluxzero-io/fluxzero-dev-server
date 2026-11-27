import {TestBed} from '@angular/core/testing';
import {CloudState} from './cloud-api';
import {githubWorkflowSnippet} from './github-workflow';
import {WorkflowPreviewComponent} from './workflow-preview.component';

describe('Workflow preview',()=>{
  const saved={application:'parcel-service',cluster:'shared-01',setup:{workflowContents:githubWorkflowSnippet,workflowSource:'suggested',workflowPath:'.github/workflows/deploy.yml',workflowApplication:'parcel-service',workflowCluster:'shared-01'}} as CloudState;
  it('shows Actions variable changes without claiming the workflow file changed',()=>{
    const fixture=TestBed.createComponent(WorkflowPreviewComponent);
    fixture.componentRef.setInput('state',{...saved,application:'parcel-service-v2',cluster:'parcel-service-v2-01'});
    fixture.detectChanges();
    const page=fixture.componentInstance;
    expect(page.changes().map(change=>change.key)).toEqual(['FLUXZERO_APPLICATION_NAME','FLUXZERO_CLUSTER_NAME']);
    (fixture.nativeElement.querySelector('.workflow-preview-link') as HTMLButtonElement).click();fixture.detectChanges();
    const dialog=fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    expect(dialog.open).toBeTrue();
    expect(dialog.querySelector('.diff-removed')?.textContent).toContain('parcel-service');
    expect(dialog.querySelector('.diff-added')?.textContent).toContain('parcel-service-v2');
    expect(dialog.textContent).toContain('Proposed configuration');
    page.view.set('workflow');fixture.detectChanges();
    expect(dialog.querySelector('code')?.textContent).toContain('vars.FLUXZERO_APPLICATION_NAME');
    expect(dialog.querySelector('code')?.textContent).not.toContain('parcel-service-v2');
    dialog.dispatchEvent(new Event('cancel',{cancelable:true}));fixture.detectChanges();
    expect(dialog.open).toBeFalse();
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('.workflow-preview-link'));
    fixture.destroy();
  });
  it('distinguishes initial configuration from an unchanged saved destination',()=>{
    const fixture=TestBed.createComponent(WorkflowPreviewComponent);
    fixture.componentRef.setInput('state',saved);fixture.detectChanges();
    expect(fixture.componentInstance.changes()).toEqual([]);
    fixture.componentInstance.open();fixture.detectChanges();
    expect(fixture.componentInstance.view()).toBe('workflow');
    fixture.componentInstance.view.set('changes');fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No variable changes');
    fixture.componentRef.setInput('state',{...saved,setup:{...saved.setup,workflowApplication:'',workflowCluster:''}});fixture.detectChanges();
    expect(fixture.componentInstance.hasBaseline()).toBeFalse();
    expect(fixture.componentInstance.changes().length).toBe(2);
    expect(fixture.nativeElement.querySelector('.diff-removed')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('No saved publishing setup');
    fixture.componentInstance.close();fixture.destroy();
  });
});
