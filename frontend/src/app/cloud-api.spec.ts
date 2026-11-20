import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {AccountConnection} from './account-connection';
import {CLOUD_API, CloudCommand, CloudConnection, CloudState, UnconnectedCloudApi} from './cloud-api';
import {CloudContextComponent} from './cloud-context.component';
import {DemoCloudApi} from './cloud-demo-api';
import {PublishSetupComponent} from './publish-setup.component';

describe('Cloud adapter contract',()=>{
  let state:CloudState,calls:CloudCommand[],failSelection:boolean;
  beforeEach(async()=>{
    state={...await new UnconnectedCloudApi().call({action:'status'}),availability:'available',organizationId:'one',capabilities:{actions:['select-organization']},organizations:[
      {id:'one',name:'First team',plan:'Starter',applications:1,limit:2,role:'Owner'},
      {id:'two',name:'Second team',plan:'Pro',applications:1,limit:3,role:'Owner'}]};
    calls=[];failSelection=false;
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),{provide:CLOUD_API,useValue:{call:async(command:CloudCommand)=>{
      calls.push(command);
      if(command.action==='select-organization'){
        if(failSelection)throw Error('Unavailable');
        state={...state,organizationId:command['organizationId']};
      }
      return state;
    }}}]});
  });
  it('accepts provider data without scenario or demo fields and rejects unsupported actions',async()=>{
    const cloud=TestBed.inject(CloudConnection);
    await cloud.call({action:'status'});
    expect(cloud.state()?.organizationId).toBe('one');
    expect(cloud.supports('select-organization')).toBeTrue();
    await cloud.call({action:'configure-application',application:'unapproved'});
    expect(calls.map(command=>command.action)).toEqual(['status']);
    expect(cloud.error()).toContain('not available');
    TestBed.inject(HttpTestingController).expectNone(()=>true);
  });
  it('renders plan first and selects authorized organization context without account calls',async()=>{
    TestBed.inject(AccountConnection).account.set({state:'signed_in'});
    const fixture=TestBed.createComponent(CloudContextComponent);fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    const root:HTMLElement=fixture.nativeElement;
    expect(root.querySelector('.cloud-context')?.firstElementChild?.classList.contains('plan-badge')).toBeTrue();
    expect(root.querySelector('.organization-name')?.textContent).toBe('First team');
    const select=root.querySelector('select')!;
    select.value='two';select.dispatchEvent(new Event('change'));await fixture.whenStable();fixture.detectChanges();
    expect(root.querySelector('.plan-badge')?.textContent).toContain('Pro');
    expect(root.querySelector('.organization-name')?.textContent).toBe('Second team');
    expect(select.value).toBe('two');
    TestBed.inject(HttpTestingController).expectNone(()=>true);
    fixture.destroy();
  });
  it('keeps confirmed context on failure and omits the selector for a single organization',async()=>{
    TestBed.inject(AccountConnection).account.set({state:'signed_in'});
    const fixture=TestBed.createComponent(CloudContextComponent);fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    failSelection=true;
    const select=fixture.nativeElement.querySelector('select') as HTMLSelectElement;
    select.value='two';select.dispatchEvent(new Event('change'));await fixture.whenStable();fixture.detectChanges();
    expect(select.value).toBe('one');
    expect(fixture.nativeElement.querySelector('.organization-name').textContent).toBe('First team');
    TestBed.inject(CloudConnection).state.set({...state,organizations:[state.organizations[0]]});fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('select')).toBeNull();
    fixture.destroy();
  });
  it('leaves unconnected resources unknown instead of supplying example data',async()=>{
    const snapshot=await new UnconnectedCloudApi().call({action:'status'});
    expect(snapshot.availability).toBe('not_connected');
    expect(snapshot.organizations).toEqual([]);
    expect(snapshot.deployment).toBeNull();
    expect(snapshot.capabilities?.actions).toEqual([]);
  });
  it('does not offer publishing edits that a read-only adapter has not advertised',async()=>{
    state={...state,application:'service-a',cluster:'cluster-a',setup:{applicationConfigured:true,size:'Known size',pipelineConfigured:true,workflowPath:'deploy.yml',workflowStatus:'not_checked'}};
    const fixture=TestBed.createComponent(PublishSetupComponent);fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('account/status').flush({account:{state:'signed_in'}});
    await fixture.whenStable();fixture.detectChanges();
    expect(fixture.componentInstance.deliveryReady()).toBeTrue();
    expect(fixture.nativeElement.querySelector('.setup-timeline .text-button')).toBeNull();
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(calls.map(command=>command.action)).toEqual(['status']);
    fixture.destroy();
  });
  it('does not let a delayed fixture status replace a newer account result',async()=>{
    const api=TestBed.inject(DemoCloudApi),http=TestBed.inject(HttpTestingController);
    const old=api.call({action:'status'}),current=api.call({action:'sign-in'});
    const requests=http.match('cloud-demo/action');
    const raw={...state,mode:'demo',scenario:'local',scenarios:[],accountName:'Fixture user'};
    requests[1].flush({...raw,account:'signed_in'});await current;
    requests[0].flush({...raw,account:'signed_out'});await old;
    expect(api.session()?.account).toBe('signed_in');
    http.verify();
  });
});
