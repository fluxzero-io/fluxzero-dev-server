import {signal} from '@angular/core';
import {DemoCloudApi, DemoCloudState, cloudFixtureState} from './cloud-demo-api';
import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {CLOUD_API, CLOUD_DEMO_ENABLED, CloudCommand} from './cloud-api';
import {PublishSetupComponent} from './publish-setup.component';
import {CloudResourcesComponent} from './cloud-resources.component';
import {CloudContextComponent} from './cloud-context.component';
import {AccountConnection} from './account-connection';
import {CloudConnection} from './cloud-api';

function initial():DemoCloudState{return {version:1,mode:'demo',revision:1,scenario:'local',scenarios:[],account:'signed_out',accountName:'Alex',availability:'available',organizations:[{id:'acme',name:'Acme Studio',plan:'Starter',applications:0,limit:1,role:'Owner'}],organizationId:'',plannedPlan:'',canDeploy:true,capacityAvailable:true,githubConnected:false,repositories:['acme/parcel-service'],repository:'',branch:'main',integration:'',availableClusters:[],cluster:'',application:'parcel-service',environment:'Production',proposal:null,deployment:null,domains:[],emailPreview:{},authTested:false,message:'',setup:{applicationConfigured:false,size:'Starter',pipelineConfigured:false,workflowPath:'.github/workflows/deploy.yml',workflowStatus:'not_checked'}};}

