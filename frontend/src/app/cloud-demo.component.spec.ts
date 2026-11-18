import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController,provideHttpClientTesting} from '@angular/common/http/testing';
import {CLOUD_DEMO_ENABLED,CloudConnection,CloudState} from './cloud-api';
import {AccountConnection} from './account-connection';
import {CloudDemoComponent} from './cloud-demo.component';

const state=():CloudState=>({version:1,mode:'demo',revision:1,scenario:'ready',scenarios:[],account:'signed_in',accountName:'Alex Morgan',availability:'available',organizations:[{id:'acme',name:'Acme Studio',plan:'Pro',applications:1,limit:3,role:'Owner'}],organizationId:'acme',plannedPlan:'',canDeploy:true,capacityAvailable:true,githubConnected:true,repositories:['acme/parcel-service'],repository:'acme/parcel-service',branch:'main',integration:'GitHub Actions',availableClusters:['Europe · shared-01'],cluster:'Europe · shared-01',application:'parcel-service',environment:'Production',proposal:null,deployment:null,domains:[],emailPreview:{},authTested:false,message:''});

describe('Cloud journey design fixture',()=>{
  let http:HttpTestingController;
  beforeEach(()=>{TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),{provide:CLOUD_DEMO_ENABLED,useValue:true}]});http=TestBed.inject(HttpTestingController);});
  afterEach(()=>http.verify());
  it('keeps simulated account actions away from native account and credential endpoints',async()=>{
    const account=TestBed.inject(AccountConnection);account.initialize();
    http.expectOne('cloud-demo/action').flush({...state(),account:'signed_out'});TestBed.tick();
    const signingIn=account.act('login');
    const request=http.expectOne('cloud-demo/action');expect(request.request.body).toEqual({action:'sign-in'});request.flush(state());await signingIn;TestBed.tick();
    expect(account.account()?.displayName).toBe('Alex Morgan');expect(account.signedIn()).toBeTrue();
    http.expectNone(request=>request.url.startsWith('account/'));
  });
  it('reviews before applying and cancelling a proposal never starts deployment',async()=>{
    const fixture=TestBed.createComponent(CloudDemoComponent);fixture.componentRef.setInput('projectName','Local draft');fixture.detectChanges();
    http.expectOne('cloud-demo/action').flush(state());await fixture.whenStable();fixture.detectChanges();
    (fixture.nativeElement.querySelector('.workspace-content .primary-button') as HTMLButtonElement).click();
    const review=http.expectOne('cloud-demo/action');expect(review.request.body).toEqual({action:'review'});
    review.flush({...state(),proposal:{id:'proposal-1',revision:2,expiresAt:9999999999,organization:'Acme Studio',plan:'Pro',cluster:'Europe',application:'parcel-service',environment:'Production',repository:'acme/parcel-service',branch:'main',commit:'4e72ab1',change:'Deploy application',planImpact:'No plan change'}});
    await fixture.whenStable();fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=dialog]')?.textContent).toContain('4e72ab1');
    (fixture.nativeElement.querySelector('.dialog-cancel') as HTMLButtonElement).click();fixture.detectChanges();
    http.expectNone(request=>request.body.action==='deploy');expect(fixture.nativeElement.querySelector('[role=dialog]')).toBeNull();fixture.destroy();
  });
  it('does no fixture I/O without the process opt-in',()=>{
    TestBed.resetTestingModule();TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting(),{provide:CLOUD_DEMO_ENABLED,useValue:false}]});
    http=TestBed.inject(HttpTestingController);const cloud=TestBed.inject(CloudConnection);cloud.initialize();
    http.expectNone(()=>true);expect(cloud.state()).toBeUndefined();
  });
});
