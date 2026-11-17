import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {CloudPageComponent, dashboardDestination} from './cloud-page.component';

describe('Fluxzero Cloud entry', () => {
  let fixture: ComponentFixture<CloudPageComponent>;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({imports:[CloudPageComponent],providers:[provideHttpClient(),provideHttpClientTesting()]});
    fixture=TestBed.createComponent(CloudPageComponent);http=TestBed.inject(HttpTestingController);
  });
  afterEach(()=>{fixture.destroy();http.verify();});
  it('keeps organization and plan unknown until authoritative Cloud context is available', () => {
    fixture.componentInstance.account.account.set({state:'signed_in',displayName:'Marty',dashboardUrl:'http://localhost:4200',accountUrl:'http://login.fluxzero.localhost:4300/account',message:'Sign-out cancelled.'});
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Organization & plan');
    expect(fixture.nativeElement.textContent).not.toContain('Free');
    expect(fixture.nativeElement.textContent).not.toContain('No organizations');
    const target:HTMLAnchorElement=fixture.nativeElement.querySelector('a.primary-button');
    expect(target.href).toBe('http://localhost:4200/');expect(target.rel).toContain('noopener');
    expect(fixture.nativeElement.textContent).toContain('Marty');
    expect(fixture.nativeElement.textContent).not.toContain('Sign-out cancelled.');
  });
  it('opens sign-in only after intent and keeps errors within Cloud', async () => {
    fixture.componentInstance.account.account.set({state:'signed_out'});fixture.detectChanges();
    http.expectNone('account/login');
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    http.expectOne('account/login').flush({error:{code:'offline',message:'Account services cannot be reached.'}});
    await fixture.whenStable();fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=status]').textContent).toContain('cannot be reached');
    expect(fixture.nativeElement.querySelector('.error-banner')).toBeNull();
  });
  it('offers retry instead of an endless loading state when account configuration fails', () => {
    fixture.componentInstance.account.account.set({state:'unavailable'});
    fixture.componentInstance.account.message.set('Account features are unavailable.');fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Try again');
    expect(fixture.nativeElement.textContent).not.toContain('Loading account');
    http.expectNone('account/login');
  });
  it('rejects unsafe links and changes sections without fetching or inventing resources', () => {
    fixture.componentInstance.account.account.set({state:'signed_in',dashboardUrl:'javascript:alert(1)'});
    fixture.componentRef.setInput('section','clusters');fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Clusters');
    expect(fixture.nativeElement.querySelector('a[target="_blank"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Dashboard is unavailable');
  });
  it('keeps project context local and opens only existing Dashboard entry routes', () => {
    fixture.componentInstance.account.account.set({state:'signed_in',displayName:'Marty',dashboardUrl:'http://localhost:4200'});
    fixture.componentRef.setInput('projectName','Local café draft');fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.local-project').textContent).toContain('Local café draft');
    const changed=jasmine.createSpy('pageChanged');fixture.componentInstance.pageChanged.subscribe(changed);
    (fixture.nativeElement.querySelector('.journey-card a[href="#cloud/integrations"]') as HTMLAnchorElement).click();
    expect(changed).toHaveBeenCalledOnceWith('cloud/integrations');
    fixture.componentRef.setInput('section','integrations');fixture.detectChanges();
    const targets=Array.from(fixture.nativeElement.querySelectorAll('a[target="_blank"]')) as HTMLAnchorElement[];
    expect(targets.map(a=>a.href)).toContain('http://localhost:4200/github/organisations');
    expect(targets.every(a=>!a.href.includes('draft') && !new URL(a.href).search && a.rel.includes('noopener'))).toBeTrue();
    (fixture.nativeElement.querySelector('.local-project a') as HTMLAnchorElement).click();
    expect(changed.calls.mostRecent().args).toEqual(['application']);
    http.expectNone(()=>true);
  });
  it('offers explicit secure-storage recovery without opening it on render', async () => {
    fixture.componentInstance.account.account.set({state:'signed_in',issue:'storage_locked',message:'Unlock your saved sign-in.'});fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h2').textContent).toBe('Unlock secure storage');
    http.expectNone(()=>true);
    (fixture.nativeElement.querySelector('.recovery button') as HTMLButtonElement).click();fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.recovery button').textContent).toContain('Waiting for access');
    expect(fixture.nativeElement.querySelector('.recovery button').disabled).toBeTrue();
    http.expectOne('account/check-interactive').flush({account:{state:'signed_in',displayName:'Marty'}});
    await fixture.whenStable();fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.organization-card')).not.toBeNull();
    http.expectNone('account/login');
  });
  it('asks for a fresh sign-in only after intent when authorization is no longer valid', async () => {
    fixture.componentInstance.account.account.set({state:'sign_in_required'});fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h2').textContent).toBe('Sign in again');
    http.expectNone(()=>true);
    (fixture.nativeElement.querySelector('.recovery button') as HTMLButtonElement).click();
    http.expectOne('account/login').flush({account:{state:'signed_out'}});
    await fixture.whenStable();
  });
  it('shows browser confirmation progress while keeping a route back to local work', () => {
    fixture.componentInstance.account.account.set({state:'signed_in',pendingAction:'sign_out_everywhere'});
    fixture.componentRef.setInput('projectName','Local draft');fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.progress-label[role="status"]').textContent).toContain('Confirm sign-out');
    expect(fixture.nativeElement.querySelector('.spinner-border')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.local-project a').getAttribute('href')).toBe('#application');
    expect(fixture.nativeElement.textContent).not.toContain('Check sign-out');
    expect(fixture.nativeElement.textContent).not.toContain('Stop waiting');
    http.expectNone(()=>true);
  });
  it('preserves a configured base path while excluding arbitrary query and fragment values', () => {
    expect(dashboardDestination('https://dashboard.example/console?returnTo=private#draft','github')).toBe('https://dashboard.example/console/github/organisations');
    expect(dashboardDestination('https://dashboard.example/console/')).toBe('https://dashboard.example/console/');
    for(const base of ['javascript:alert(1)','http://dashboard.example','https://user:password@dashboard.example'])
      expect(dashboardDestination(base,'github')).toBeUndefined();
  });
});