describe('Publication setup checklist',()=>{
  let state:DemoCloudState,calls:CloudCommand[];
  beforeEach(()=>{
    state=initial();calls=[];
    const api={session:signal<DemoCloudState|undefined>(undefined),call:async(command:CloudCommand)=>{
      calls.push(command);
      if(command.action==='sign-in')state={...state,account:'signed_in'};
      if(command.action==='select-organization')state={...state,organizationId:command['organizationId']};
      if(command.action==='configure-application')state={...state,cluster:'parcel-service-01',setup:{...state.setup!,applicationConfigured:true}};
      if(command.action==='connect-github')state={...state,githubConnected:true};
      if(command.action==='select-source')state={...state,repository:command['repository'],branch:command['branch']};
      if(command.action==='configure-pipeline')state={...state,setup:{...state.setup!,pipelineConfigured:true}};
      api.session.set(state);return cloudFixtureState(state);
    }};
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),{provide:CLOUD_DEMO_ENABLED,useValue:true},{provide:DemoCloudApi,useValue:api},{provide:CLOUD_API,useValue:api}]});
  });
  it('finishes setup with a Cloud handoff and never claims a build, push or deployment',async()=>{
    const fixture=TestBed.createComponent(PublishSetupComponent);fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.setup-timeline > li').length).toBe(6);
    expect(calls.map(c=>c.action)).toEqual(['status']);
    expect(fixture.nativeElement.querySelector('.cloud-link')).toBeNull();
    (fixture.nativeElement.querySelector('.sign-in-action') as HTMLButtonElement).click();await fixture.whenStable();fixture.detectChanges();
    const page=fixture.componentInstance;
    await page.chooseOrganization('acme');fixture.detectChanges();
    await page.saveApplication();fixture.detectChanges();
    await page.connectGitHub();fixture.detectChanges();
    await page.saveDelivery();fixture.detectChanges();
    expect(page.deliveryReady()).toBeTrue();
    expect(fixture.nativeElement.querySelector('.setup-timeline > li:nth-child(5)').classList.contains('done')).toBeFalse();
    expect(fixture.nativeElement.textContent).toContain('Commit and push the workflow');
    expect(fixture.nativeElement.textContent).not.toContain('Healthy');
    expect(calls.some(c=>['review','deploy'].includes(c.action))).toBeFalse();
    expect(state.deployment).toBeNull();
    const changed=spyOn(page.pageChanged,'emit');
    expect(fixture.nativeElement.querySelector('.setup-timeline > li:last-child .cloud-link')?.textContent).toContain('See your live applications');
    (fixture.nativeElement.querySelector('.cloud-link') as HTMLButtonElement).click();
    expect(changed).toHaveBeenCalledWith('cloud/applications');
    TestBed.inject(HttpTestingController).expectNone(r=>r.url.startsWith('account/'));
    fixture.destroy();
  });
  it('shows a planned cluster and an unreported app separately from running resources',async()=>{
    state={...initial(),account:'signed_in',organizationId:'acme',cluster:'parcel-service-01',repository:'acme/parcel-service',setup:{...initial().setup!,applicationConfigured:true,pipelineConfigured:true}};
    const fixture=TestBed.createComponent(CloudResourcesComponent);fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Waiting for first deployment');
    expect(fixture.nativeElement.textContent).toContain('Planned');
    expect(fixture.nativeElement.textContent).toContain('Build and push status is not connected yet');
    expect(fixture.nativeElement.querySelector('.status.running')).toBeNull();
    expect(fixture.nativeElement.querySelector('.application-url')).toBeNull();
    fixture.destroy();
  });
  it('renders the saved existing cluster when its options appear after Cloud discovery',async()=>{
    state={...initial(),account:'signed_in',organizationId:'acme',cluster:'shared-01',availableClusters:['shared-01'],setup:{...initial().setup!,applicationConfigured:true}};
    const fixture=TestBed.createComponent(PublishSetupComponent);fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    fixture.componentInstance.edit(2);fixture.detectChanges();
    const select=fixture.nativeElement.querySelector('.setup-timeline > li:nth-child(3) details select') as HTMLSelectElement;
    expect(fixture.componentInstance.cluster()).toBe('existing');
    expect(select.value).toBe('existing');
    expect(select.selectedOptions[0].textContent).toBe('shared-01');
    fixture.destroy();
  });
  it('shows only an explicitly known organization plan and never infers Free from sign-in',async()=>{
    state={...initial(),account:'signed_in',organizationId:'acme'};
    const fixture=TestBed.createComponent(CloudContextComponent);fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    const cloud=TestBed.inject(CloudConnection);
    for(const plan of ['Free','Starter','Pro']) {
      cloud.state.set(cloudFixtureState({...state,organizations:[{...state.organizations[0],plan}]}));fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.plan-badge')?.textContent).toContain(plan);
    }
    for(const snapshot of [{...state,organizationId:''},{...state,organizations:[{...state.organizations[0],plan:''}]},{...state,availability:'offline' as const},{...state,account:'signed_out' as const}]) {
      cloud.state.set(cloudFixtureState(snapshot));if(snapshot.account==='signed_out')TestBed.inject(AccountConnection).account.set({state:'signed_out'});fixture.detectChanges();expect(fixture.nativeElement.querySelector('.plan-badge')).toBeNull();
    }
    fixture.destroy();
  });
  it('uses the configured Dashboard link and leaves an unavailable preview destination disabled',async()=>{
    state={...initial(),account:'signed_in',organizationId:'acme'};
    const fixture=TestBed.createComponent(CloudResourcesComponent);fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button.dashboard-link').disabled).toBeTrue();
    const account=TestBed.inject(AccountConnection);
    account.account.set({state:'signed_in',dashboardUrl:'http://dashboard.fluxzero.localhost:4200/'});fixture.detectChanges();
    const link=fixture.nativeElement.querySelector('a.dashboard-link') as HTMLAnchorElement;
    expect(link.href).toBe('http://dashboard.fluxzero.localhost:4200/');expect(link.target).toBe('_blank');expect(link.rel).toContain('noopener');
    account.account.set({state:'signed_in',dashboardUrl:'javascript:alert(1)'});fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a.dashboard-link')).toBeNull();
    fixture.destroy();
  });
  it('signs in directly on a Cloud page without navigating to publication setup',async()=>{
    const fixture=TestBed.createComponent(CloudResourcesComponent);fixture.componentRef.setInput('section','clusters');fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    const changed=spyOn(fixture.componentInstance.pageChanged,'emit');
    expect(calls.map(call=>call.action)).toEqual(['status']);
    (fixture.nativeElement.querySelector('.account-sign-in') as HTMLButtonElement).click();await fixture.whenStable();fixture.detectChanges();
    expect(calls.map(call=>call.action)).toEqual(['status','sign-in']);expect(changed).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Clusters');
    TestBed.inject(HttpTestingController).expectNone(r=>r.url.startsWith('account/'));fixture.destroy();
  });
  it('offers the configured Dashboard before sign-in without accepting an unsafe account override',async()=>{
    const client=window as Window & {fluxzeroDashboardUrl?:string},previous=client.fluxzeroDashboardUrl;
    client.fluxzeroDashboardUrl='http://dashboard.fluxzero.localhost:4200/console?source=private#draft';
    const fixture=TestBed.createComponent(PublishSetupComponent);
    try {
      fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
      const link=fixture.nativeElement.querySelector('.publish-destinations a.dashboard-link') as HTMLAnchorElement;
      expect(link.href).toBe('http://dashboard.fluxzero.localhost:4200/console/');
      expect(fixture.nativeElement.querySelector('.setup-timeline > li:first-child .account-sign-in')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.publish-destinations .account-sign-in')).toBeNull();
      expect(fixture.nativeElement.querySelectorAll('.account-sign-in').length).toBe(1);
      expect(fixture.nativeElement.querySelector('.setup-timeline > li:last-child .dashboard-link')).toBe(link);
      expect(calls.map(call=>call.action)).toEqual(['status']);
      TestBed.inject(AccountConnection).account.set({state:'signed_out',dashboardUrl:'javascript:alert(1)'});fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('a.dashboard-link')).toBeNull();
    } finally {fixture.destroy();if(previous===undefined)delete client.fluxzeroDashboardUrl;else client.fluxzeroDashboardUrl=previous;}
  });
});
