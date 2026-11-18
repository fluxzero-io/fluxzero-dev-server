import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {CLOUD_API, CLOUD_DEMO_ENABLED, CloudCommand, CloudState} from './cloud-api';
import {PublishSetupComponent} from './publish-setup.component';
import {CloudResourcesComponent} from './cloud-resources.component';

function initial():CloudState{return {version:1,mode:'demo',revision:1,scenario:'local',scenarios:[],account:'signed_out',accountName:'Alex',availability:'available',organizations:[{id:'acme',name:'Acme Studio',plan:'Starter',applications:0,limit:1,role:'Owner'}],organizationId:'',plannedPlan:'',canDeploy:true,capacityAvailable:true,githubConnected:false,repositories:['acme/parcel-service'],repository:'',branch:'main',integration:'',availableClusters:[],cluster:'',application:'parcel-service',environment:'Production',proposal:null,deployment:null,domains:[],emailPreview:{},authTested:false,message:'',setup:{applicationConfigured:false,size:'Starter',pipelineConfigured:false,workflowPath:'.github/workflows/deploy.yml',workflowStatus:'not_checked'}};}

describe('Publication setup checklist',()=>{
  let state:CloudState,calls:CloudCommand[];
  beforeEach(()=>{
    state=initial();calls=[];
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),{provide:CLOUD_DEMO_ENABLED,useValue:true},{provide:CLOUD_API,useValue:{call:async(command:CloudCommand)=>{
      calls.push(command);
      if(command.action==='sign-in')state={...state,account:'signed_in'};
      if(command.action==='select-organization')state={...state,organizationId:command['organizationId']};
      if(command.action==='configure-application')state={...state,cluster:'parcel-service-01',setup:{...state.setup!,applicationConfigured:true}};
      if(command.action==='connect-github')state={...state,githubConnected:true};
      if(command.action==='select-source')state={...state,repository:command['repository'],branch:command['branch']};
      if(command.action==='configure-pipeline')state={...state,setup:{...state.setup!,pipelineConfigured:true}};
      return state;
    }}}]});
  });
  it('finishes setup with a Cloud handoff and never claims a build, push or deployment',async()=>{
    const fixture=TestBed.createComponent(PublishSetupComponent);fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.setup-timeline > li').length).toBe(5);
    expect(calls.map(c=>c.action)).toEqual(['status']);
    expect(fixture.nativeElement.querySelector('.cloud-link')).toBeNull();
    (fixture.nativeElement.querySelector('.primary-button') as HTMLButtonElement).click();await fixture.whenStable();fixture.detectChanges();
    const page=fixture.componentInstance;
    await page.chooseOrganization('acme');fixture.detectChanges();
    await page.saveApplication();fixture.detectChanges();
    await page.connectGitHub();fixture.detectChanges();
    await page.saveDelivery();fixture.detectChanges();
    expect(page.deliveryReady()).toBeTrue();
    expect(fixture.nativeElement.querySelector('.setup-timeline > li:last-child').classList.contains('done')).toBeFalse();
    expect(fixture.nativeElement.textContent).toContain('Commit and push the workflow');
    expect(fixture.nativeElement.textContent).not.toContain('Healthy');
    expect(calls.some(c=>['review','deploy'].includes(c.action))).toBeFalse();
    expect(state.deployment).toBeNull();
    const changed=spyOn(page.pageChanged,'emit');
    (fixture.nativeElement.querySelector('.cloud-link') as HTMLButtonElement).click();
    expect(changed).toHaveBeenCalledWith('cloud/overview');
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
});
