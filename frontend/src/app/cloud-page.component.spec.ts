import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {CloudPageComponent} from './cloud-page.component';

describe('Fluxzero Cloud entry', () => {
  let fixture: ComponentFixture<CloudPageComponent>;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({imports:[CloudPageComponent],providers:[provideHttpClient(),provideHttpClientTesting()]});
    fixture=TestBed.createComponent(CloudPageComponent);http=TestBed.inject(HttpTestingController);
  });
  afterEach(()=>{fixture.destroy();http.verify();});
  it('keeps organization and plan unknown until authoritative Cloud context is available', () => {
    fixture.componentInstance.account.account.set({state:'signed_in',displayName:'Marty',dashboardUrl:'http://localhost:4200',accountUrl:'http://login.fluxzero.localhost:4300/account'});
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Organization & plan');
    expect(fixture.nativeElement.textContent).not.toContain('Free');
    expect(fixture.nativeElement.textContent).not.toContain('No organizations');
    const target:HTMLAnchorElement=fixture.nativeElement.querySelector('a.primary-button');
    expect(target.href).toBe('http://localhost:4200/');expect(target.rel).toContain('noopener');
    expect(fixture.nativeElement.textContent).toContain('Marty');
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
    expect(fixture.nativeElement.querySelector('a')).toBeNull();
  });
});
