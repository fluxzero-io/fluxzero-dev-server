import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {AccountMenuComponent} from './account-menu.component';

describe('Optional Fluxzero account', () => {
  let fixture: ComponentFixture<AccountMenuComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({imports: [AccountMenuComponent], providers: [provideHttpClient(), provideHttpClientTesting()]});
    fixture = TestBed.createComponent(AccountMenuComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('account/status').flush({account: {state: 'signed_out'}});
    await fixture.whenStable();
  });
  afterEach(() => { fixture.destroy(); http.verify(); });

  async function open() {
    fixture.componentInstance.toggle();
    http.expectOne('account/status').flush({account: {state: 'signed_out'}});
    await fixture.whenStable(); fixture.detectChanges();
  }

  it('offers quiet sign-in and does not start login on opening', async () => {
    await open();
    expect(fixture.nativeElement.textContent).not.toContain('Your Fluxzero account');
    expect(fixture.nativeElement.textContent).not.toContain('Local development is always available');
    expect(fixture.nativeElement.querySelector('.account-trigger').textContent.trim()).toBe('');
    expect(fixture.nativeElement.querySelector('.account-trigger .bi-person')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.account-trigger').title).toBe('Sign in to Fluxzero');
    expect(fixture.nativeElement.textContent).toContain('Sign in');
    http.expectNone('account/login');
    http.expectNone('account/refresh');
  });

  it('keeps account failures inside the account panel', async () => {
    await open();
    const action = fixture.componentInstance.act('login');
    const request = http.expectOne('account/login');
    expect(request.request.headers.get('X-Fluxzero-Console')).toBe('1');
    expect(request.request.body).toEqual({returnTo:location.origin+location.pathname+location.hash});
    request.flush({error: {code: 'offline', message: 'Cloud cannot be reached. You can keep working locally.'}});
    await action; fixture.detectChanges();
    expect(fixture.componentInstance.open()).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('You can keep working locally');
    expect(fixture.nativeElement.querySelector('.error-banner')).toBeNull();
  });

  it('shows the shared identity and makes logout scope clear', async () => {
    await open();
    fixture.componentInstance.account.set({state: 'signed_in', displayName: 'Marty', email: 'marty@example.test', accountUrl: 'https://login.example.test/account', dashboardUrl:'https://cloud.example.test'});
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.profile-actions .switch-account').getAttribute('aria-label')).toBe('Use another account');
    expect(fixture.nativeElement.querySelector('a[href="https://cloud.example.test/"]')?.textContent).toContain('Dashboard');
    expect(fixture.nativeElement.textContent).toContain('Marty');
    expect(fixture.nativeElement.querySelector('.account-trigger .bi-person-fill')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.account-trigger').getAttribute('aria-label')).toContain('Marty');
    expect(fixture.nativeElement.textContent).toContain('Profile');
    expect(fixture.nativeElement.textContent).toContain('Sign out on this computer');
    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('a');
    expect(link.rel).toContain('noopener');
    fixture.componentInstance.account.set({state: 'signed_in', accountUrl: 'javascript:alert(1)'});
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('a')).toBeNull();
  });

  it('supports menu navigation and closes on Tab without invoking an action', async () => {
    await open();
    fixture.componentInstance.account.set({state: 'signed_in', displayName: 'Marty', accountUrl: 'https://login.example.test/account'});
    fixture.detectChanges();
    const items: HTMLElement[] = Array.from(fixture.nativeElement.querySelectorAll('[role="menuitem"]'));
    items[0].focus();
    fixture.componentInstance.onKeydown(new KeyboardEvent('keydown', {key: 'ArrowDown'}));
    expect(document.activeElement).toBe(items[1]);
    fixture.componentInstance.onKeydown(new KeyboardEvent('keydown', {key: 'Home'}));
    expect(document.activeElement).toBe(items[0]);
    fixture.componentInstance.onKeydown(new KeyboardEvent('keydown', {key: 'End'}));
    expect(document.activeElement).toBe(items[items.length - 1]);
    fixture.componentInstance.onKeydown(new KeyboardEvent('keydown', {key: 'Tab'}));
    expect(fixture.componentInstance.open()).toBeFalse();
    http.expectNone('account/login');http.expectNone('account/logout');
  });

  it('keeps a remembered account filled during a Cloud failure and opens storage only on intent', async () => {
    await open();
    fixture.componentInstance.account.set({state: 'signed_in', displayName: 'Marty'});
    fixture.detectChanges();
    const action = fixture.componentInstance.act('refresh');
    http.expectOne('account/refresh').flush({error: {code: 'storage_locked', message: 'Unlock secure storage to continue.'}});
    await action;fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.bi-person-fill')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Unlock secure storage');
    expect(fixture.componentInstance.signedIn()).toBeTrue();
    http.expectNone('account/login');
  });

  it('offers explicit secure storage recovery from shared background status', async () => {
    await open();
    const loading=fixture.componentInstance.connection.load();
    http.expectOne('account/status').flush({account:{state:'signed_in',issue:'storage_locked',message:'Unlock secure storage to finish signing out.'}});
    await loading;fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Unlock secure storage');
    const action=fixture.componentInstance.act('check-interactive');
    http.expectOne('account/check-interactive').flush({account:{state:'signed_out'}});
    await action;fixture.detectChanges();
    expect(fixture.componentInstance.signedIn()).toBeFalse();
  });

  it('keeps global sign-out behind its own button and preserves the account while the browser confirms', async () => {
    await open();
    fixture.componentInstance.account.set({state: 'signed_in', displayName: 'Marty'});
    fixture.detectChanges();
    const more: HTMLButtonElement = fixture.nativeElement.querySelector('.more-signout');
    more.dispatchEvent(new PointerEvent('pointerenter',{pointerType:'mouse'}));fixture.detectChanges();
    expect(document.activeElement).not.toBe(fixture.nativeElement.querySelector('#more-signout-menu button'));
    more.click();fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Sign out everywhere…');
    http.expectNone('account/logout');http.expectNone('account/logout-everywhere');
    fixture.componentInstance.onKeydown(new KeyboardEvent('keydown', {key:'Escape'}));fixture.detectChanges();
    expect(fixture.componentInstance.open()).toBeTrue();
    expect(document.activeElement).toBe(more);
    more.click();fixture.detectChanges();
    const action=fixture.componentInstance.act('logout-everywhere');
    http.expectOne('account/logout-everywhere').flush({account:{state:'signed_in',displayName:'Marty',pendingAction:'sign_out_everywhere',message:'Sign-out cancelled.'}});
    await action;fixture.detectChanges();
    expect(fixture.componentInstance.signedIn()).toBeTrue();
    expect(fixture.nativeElement.textContent).toContain('Confirm sign-out in your browser');
    expect(fixture.nativeElement.textContent).not.toContain('Check sign-out');
    expect(fixture.nativeElement.textContent).not.toContain('Stop waiting');
    expect(fixture.nativeElement.textContent).not.toContain('Sign-out cancelled.');
    expect(fixture.nativeElement.querySelector('[role=status] .spinner-border')).not.toBeNull();
    http.expectNone('account/logout');
    const cancel=fixture.componentInstance.act('cancel-browser');
    http.expectOne('account/cancel-browser').flush({account:{state:'signed_in',displayName:'Marty'}});
    await cancel;fixture.detectChanges();
    expect(fixture.componentInstance.signedIn()).toBeTrue();
    expect(fixture.nativeElement.textContent).not.toContain('Check sign-out');
  });

  it('observes explicit browser completion without a manual check or background credential access', async () => {
    await open();
    fixture.componentInstance.account.set({state:'signed_in',displayName:'Marty',pendingAction:'sign_out_everywhere'});
    const returned=fixture.componentInstance.connection.load(true);
    http.expectOne('account/status').flush({account:{state:'signed_in',displayName:'Marty',pendingAction:'sign_out_everywhere'}});
    await returned;
    http.expectNone('account/check');
    const completed=fixture.componentInstance.connection.load();
    http.expectOne('account/status').flush({account:{state:'signed_out'}});
    await completed;fixture.detectChanges();
    expect(fixture.componentInstance.signedIn()).toBeFalse();
    expect(fixture.nativeElement.querySelector('.bi-person')).not.toBeNull();
    http.expectNone('account/logout');
  });

  it('closes with Escape and restores focus without signing out', async () => {
    await open();
    fixture.componentInstance.onKeydown(new KeyboardEvent('keydown', {key: 'Escape'}));
    fixture.detectChanges();
    expect(fixture.componentInstance.open()).toBeFalse();
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('.account-trigger'));
    http.expectNone('account/logout');
  });
});
