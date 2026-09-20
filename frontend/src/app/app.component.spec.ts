import {TestBed, ComponentFixture} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting, HttpTestingController} from '@angular/common/http/testing';
import {ProjectManagerComponent} from './project-manager.component';
import {AppComponent} from './app.component';
import {monitoringPath, environmentConsoleUrl, Status, Environment} from './models';
import {ConsoleConnection, ConsoleState} from './console-connection';
import {By} from '@angular/platform-browser';
import {EnvironmentComponent} from './environment.component';

describe('Dev console navigation', () => {
  let fixture: ComponentFixture<AppComponent>;
  let push: (state: ConsoleState) => void;
  let connected: (value: boolean) => void;
  const originalLocation = location.href;
  const originalConfirmation = localStorage.getItem('devConfirmTruncate');
  const originalRestartScope = localStorage.getItem('devRestartScope');
  const originalTheme = localStorage.getItem('dashboardTheme');
  const navigationKeys=['devboard.monitoringExpanded','devboard.sidebarCollapsed','devboard.sidebarWidth'];
  const navigationPreferences=navigationKeys.map(key=>localStorage.getItem(key));
  beforeEach(async () => {
    localStorage.removeItem('devRestartScope');
    navigationKeys.forEach(key=>localStorage.removeItem(key));
    // A legacy opt-out must never suppress confirmation, even after startup.
    localStorage.setItem('devConfirmTruncate', 'false');
    history.replaceState(null, '', location.pathname + '#projects');
    TestBed.configureTestingModule({imports: [AppComponent], providers: [provideHttpClient(), provideHttpClientTesting(),
      {provide: ConsoleConnection, useValue: {initialise: (update: typeof push, connection: typeof connected) => {push = update; connected = connection;}, close: () => {}}}]});
    fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const status: Status = {resourceHistory: [], project: 'repair-cafe', projectDirectory: '/projects/repair-cafe', state: 'running',
      runtime: 'running', applications: 'running', tests: 'passed', frontend: 'stopped', monitoring: {enabled: false},
      maintenance: {busy: false, stopSupported: true, resetSupported: true, restartSupported: true, applicationRestartSupported: true, error: ''},
      components: [{id: 'devserver', name: 'Fluxzero Dev Server', state: 'running', memoryBytes: 20971520, application: false, port: 4200, url: 'http://localhost:4200/_fluxzero/dev/#projects'}, {id: 'testserver', name: 'Fluxzero Test Server', state: 'running', memoryBytes: 104857600, application: false},
        {id: 'app', name: 'Repair Café', state: 'running', memoryBytes: 52428800, application: true, port: 4200, url: 'http://localhost:4200/'}],
      testResults: {available: true, passed: 8, failed: 2, skipped: 1, total: 11, label: 'Latest completed run per module'}};
    const environments: Environment[] = [
      {id: 'a'.repeat(64), directoryExists: true, projectName: 'repair-cafe', projectDirectory: '/projects/repair-cafe', status: 'running', port: 4200, consoleUrl: 'http://localhost:4200/_fluxzero/dev/'},
      {id: 'b'.repeat(64), directoryExists: false, projectName: 'orders', projectDirectory: '/projects/orders', status: 'stopped', port: 4300, consoleUrl: null}
    ];
    push({status, environments});
    connected(true);
    await fixture.whenStable();
    fixture.detectChanges();
    http.verify();
  });
  afterEach(() => {
    fixture.destroy();
    navigationKeys.forEach((key,index)=>{const value=navigationPreferences[index];if(value===null) localStorage.removeItem(key);else localStorage.setItem(key,value);});
    if (originalRestartScope == null) localStorage.removeItem('devRestartScope');
    else localStorage.setItem('devRestartScope', originalRestartScope);
    if (originalConfirmation == null) localStorage.removeItem('devConfirmTruncate');
    else localStorage.setItem('devConfirmTruncate', originalConfirmation);
    history.replaceState(null, '', originalLocation);
    if (originalTheme == null) localStorage.removeItem('dashboardTheme');
    else localStorage.setItem('dashboardTheme', originalTheme);
  });
  it('recognizes the current project through its dev-server port when directory aliases differ', () => {
    const app = fixture.componentInstance;
    const status = app.status()!;
    push({status: {...status, projectDirectory: '/aliased/repair-cafe'}, environments: app.environments()});
    fixture.detectChanges();
    expect(app.current()?.projectName).toBe('repair-cafe');
    const rows = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.project-row'));
    const current = rows.find(row => row.textContent?.includes('repair-cafe'))!;
    expect(current.querySelector('[aria-label="Switch to project"]')).toBeNull();
    expect(current.querySelector('[aria-label="Stop project"]')).not.toBeNull();
    expect(rows.find(row => row.textContent?.includes('orders'))?.querySelector('[aria-label="Switch to project"]')).not.toBeNull();
  });

  it('stops and resumes the current workspace in project management without shutting down its dashboard', async () => {
    const app=fixture.componentInstance, http=TestBed.inject(HttpTestingController);
    const manager=fixture.debugElement.query(By.directive(ProjectManagerComponent)).componentInstance as ProjectManagerComponent;
    const project=app.environments()[0];
    manager.requestStop(project);fixture.detectChanges();
    expect(manager.stopping()).toEqual(project);
    http.expectNone('actions/stop-workspace');
    const stopping=manager.stopProject(project);
    http.expectOne('actions/stop-workspace').flush(null);
    await Promise.resolve();await Promise.resolve();
    http.match('environments.json').forEach(r=>r.flush({environments:app.environments()}));
    await stopping;
    push({status:{...app.status()!,projectDirectory:'/alias/repair-cafe',components:[],state:'idle',maintenance:{busy:false,error:'',workspaceStopped:true}},environments:app.environments().map(p=>p.id===project.id ? {...p,port:Number(location.port)} : p)});fixture.detectChanges();
    const row=(fixture.nativeElement as HTMLElement).querySelector('.project-row')!;
    expect(row.textContent).toContain('Stopped');
    expect(row.querySelector('[aria-label="Start project"]')).not.toBeNull();
    expect((row.querySelector('[aria-label="Remove from list"]') as HTMLButtonElement).disabled).toBeTrue();
    const starting=manager.startProject(project);
    http.expectOne('actions/start-workspace').flush(null);await starting;
    http.expectNone('actions/stop-devserver');
    http.verify();
  });

  it('confirms an available update before sending its exact version', async () => {
    const root=fixture.nativeElement as HTMLElement;
    expect(root.querySelector('dev-server-update .update-button')).toBeNull();
    const status=fixture.componentInstance.status()!;
    push({status:{...status, update:{status:'available',latestVersion:'1.99.0'}},environments:[]});
    fixture.detectChanges();
    (root.querySelector('dev-server-update .update-button') as HTMLButtonElement).click(); fixture.detectChanges();
    const dialog=root.querySelector('dev-server-update dialog') as HTMLDialogElement;
    expect(dialog.open).toBeTrue(); expect(dialog.textContent).toContain('resets local app data');
    TestBed.inject(HttpTestingController).expectNone('actions/update-devserver');
    (dialog.querySelector('.dialog-cancel') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(dialog.open).toBeFalse();
    (root.querySelector('dev-server-update .update-button') as HTMLButtonElement).click(); fixture.detectChanges();
    (dialog.querySelector('.primary-button') as HTMLButtonElement).click(); fixture.detectChanges();
    const request=TestBed.inject(HttpTestingController).expectOne('actions/update-devserver');
    expect(request.request.body).toEqual({version:'1.99.0'});
    expect(request.request.headers.get('X-Fluxzero-Console')).toBe('1');
    request.flush({}); await fixture.whenStable(); fixture.detectChanges();
    expect(root.querySelector('dev-server-update .update-button')?.textContent).toContain('Updating');
    connected(false);
    push({status:{...status,versions:{devServer:'1.99.0'},update:{status:'current'}},environments:[]});
    fixture.detectChanges();
    expect(root.querySelector('.update-notice')).toBeNull();
    connected(true);
    fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
    expect(root.querySelector('dev-server-update .update-button')).toBeNull();
    expect(root.querySelector('.update-notice')?.textContent).toContain('Devboard updated to 1.99.0');
    (root.querySelector('.update-notice button') as HTMLButtonElement).click();fixture.detectChanges();
    push({status:{...status,versions:{devServer:'1.99.0'},update:{status:'current'}},environments:[]});fixture.detectChanges();
    expect(root.querySelector('.update-notice')).toBeNull();
  });

  it('allows retrying after an earlier update failure and shows a new failure', async () => {
    const root=fixture.nativeElement as HTMLElement, status=fixture.componentInstance.status()!;
    const failed={...status,update:{status:'available',latestVersion:'1.99.0',attemptId:'first',error:'Previous version restored'}};
    push({status:failed,environments:[]}); fixture.detectChanges();
    (root.querySelector('dev-server-update .update-button') as HTMLButtonElement).click();fixture.detectChanges();
    (root.querySelector('dev-server-update .primary-button') as HTMLButtonElement).click();fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('actions/update-devserver').flush({});
    await fixture.whenStable();fixture.detectChanges();
    expect(root.querySelector('dev-server-update .update-button')?.textContent).toContain('Updating');
    expect(root.querySelector('.update-notice')).toBeNull();
    // Intermediate busy/cleared-error snapshots can be coalesced by the browser.
    push({status:{...failed,update:{...failed.update,attemptId:'second'}},environments:[]});fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    expect(root.querySelector('dev-server-update .update-button')?.textContent).toContain('Update available');
    expect(root.querySelector('dev-server-update [role="alert"]')?.textContent).toContain('Previous version restored');
  });

  function openTests() {
    fixture.componentInstance.navigate('tests');fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne(r => r.url === 'tests.json')
      .flush({items:[],total:0,offset:0,pageSize:50,counts:{passed:8,failed:2,skipped:1,pending:0}});
    fixture.detectChanges();
  }
  function openOutput() {
    (fixture.nativeElement.querySelector('.output-tab') as HTMLButtonElement).click();fixture.detectChanges();
  }
  function openPicker() {
    (fixture.nativeElement.querySelector('[aria-label="Choose workspace"]') as HTMLButtonElement).click();
    fixture.detectChanges();
  }
  it('keeps versions out of the workspace heading and shows current problem badges, clearing them on recovery and disconnect', () => {
    const component = fixture.componentInstance;
    const root: HTMLElement = fixture.nativeElement;
    component.status.update(s => ({...s!, versions:{devServer:'1.2.3',fluxzero:'2.0.0-RC1'}, workspaceIssue:''}));
    fixture.detectChanges();
    expect(root.querySelector('.workspace-versions')).toBeNull();
    expect(root.querySelector('.workspace-issue')).toBeNull();
    expect(root.querySelector('a[href="#projects"] .nav-issue-badge')).toBeNull();
    expect(root.querySelector('a[href="#tests"] .nav-result-badge.failed')?.textContent).toBe('2');
    component.status.update(s => ({...s!, workspaceIssue:'Your app could not start.'}));fixture.detectChanges();
    expect(root.querySelector('.workspace-issue')?.textContent).toContain('Your app could not start.');
    expect(root.querySelector('a[href="#projects"] .nav-issue-badge')?.getAttribute('aria-label')).toBe('Workspace needs attention');
    connected(false);fixture.detectChanges();expect(root.querySelector('.nav-issue-badge')).toBeNull();
    component.status.update(s => ({...s!, workspaceIssue:'',testResults:{...s!.testResults!,failed:0}}));
    connected(true);fixture.detectChanges();
    expect(root.querySelector('.workspace-issue')).toBeNull();expect(root.querySelector('.nav-issue-badge')).toBeNull();
  });
  it('navigates to Startup data below Tests and replaces green result counters with only failures', () => {
    const component=fixture.componentInstance;
    const root:HTMLElement=fixture.nativeElement;
    const actions=[{id:'one',name:'Create home',state:'succeeded'},{id:'two',name:'Add floor',state:'succeeded'}];
    component.status.update(s=>({...s!,testResults:{...s!.testResults!,failed:0},startup:{state:'succeeded',actions}}));fixture.detectChanges();
    expect(root.querySelector('a[href="#tests"] .nav-result-badge:not(.failed)')?.textContent).toBe('8');
    expect(root.querySelector('a[href="#startup"] .nav-result-badge:not(.failed)')?.textContent).toBe('2');
    expect(root.querySelector('a[href="#tests"]')?.nextElementSibling?.getAttribute('href')).toBe('#startup');
    expect(root.querySelector('dev-startup')).toBeNull();
    (root.querySelector('a[href="#startup"]') as HTMLAnchorElement).click();fixture.detectChanges();
    expect(component.route()).toBe('startup');
    expect(root.querySelector('h1')?.textContent).toBe('Startup commands');
    expect(root.querySelectorAll('.startup-row').length).toBe(2);
    component.readRoute();expect(component.route()).toBe('startup');
    component.status.update(s=>({...s!,testResults:{...s!.testResults!,failed:3},startup:{state:'failed',actions:[actions[0],{...actions[1],state:'failed'}]}}));fixture.detectChanges();
    expect(root.querySelector('a[href="#tests"] .nav-result-badge.failed')?.textContent).toBe('3');
    expect(root.querySelector('a[href="#startup"] .nav-result-badge.failed')?.textContent).toBe('1');
    expect(root.querySelector('.nav-result-badge:not(.failed)')).toBeNull();
    connected(false);fixture.detectChanges();expect(root.querySelector('.nav-result-badge')).toBeNull();
    connected(true);
    component.status.update(s=>({...s!,maintenance:{...s!.maintenance!,workspaceStopped:true}}));fixture.detectChanges();
    expect(root.querySelector('.nav-result-badge')).toBeNull();
    expect(root.querySelector('.workspace-stopped')?.textContent).toContain('Workspace stopped');
    component.status.update(s=>({...s!,maintenance:{...s!.maintenance!,workspaceStopped:false},testResults:undefined,startup:{state:'idle',actions:[]}}));fixture.detectChanges();
    expect(root.querySelector('.nav-result-badge')).toBeNull();
    expect(root.querySelector('dev-startup-page')?.textContent).toContain('No startup commands configured');
  });
  it('confirms a profile switch, keeps the active label until reconnect, and refreshes the preview', async () => {
    const component = fixture.componentInstance;
    component.status.update(s => ({...s!, profiles: {active: 'local', available: ['local', 'demo'], switchSupported: true}}));
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const select = root.querySelector('.profile-picker-button') as HTMLButtonElement;
    const choose = () => {select.click(); fixture.detectChanges(); (root.querySelector('.profile-option:not(.active)') as HTMLButtonElement).click(); fixture.detectChanges();};
    choose();
    const dialog = root.querySelector('[aria-labelledby="profile-title"]') as HTMLDialogElement;
    expect(dialog.open).toBeTrue();
    expect(select.textContent).toContain('local');
    const http = TestBed.inject(HttpTestingController);
    http.expectNone('actions/switch-profile');
    (dialog.querySelector('.secondary-button') as HTMLButtonElement).click();
    expect(dialog.open).toBeFalse();
    choose();
    const refresh = spyOn(component, 'refreshApplication');
    (dialog.querySelector('.primary-button') as HTMLButtonElement).click(); fixture.detectChanges();
    const request = http.expectOne('actions/switch-profile');
    expect(request.request.body).toEqual({profile:'demo'});
    expect(request.request.headers.get('X-Fluxzero-Console')).toBe('1');
    request.flush({accepted:true}); await fixture.whenStable(); fixture.detectChanges();
    expect(select.disabled).toBeTrue();
    expect(root.textContent).toContain('Switching to demo');
    push({status: {...component.status()!, profiles: {active:'demo', available:['local','demo'], switchSupported:true}}, environments: component.environments()});
    await fixture.whenStable(); fixture.detectChanges();
    expect(select.textContent).toContain('demo'); expect(select.disabled).toBeFalse();
    expect(refresh).toHaveBeenCalledTimes(1);
    http.verify();
  });
  it('shows profile errors and retains the active choice when switching is rejected', async () => {
    const component = fixture.componentInstance;
    component.status.update(s => ({...s!, profiles: {active:'local', available:['local','demo'], switchSupported:true}}));
    fixture.detectChanges();
    const select = fixture.nativeElement.querySelector('.profile-picker-button') as HTMLButtonElement;
    select.click(); fixture.detectChanges();
    fixture.nativeElement.querySelector('.profile-option:not(.active)').click(); fixture.detectChanges();
    fixture.nativeElement.querySelector('[aria-labelledby="profile-title"] .primary-button').click();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('actions/switch-profile').flush({error:'Profile is invalid'}, {status:409,statusText:'Conflict'});
    await fixture.whenStable(); fixture.detectChanges();
    expect(select.textContent).toContain('local'); expect(select.disabled).toBeFalse();
    expect(fixture.nativeElement.textContent).toContain('Profile is invalid');
    connected(false); fixture.detectChanges(); expect(select.disabled).toBeTrue();
    http.verify();
  });
  it('marks the active profile and supports keyboard navigation and Escape without switching', () => {
    fixture.componentInstance.status.update(s => ({...s!, profiles: {active:'local', available:['local','demo'], switchSupported:true}}));
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const trigger = root.querySelector('.profile-picker-button') as HTMLButtonElement;
    trigger.click(); fixture.detectChanges();
    const options = root.querySelectorAll<HTMLButtonElement>('.profile-option');
    expect(options[0].getAttribute('aria-checked')).toBe('true');
    expect(document.activeElement).toBe(options[0]);
    options[0].dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowDown',bubbles:true}));
    expect(document.activeElement).toBe(options[1]);
    options[1].dispatchEvent(new KeyboardEvent('keydown', {key:'Escape',bubbles:true}));
    fixture.detectChanges();
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(trigger);
    TestBed.inject(HttpTestingController).expectNone('actions/switch-profile');
  });
  it('restores Monitoring and pinned navigation choices after recreating the UI', () => {
    const app=fixture.componentInstance;
    expect(app.monitoringExpanded()).toBeTrue();
    app.toggleMonitoring();app.togglePinnedNavigation();
    app.resizeNavigationWithKeyboard(new KeyboardEvent('keydown',{key:'End'}));
    const restored=TestBed.createComponent(AppComponent);
    expect(restored.componentInstance.monitoringExpanded()).toBeFalse();
    expect(restored.componentInstance.sidebarCollapsed()).toBeTrue();
    expect(restored.componentInstance.sidebarWidth()).toBe(520);
    history.replaceState(null,'','#monitoring/logs');
    restored.componentInstance.readRoute();
    expect(restored.componentInstance.monitoringExpanded()).toBeFalse();
    restored.destroy();
  });
  it('opens a temporary drawer without changing the saved pinned state and closes on Escape', () => {
    const app=fixture.componentInstance;
    app.togglePinnedNavigation();app.openNavigation();fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('main').hasAttribute('inert')).toBeTrue();
    expect(fixture.nativeElement.querySelector('aside').hasAttribute('inert')).toBeFalse();
    app.navigationKeyboard(new KeyboardEvent('keydown',{key:'Escape'}));fixture.detectChanges();
    expect(app.menuOpen()).toBeFalse();expect(app.sidebarCollapsed()).toBeTrue();
    expect(localStorage.getItem('devboard.sidebarCollapsed')).toBe('true');
    expect(fixture.nativeElement.querySelector('aside').hasAttribute('inert')).toBeTrue();
  });
  it('opens App preview by default and from the home link while honoring explicit environment bookmarks', () => {
    history.replaceState(null, '', location.pathname);
    fixture.componentInstance.readRoute(); fixture.detectChanges();
    expect(fixture.componentInstance.route()).toBe('application');
    expect(fixture.nativeElement.querySelector('.application-heading').getAttribute('aria-label')).toBe('Preview navigation');
    fixture.componentInstance.navigate('projects'); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Workspace');
    fixture.nativeElement.querySelector('.brand-home').click(); fixture.detectChanges();
    expect(location.hash).toBe('#application');
  });
  it('shows completion percentage in Progress navigation and keeps history when the workspace is stopped', () => {
    const state=fixture.componentInstance.status()!;
    const feature={id:'f',title:'Customers can book',kind:'feature' as const,status:'done' as const,description:'',acceptance:[],createdAt:'2026-09-19T12:00:00Z',updatedAt:'2026-09-19T12:00:00Z',history:[]};
    fixture.componentInstance.status.set({...state,maintenance:{...state.maintenance!,workspaceStopped:true},progress:{revision:'r',error:null,data:{version:1,milestones:[{id:'m',title:'Booking',description:'',features:[feature]}]}}});
    fixture.componentInstance.navigate('progress');fixture.detectChanges();
    const badge=fixture.nativeElement.querySelector('a[href="#progress"] .nav-result-badge');
    expect(badge.textContent).toBe('100%');expect(badge.title).toContain('1 of 1 recorded features and fixes completed');
    expect(badge.classList.contains('in-progress')).toBeFalse();
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Progress');
    expect(fixture.nativeElement.querySelector('.milestone-title').textContent).toBe('Booking');
  });
  it('scopes the menu and project page to the selected dev server', () => {
    const root: HTMLElement = fixture.nativeElement;
    expect(Array.from(root.querySelectorAll('nav > a')).map(a => a.querySelector('span')?.textContent?.trim())).toEqual(['App preview', 'Workspace', 'Progress', 'Tests', 'Startup']);
    expect(root.querySelector('nav a[href="#projects"]')?.getAttribute('aria-current')).toBe('page');
    expect(root.querySelector('nav a[href="#monitoring/visualize"]')).toBeNull();
    expect(root.querySelector('h1')?.textContent).toBe('Workspace');
    expect(root.querySelector('main')?.textContent).not.toContain('Other projects');
    expect(root.querySelector('main')?.textContent).not.toContain('/projects/orders');
    expect(root.querySelector('.current-project')?.textContent).toContain('/projects/repair-cafe');
    openPicker();
    const options = root.querySelectorAll('.server-option');
    expect(options.length).toBe(1);
    expect(Array.from(root.querySelectorAll('.server-group-title')).map(e => e.textContent)).toEqual(['Stopped']);
    expect(options[0].textContent).toContain('orders');
    expect(options[0].tagName).toBe('BUTTON');
    expect(root.querySelector('.server-menu')?.textContent).not.toContain('repair-cafe');
    expect(root.querySelector('.server-menu .server-state')).toBeNull();
    expect(root.querySelector('.server-menu .icon-button')).toBeNull();
    expect(root.querySelector('[aria-label="Deselect dev server"]')).toBeNull();
    expect(root.querySelector('.server-picker-button')?.textContent).toContain('repair-cafe');
  });
  it('keeps the visited server current while navigating and derives the selection from the destination server', () => {
    const root: HTMLElement = fixture.nativeElement;
    const component = fixture.componentInstance;
    const destination: Environment = {...component.environments()[1], id:'c'.repeat(64), projectName:'Workshop',
      projectDirectory:'/projects/workshop', status:'running', consoleUrl:'http://localhost:4500/_fluxzero/dev/'};
    component.environments.update(list => [...list, destination]);
    const navigate = spyOn(component, 'openEnvironment');
    openPicker();
    const search = root.querySelector('[aria-label="Search dev servers"]') as HTMLInputElement;
    expect(document.activeElement).toBe(search);
    search.value = 'workshop'; search.dispatchEvent(new Event('input')); fixture.detectChanges();
    const option = root.querySelector('a.server-option') as HTMLAnchorElement;
    expect(option.href).toBe('http://localhost:4500/_fluxzero/dev/#application');
    (option.querySelector('.server-path') as HTMLElement).click(); fixture.detectChanges();
    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate.calls.mostRecent().args[0]).toEqual(destination);
    expect(root.querySelector('.server-menu')).toBeNull();
    expect(root.querySelector('.server-picker-button')?.textContent).toContain('repair-cafe');
    expect(document.activeElement).toBe(root.querySelector('[aria-label="Choose workspace"]'));
    push({status:{...component.status()!, project:'Workshop',projectDirectory:destination.projectDirectory},environments:component.environments()});
    fixture.detectChanges();
    expect(root.querySelector('.server-picker-button')?.textContent).toContain('Workshop');
    openPicker();
    expect((root.querySelector('[aria-label="Search dev servers"]') as HTMLInputElement).value).toBe('');
    expect(root.querySelector('.server-menu')?.textContent).not.toContain('Workshop');
    expect(root.querySelector('.server-menu')?.textContent).toContain('repair-cafe');
  });
  it('excludes the current server and sorts other active servers before inactive servers, searching names and folders', () => {
    const component = fixture.componentInstance;
    const stopped = component.environments()[1];
    component.environments.update(list => [...list,
      {...stopped, id:'c'.repeat(64), projectName:'Zulu active', projectDirectory:'/projects/active', status:'running', consoleUrl:'http://localhost:4500/_fluxzero/dev/'},
      {...stopped, id:'d'.repeat(64), projectName:'Alpha stopped', projectDirectory:'/projects/other'}]);
    openPicker();
    const root: HTMLElement = fixture.nativeElement;
    expect(Array.from(root.querySelectorAll('.server-option-name')).map(e => e.textContent?.trim()))
      .toEqual(['Zulu active', 'Alpha stopped', 'orders']);
    expect(Array.from(root.querySelectorAll('.server-group-title')).map(e => e.textContent)).toEqual(['Running', 'Stopped']);
    const search = root.querySelector('[aria-label="Search dev servers"]') as HTMLInputElement;
    search.value = '/projects/active'; search.dispatchEvent(new Event('input')); fixture.detectChanges();
    expect(root.querySelectorAll('.server-option').length).toBe(1);
    expect(root.querySelector('.server-option-name')?.textContent).toContain('Zulu active');
    expect(Array.from(root.querySelectorAll('.server-group-title')).map(e => e.textContent)).toEqual(['Running']);
    search.value = 'not a server'; search.dispatchEvent(new Event('input')); fixture.detectChanges();
    expect(root.querySelectorAll('.server-group').length).toBe(0);
    expect(root.querySelector('.server-empty')?.textContent).toBe('No matching dev servers');
  });
  it('explains unavailable dashboards without linking into the customer application', () => {
    fixture.componentInstance.environments.update(list => [...list, {...list[1], id:'c'.repeat(64),
      projectName:'Legacy ticketing',status:'running',consoleUrl:null,
      detail:'Dashboard unavailable. Restart with a dashboard-enabled dev server.'}]);
    openPicker();
    const root: HTMLElement = fixture.nativeElement;
    const unavailable = root.querySelector('.server-option[aria-disabled="true"]')!;
    expect(unavailable.textContent).toContain('Legacy ticketing');
    expect(unavailable.textContent).toContain('Dashboard unavailable');
    expect(unavailable.querySelector('a,button')).toBeNull();
  });
  it('offers only validated console URLs for navigation', () => {
    const current = fixture.componentInstance.environments()[0];
    expect(environmentConsoleUrl(current)).toBe('http://localhost:4200/_fluxzero/dev/#application');
    for (const consoleUrl of ['https://example.org', 'http://localhost:4200/other', 'http://user@localhost:4200/_fluxzero/dev/', 'javascript:alert(1)', 'invalid']) {
      expect(environmentConsoleUrl({...current, consoleUrl})).toBeNull();
    }
    expect(environmentConsoleUrl({...current, status:'stopped'})).toBeNull();
  });
  it('dismisses the picker with Escape and an outside click', () => {
    const root:HTMLElement = fixture.nativeElement;
    openPicker();
    (root.querySelector('dev-environment-selector') as HTMLElement).dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));
    fixture.detectChanges();expect(root.querySelector('.server-menu')).toBeNull();
    expect(root.querySelector('[aria-label="Choose workspace"]')?.textContent).toContain('repair-cafe');
    openPicker();(root.querySelector('h1') as HTMLElement).click();fixture.detectChanges();
    expect(root.querySelector('.server-menu')).toBeNull();
    expect(root.querySelector('[aria-label="Choose workspace"]')?.textContent).toContain('repair-cafe');
  });
  for (const succeeds of [true, false]) it(`starts a stopped project from the manager and ${succeeds ? 'switches after success' : 'stays on failure'}`, async () => {
    const app=fixture.componentInstance;
    const navigate=spyOn(app,'openEnvironment');
    const root=fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    const manager=fixture.debugElement.query(By.directive(ProjectManagerComponent)).componentInstance as ProjectManagerComponent;
    const switchProject=spyOn(manager,'openProject').and.callThrough();
    const button=root.querySelector('dev-project-manager [aria-label="Switch to project"]') as HTMLButtonElement;
    expect(button.disabled).toBeTrue(); // The initial fixture has a missing folder.
    app.environments.update(list=>list.map(e=>({...e,directoryExists:true})));
    fixture.detectChanges();
    expect(button.disabled).toBeFalse();
    button.click(); fixture.detectChanges();
    expect(button.disabled).toBeTrue();
    expect(button.querySelector('.starting-icon')).not.toBeNull();
    expect(navigate).not.toHaveBeenCalled();
    const request=TestBed.inject(HttpTestingController).expectOne('projects/'+'b'.repeat(64)+'/start');
    if(succeeds) request.flush({...app.environments()[1],status:'running',consoleUrl:'http://localhost:4300/_fluxzero/dev/'});
    else request.flush({error:'Could not start project.'},{status:409,statusText:'Conflict'});
    await switchProject.calls.mostRecent().returnValue;
    await fixture.whenStable(); fixture.detectChanges();
    if(succeeds) expect(navigate).toHaveBeenCalledTimes(1);
    else {expect(navigate).not.toHaveBeenCalled();expect(root.querySelector('dev-project-manager [role="alert"]')?.textContent).toContain('Could not start project.');}
    expect(button.querySelector('.starting-icon')).toBeNull();
  });

  it('requires confirmation before starting an inactive server and switches only after success', async () => {
    const component = fixture.componentInstance;
    component.environments.update(list => list.map(e => ({...e,directoryExists:true})));
    const navigate = spyOn(component, 'openEnvironment');
    const startCommand = spyOn(component, 'startEnvironment').and.callThrough();
    openPicker();
    const root:HTMLElement = fixture.nativeElement;
    (root.querySelector('.inactive-server .server-path') as HTMLElement).click();fixture.detectChanges();
    const dialog = root.querySelector('[aria-labelledby="server-start-title"]') as HTMLDialogElement;
    expect(dialog.open).toBeTrue();
    const http = TestBed.inject(HttpTestingController);
    http.expectNone('projects/' + 'b'.repeat(64) + '/start');
    expect(document.activeElement).toBe(dialog.querySelector('.secondary-button'));
    (dialog.querySelector('.secondary-button') as HTMLButtonElement).click();
    expect(navigate).not.toHaveBeenCalled();
    openPicker();(root.querySelector('.inactive-server') as HTMLButtonElement).click();fixture.detectChanges();
    (dialog.querySelector('.primary-button') as HTMLButtonElement).click();fixture.detectChanges();
    expect(navigate).not.toHaveBeenCalled();
    expect((dialog.querySelector('.primary-button') as HTMLButtonElement).disabled).toBeTrue();
    const started:Environment = {...component.environments()[1],status:'running',consoleUrl:'http://localhost:4300/_fluxzero/dev/'};
    http.expectOne('projects/' + 'b'.repeat(64) + '/start').flush(started);
    await startCommand.calls.mostRecent().returnValue;
    await fixture.whenStable();fixture.detectChanges();
    expect(navigate).toHaveBeenCalledOnceWith(started);
    expect(dialog.open).toBeFalse();
  });
  it('keeps the user on the current server when startup fails', async () => {
    const component = fixture.componentInstance;
    component.environments.update(list => list.map(e => ({...e,directoryExists:true})));
    const navigate = spyOn(component, 'openEnvironment');
    const startCommand = spyOn(component, 'startEnvironment').and.callThrough();
    openPicker();
    const root:HTMLElement = fixture.nativeElement;
    (root.querySelector('.inactive-server') as HTMLButtonElement).click();fixture.detectChanges();
    const dialog = root.querySelector('[aria-labelledby="server-start-title"]') as HTMLDialogElement;
    (dialog.querySelector('.primary-button') as HTMLButtonElement).click();
    TestBed.inject(HttpTestingController).expectOne('projects/' + 'b'.repeat(64) + '/start')
      .flush({error:'The port is occupied.'},{status:409,statusText:'Conflict'});
    await fixture.whenStable();fixture.detectChanges();
    expect(navigate).not.toHaveBeenCalled();expect(dialog.open).toBeTrue();
    expect(dialog.querySelector('[role="alert"]')?.textContent).toBe('The port is occupied.');
    expect((dialog.querySelector('.primary-button') as HTMLButtonElement).disabled).toBeFalse();
  });
  it('updates tests and server history from push without polling, and replaces history on reconnect', () => {
    openTests();
    const state = fixture.componentInstance.status()!;
    const sample = {at:5000,applicationMemory:100,devserverMemory:200,monitoringStorage:50};
    push({status:{...state,resourceHistory:[sample],testResults:{available:true,running:true,passed:3,failed:0,skipped:0,total:3,expectedTotal:10,label:''}},environments:[]});
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.test-status').textContent).toContain('3 passed');
    expect(fixture.componentInstance.status()?.resourceHistory).toEqual([sample]);
    connected(false); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.sidebar-footer').textContent).toBe('Disconnected');
    expect(fixture.componentInstance.status()?.resourceHistory?.length).toBe(1);
    push({status:{...state,resourceHistory:[sample,{...sample,at:10000}]},environments:[]});
    connected(true); fixture.detectChanges();
    expect(fixture.componentInstance.status()?.resourceHistory?.length).toBe(2);
    TestBed.inject(HttpTestingController).expectNone('status.json');
    TestBed.inject(HttpTestingController).expectNone('environments.json');
  });
  it('shows pushed test output as text and keeps long lines inside the mobile layout', async () => {
    openTests();openOutput();
    push({status:{...fixture.componentInstance.status()!,testOutput:[{sequence:1,module:'customer',text:'<script>example</script>'+ 'x'.repeat(500)}]},environments:[]});
    fixture.detectChanges();
    await fixture.whenStable();fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('dev-test-output') as HTMLElement).hidden).toBeFalse();
    const output=fixture.nativeElement.querySelector('.test-output pre') as HTMLElement;
    expect(output.textContent).toContain('<script>example</script>');
    expect(output.querySelector('script')).toBeNull();
    expect(output.scrollWidth).toBeLessThanOrEqual(output.clientWidth+1);
  });
  it('clears output without changing test results', async () => {
    openTests();openOutput();
    const state=fixture.componentInstance.status()!;
    push({status:{...state,testOutput:[{sequence:1,module:'app',text:'example'}]},environments:[]});
    fixture.detectChanges();await fixture.whenStable();
    const root:HTMLElement=fixture.nativeElement;
    (root.querySelector('[aria-label="Clear test output"]') as HTMLButtonElement).click();fixture.detectChanges();
    const request=TestBed.inject(HttpTestingController).expectOne('actions/clear-test-output');
    expect(request.request.method).toBe('POST');expect(request.request.headers.get('X-Fluxzero-Console')).toBe('1');
    request.flush(null);await fixture.whenStable();
    push({status:{...state,testOutput:[]},environments:[]});fixture.detectChanges();
    expect(root.querySelector('pre')?.textContent).toContain('No test output.');
    expect((root.querySelector('[aria-label="Clear test output"]') as HTMLButtonElement).disabled).toBeTrue();
    expect(fixture.componentInstance.status()?.testResults).toEqual(state.testResults);
  });
  it('pauses only automatic tests and keeps manual runs and restarts available', async () => {
    openTests();
    const state=fixture.componentInstance.status()!;
    const root:HTMLElement=fixture.nativeElement;
    const http=TestBed.inject(HttpTestingController);
    push({status:{...state,testResults:{...state.testResults!,runnable:true}},environments:[]});
    fixture.detectChanges();await fixture.whenStable();
    (root.querySelector('[aria-label="Pause"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const pause=http.expectOne('actions/pause-tests');
    expect(pause.request.method).toBe('POST');
    pause.flush(null); await fixture.whenStable();
    push({status:{...state,testResults:{...state.testResults!,runnable:true,paused:true}},environments:[]});
    fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    const resume=root.querySelector('[aria-label="Resume"]') as HTMLButtonElement;
    expect(resume.disabled).toBeFalse();
    expect(root.querySelector('.test-pause-status')?.textContent).toContain('Automatic tests paused');
    expect((root.querySelector('[aria-label="Rerun"]') as HTMLButtonElement).disabled).toBeFalse();
    (root.querySelector('[aria-label="Rerun"]') as HTMLButtonElement).click();
    http.expectOne('actions/run-tests').flush(null);await fixture.whenStable();
    resume.click();fixture.detectChanges();
    http.expectOne('actions/resume-tests').flush(null);await fixture.whenStable();
    push({status:state,environments:[]});fixture.detectChanges();
    expect(root.querySelector('.test-pause-status')).toBeNull();
    http.expectNone('actions/pause-builds');
  });
  it('follows output at the bottom and preserves a scrolled-up viewport', async () => {
    openTests();openOutput();
    const state=fixture.componentInstance.status()!;
    const update=async (count:number) => {
      push({status:{...state,testOutput:Array.from({length:count},(_,i)=>({sequence:i,module:'app',text:'line '+i}))},environments:[]});
      fixture.detectChanges();await fixture.whenStable();
    };
    await update(1);
    const output=fixture.nativeElement.querySelector('.test-output pre') as HTMLElement;
    await update(40);
    expect(output.scrollTop).toBeGreaterThan(0);
    expect(output.scrollHeight-output.scrollTop-output.clientHeight).toBeLessThanOrEqual(4);
    output.scrollTop=20;output.dispatchEvent(new Event('scroll'));
    await update(50);
    expect(output.scrollTop).toBe(20);
    output.scrollTop=output.scrollHeight;output.dispatchEvent(new Event('scroll'));
    await update(60);
    expect(output.scrollHeight-output.scrollTop-output.clientHeight).toBeLessThanOrEqual(4);
    output.scrollTop=0;output.dispatchEvent(new Event('scroll'));
    (fixture.nativeElement.querySelector('[aria-label="Scroll to bottom"]') as HTMLButtonElement).click();
    expect(output.scrollHeight-output.scrollTop-output.clientHeight).toBeLessThanOrEqual(4);
    await update(70);
    expect(output.scrollHeight-output.scrollTop-output.clientHeight).toBeLessThanOrEqual(4);
  });
  it('starts tests through the dev server and offers output in its own tab', async () => {
    openTests();
    const state=fixture.componentInstance.status()!;
    push({status:{...state,testResults:{...state.testResults!,runnable:true},testOutput:[{sequence:1,module:'app',text:'retained'}]},environments:[]});
    fixture.detectChanges();await fixture.whenStable();
    const root:HTMLElement=fixture.nativeElement;
    expect(root.querySelector('.page-heading .count')).toBeNull();
    expect(root.querySelector('.test-output summary')).toBeNull();
    expect(root.querySelector('[aria-label="Hide test output"]')).toBeNull();
    expect(root.querySelector('[aria-label="Rerun"] .bi-arrow-clockwise')).not.toBeNull();
    expect((root.querySelector('.output-panel') as HTMLElement).hidden).toBeTrue();
    openOutput();
    expect((root.querySelector('.output-panel') as HTMLElement).hidden).toBeFalse();
    expect(root.querySelector('.test-output pre')?.textContent).toContain('retained');
    (root.querySelector('[aria-label="Rerun"]') as HTMLButtonElement).click();fixture.detectChanges();
    const request=TestBed.inject(HttpTestingController).expectOne('actions/run-tests');
    expect(request.request.method).toBe('POST');expect(request.request.headers.get('X-Fluxzero-Console')).toBe('1');
    request.flush(null);await fixture.whenStable();
    push({status:{...state,testResults:{...state.testResults!,runnable:true,running:true}},environments:[]});fixture.detectChanges();
    expect((root.querySelector('[aria-label="Rerun"]') as HTMLButtonElement).disabled).toBeTrue();
    expect(root.querySelector('.test-status')?.textContent).toContain('Running tests');
  });
  it('keeps monitoring views in the left navigation even without a configured backend', async () => {
    const link = fixture.nativeElement.querySelector('nav a[href="#monitoring/logs"]') as HTMLAnchorElement;
    link.click();
    await fixture.whenStable(); fixture.detectChanges();
    expect(location.hash).toBe('#monitoring/logs');
    expect(link.getAttribute('aria-current')).toBe('page');
    expect(fixture.nativeElement.textContent).toContain('Monitoring is not configured');
  });
  it('opens known folders and forgets stopped projects through DOM commands', async () => {
    const http = TestBed.inject(HttpTestingController);
    (fixture.nativeElement.querySelector('.current-project .folder-button') as HTMLButtonElement).click();
    const open = http.expectOne('projects/' + 'a'.repeat(64) + '/open-folder');
    expect(open.request.method).toBe('POST');
    expect(open.request.headers.get('X-Fluxzero-Console')).toBe('1');
    open.flush(null);
    openPicker();
    (fixture.nativeElement.querySelector('.inactive-server') as HTMLButtonElement).click();fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.start-dialog .primary-button')).toBeNull();
    (fixture.nativeElement.querySelector('.remove-project') as HTMLButtonElement).click();
    http.expectOne('projects/' + 'b'.repeat(64) + '/forget').flush(null);
    await fixture.whenStable(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('/projects/orders');
    expect(fixture.nativeElement.textContent).toContain('/projects/repair-cafe');
    http.verify();
  });
  it('keeps a project visible when the backend rejects removal', async () => {
    openPicker();
    (fixture.nativeElement.querySelector('.inactive-server') as HTMLButtonElement).click();fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.start-dialog .primary-button')).toBeNull();
    (fixture.nativeElement.querySelector('.remove-project') as HTMLButtonElement).click();
    TestBed.inject(HttpTestingController).expectOne('projects/' + 'b'.repeat(64) + '/forget')
      .flush({error: 'Project is running.'}, {status: 409, statusText: 'Conflict'});
    await fixture.whenStable(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('/projects/orders');
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Project is running.');
  });
  it('opens the app in a persistent iframe and retains it across console navigation and status updates', () => {
    const app = fixture.componentInstance;
    const status = {...app.status()!, components: [{id: 'app', name: 'Home', state: 'running',
      application: true, memoryBytes: null, url: location.origin + '/?preview=1'}]};
    push({status, environments: app.environments()}); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('iframe[name="dev-application"]')).toBeNull();
    fixture.nativeElement.querySelector('nav a[href="#application"]').click(); fixture.detectChanges();
    expect(location.hash).toBe('#application');
    const frame = fixture.nativeElement.querySelector('iframe[name="dev-application"]') as HTMLIFrameElement;
    expect(frame.src).toBe(location.origin + '/?preview=1');
    const source = app.applicationSource();
    app.navigate('projects'); fixture.detectChanges();
    expect(frame.closest('section')!.hidden).toBeTrue();
    push({status: {...status}, environments: app.environments()}); fixture.detectChanges();
    app.navigate('application'); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('iframe[name="dev-application"]')).toBe(frame);
    expect(app.applicationSource()).toBe(source);
    expect(frame.closest('section')!.hidden).toBeFalse();
    expect(fixture.nativeElement.querySelector('[aria-label="Open application full page"]').getAttribute('target')).toBe('_blank');
  });
  it('expands preview without replacing the app, changing navigation preferences or consuming Escape', async () => {
    const app = fixture.componentInstance;
    app.navigate('application'); fixture.detectChanges();
    const frame = fixture.nativeElement.querySelector('iframe[name="dev-application"]');
    const saved = localStorage.getItem('devboard.sidebarCollapsed');
    for (const collapsed of [false, true]) {
      app.sidebarCollapsed.set(collapsed); fixture.detectChanges();
      fixture.nativeElement.querySelector('.preview-expand').click(); fixture.detectChanges();
      await new Promise(resolve => setTimeout(resolve, 0));
      await fixture.whenStable(); fixture.detectChanges();
      expect(app.navigationHidden()).toBeTrue();
      expect(fixture.nativeElement.querySelector('.preview-expand').getAttribute('aria-label')).toBe('Restore preview');
      expect(fixture.nativeElement.querySelector('.application-heading')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.dashboard-shell').classList.contains('preview-expanded')).toBeTrue();
      expect(document.activeElement).toBe(fixture.nativeElement.querySelector('.preview-expand'));
      const escape = new KeyboardEvent('keydown', {key:'Escape', bubbles:true, cancelable:true});
      document.dispatchEvent(escape);
      expect(escape.defaultPrevented).toBeFalse();
      expect(app.previewExpanded()).toBeTrue();
      fixture.nativeElement.querySelector('.preview-expand').click(); fixture.detectChanges();
      await new Promise(resolve => setTimeout(resolve, 0));
      await fixture.whenStable(); fixture.detectChanges();
      expect(app.previewExpanded()).toBeFalse();
      expect(app.sidebarCollapsed()).toBe(collapsed);
      expect(localStorage.getItem('devboard.sidebarCollapsed')).toBe(saved);
      expect(fixture.nativeElement.querySelector('iframe[name="dev-application"]')).toBe(frame);
      expect(document.activeElement).toBe(fixture.nativeElement.querySelector('.preview-expand'));
    }
    app.setPreviewExpanded(true);
    app.navigate('projects'); fixture.detectChanges();
    expect(app.previewExpanded()).toBeFalse();
  });
  it('copies the full current preview URL',async()=>{
    const app=fixture.componentInstance;app.preview.url.set('http://localhost:4242/#/tickets');
    const clipboard=spyOn(navigator.clipboard,'writeText').and.resolveTo();
    await app.copyPreviewUrl();
    expect(clipboard).toHaveBeenCalledOnceWith('http://localhost:4242/#/tickets');expect(app.previewCopied()).toBeTrue();
  });
  it('handles direct app routes and shows an unavailable state without a frame URL', () => {
    history.replaceState(null, '', '#application');
    fixture.componentInstance.readRoute(); fixture.detectChanges();
    expect(fixture.componentInstance.route()).toBe('application');
    expect(fixture.nativeElement.querySelector('.application-empty').textContent).toContain('not available yet');
    expect(fixture.nativeElement.querySelector('iframe[name="dev-application"]')).toBeNull();
  });
  it('collapses long folder paths while keeping every ancestor reachable', () => {
    const manager=fixture.debugElement.query(By.directive(ProjectManagerComponent)).componentInstance as ProjectManagerComponent;
    const ancestors=['/','/one','/one/two','/one/two/three','/one/two/three/four'].map(path=>({name:path.split('/').pop()||'/',path}));
    manager.folder.set({path:ancestors[4].path,parent:ancestors[3].path,folders:[],truncated:false,project:false,ancestors});
    expect(manager.crumbs().map(c=>c.path)).toEqual(['/', '…', ancestors[3].path, ancestors[4].path]);
    manager.expandedPath.set(true);
    expect(manager.crumbs()).toEqual(ancestors);
  });

  it('opens project management and browses folders without creating a project', async () => {
    const root=fixture.nativeElement as HTMLElement;
    (root.querySelector('[aria-label="Choose workspace"]') as HTMLButtonElement).click();fixture.detectChanges();
    (root.querySelector('.manage-projects') as HTMLButtonElement).click();fixture.detectChanges();
    const dialog=root.querySelector('dev-project-manager dialog') as HTMLDialogElement;
    expect(dialog.open).toBeTrue();
    expect(dialog.textContent).toContain('Folder not found');
    (dialog.querySelector('.toolbar .primary-button') as HTMLButtonElement).click();fixture.detectChanges();
    const http=TestBed.inject(HttpTestingController);
    const browse=http.expectOne('projects/folders');
    expect(browse.request.headers.get('X-Fluxzero-Console')).toBe('1');
    browse.flush({path:'/projects',parent:'/',folders:[],truncated:false,project:false});
    await fixture.whenStable();fixture.detectChanges();
    expect((dialog.querySelector('[aria-label="Folder path"]') as HTMLInputElement).value).toBe('/projects');
    expect((dialog.querySelector('.dialog-actions .primary-button') as HTMLButtonElement).disabled).toBeTrue();
    http.expectNone('projects/create');
    (dialog.querySelector('.dialog-cancel') as HTMLButtonElement).click();fixture.detectChanges();
    expect(dialog.textContent).toContain('Manage projects');
  });

  it('renames the current project and keeps a rejected draft available', async () => {
    const root: HTMLElement = fixture.nativeElement;
    (root.querySelector('dev-project-rename [aria-label="Rename project"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const dialog = root.querySelector('dev-project-rename dialog') as HTMLDialogElement;
    const input = dialog.querySelector('input')!;
    expect(dialog.open).toBeTrue();
    expect(input.value).toBe('repair-cafe');
    input.value = '  My workshop  '; input.dispatchEvent(new Event('input'));
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', {cancelable: true}));
    const http = TestBed.inject(HttpTestingController);
    let request = http.expectOne('projects/' + 'a'.repeat(64) + '/rename');
    expect(request.request.body).toEqual({name: 'My workshop'});
    expect(request.request.headers.get('X-Fluxzero-Console')).toBe('1');
    request.flush({error: 'Try again'}, {status: 503, statusText: 'Unavailable'});
    await fixture.whenStable(); fixture.detectChanges();
    expect(dialog.open).toBeTrue(); expect(input.value).toBe('  My workshop  ');
    expect(dialog.querySelector('[role="alert"]')?.textContent).toContain('Try again');
    dialog.querySelector('form')!.dispatchEvent(new Event('submit', {cancelable: true}));
    request = http.expectOne('projects/' + 'a'.repeat(64) + '/rename');
    request.flush({...fixture.componentInstance.current()!, projectName: 'My workshop'});
    await fixture.whenStable(); fixture.detectChanges();
    expect(dialog.open).toBeFalse();
    expect(root.querySelector('.current-project-title')?.textContent).toContain('My workshop');
    expect(root.querySelector('[aria-label="Choose workspace"]')?.textContent).toContain('My workshop');
  });
  it('shows measured component memory, the application link and actual test counts', () => {
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('.cards')).toBeNull();
    expect(root.querySelector('.application-overview .component-storage')).toBeNull();
    expect(root.querySelector('.application-overview')?.textContent).not.toContain('Storage');
    expect(root.querySelector('.infrastructure-section h2')?.textContent).toBe('Dev resources');
    expect(root.querySelector('[aria-label="Rename project"]')).not.toBeNull();
    expect(root.querySelector('.environment-tests')).toBeNull();
    expect(root.querySelector('.environment-eyebrow')).toBeNull();
    expect(root.querySelector('.tests-card h2,.tests-card h3')).toBeNull();
    expect(root.querySelector('.infrastructure-section')?.contains(root.querySelectorAll('.component-table')[1])).toBeTrue();
    expect(root.querySelector('.applications-section')?.contains(root.querySelector('.infrastructure-section'))).toBeFalse();
    expect(root.querySelector('.component-table tbody tr th')?.textContent).toContain('Repair Café');
    expect(root.querySelector('.application-actions .application-link')).toBeNull();
    expect(root.querySelector('.infrastructure-section .component-table')?.textContent).toContain('120.0 MiB');
    const componentRows = root.querySelectorAll('.component-table tbody tr');
    expect(componentRows[0].querySelector('.component-restart')).toBeNull();
    expect(root.querySelector('.workspace-actions .restart-action')?.getAttribute('aria-label')).toBe('Restart Apps');
    expect(root.querySelector('.applications-heading button')).toBeNull();
    expect(root.querySelector('[aria-label="Reset data"]')).toBeNull();
    expect(componentRows[1].querySelector('.component-restart,.component-actions')).toBeNull();
    openTests();
    expect(root.querySelector('nav a[href="#tests"]')?.getAttribute('aria-current')).toBe('page');
    expect(root.querySelector('.scenario-filters')?.textContent).toContain('Passed8');
    expect(root.querySelector('.scenario-filters')?.textContent).toContain('Skipped1');
    expect(root.querySelector('.test-bar')).toBeNull();
    expect(root.querySelector('.test-summary > small')).toBeNull();
  });
  it('shows backend apps independently and includes managed frontends in shared services', () => {
    const root: HTMLElement = fixture.nativeElement;
    fixture.componentInstance.status.update(s => s ? {...s, components: [
      {id:'app-orders',name:'Orders',state:'running',application:true,memoryBytes:1048576,url:'http://localhost:8080/'},
      {id:'app-billing',name:'Billing',state:'failed',application:true,memoryBytes:2097152},
      {id:'frontend-store',name:'Frontend (store)',state:'running',application:true,memoryBytes:4194304},
      {id:'devserver',name:'Supervisor',state:'running',application:false,memoryBytes:8388608}
    ], resourceHistory:[{at:5000,applicationMemory:7340032,devserverMemory:8388608,monitoringStorage:0,
      componentMemory:{'app-orders':1048576,'app-billing':2097152,'frontend-store':4194304}}]} : s);
    fixture.detectChanges();
    const cards = root.querySelectorAll('.application-overview .component-table');
    expect(cards.length).toBe(2);
    expect(Array.from(cards, c => c.querySelector('.component-name')!.textContent!.trim())).toEqual(['Orders','Billing']);
    expect(Array.from(cards, c => c.querySelector('.badge')!.textContent)).toEqual(['running','failed']);
    expect(Array.from(cards, c => c.querySelector('.resource-value')!.textContent)).toEqual(['1.0 MiB','2.0 MiB']);
    expect(root.querySelectorAll('.application-overview dev-resource-graph').length).toBe(2);
    expect(root.querySelector('.infrastructure-section .resource-value')!.textContent).toBe('12.0 MiB');
    const detail = root.querySelector('.infrastructure-section dev-resource-detail')!;
    detail.dispatchEvent(new MouseEvent('mouseenter')); fixture.detectChanges();
    expect(detail.querySelector('[role=tooltip]')!.textContent).toContain('Frontend (store)');
    expect(detail.querySelector('[role=tooltip]')!.textContent).not.toContain('Orders');
    detail.dispatchEvent(new MouseEvent('mouseleave')); fixture.detectChanges();
    expect(root.querySelectorAll('.application-overview .component-restart,.application-overview .component-storage').length).toBe(0);
    expect(root.querySelectorAll('[aria-label="Restart Apps"]').length).toBe(1);
    const applications = root.querySelector('.applications-section')!;
    expect(root.querySelector('.environment-tests')).toBeNull();
    expect(applications.nextElementSibling).toBe(root.querySelector('.infrastructure-section'));
    openTests();
    expect(root.querySelector('h1')?.textContent).toBe('Tests');
    expect(root.querySelector('dev-test-catalog .output-panel dev-test-output')).not.toBeNull();
    expect(root.querySelector('.test-output-section')).toBeNull();
  });
  it('shows an empty application list without inventing an application', () => {
    fixture.componentInstance.status.update(s => s ? {...s, components:s.components!.filter(c => !c.application)} : s);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.application-overview .component-table').length).toBe(0);
    expect(fixture.nativeElement.querySelector('.applications-empty').textContent).toContain('No applications configured');
    expect(fixture.nativeElement.querySelectorAll('.environment-tests').length).toBe(0);
  });
  it('keeps preview available for a frontend-only environment without a backend card', () => {
    fixture.componentInstance.status.update(s => s ? {...s, frontend:'running', components:[
      {id:'frontend-store',name:'Frontend (store)',state:'running',application:true,memoryBytes:4194304,url:location.origin + '/'}
    ]} : s);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.application-overview .component-table').length).toBe(0);
    expect(fixture.nativeElement.querySelector('nav a[href="#application"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.infrastructure-section .resource-value').textContent).toBe('4.0 MiB');
  });
  it('restarts only the selected backend app without a separate data reset action', async () => {
    fixture.componentInstance.status.update(s => s ? {...s, components:[
      {id:'app-orders',name:'Orders',state:'running',application:true,restartSupported:true,memoryBytes:null},
      {id:'app-billing',name:'Billing',state:'running',application:true,restartSupported:true,memoryBytes:null}],
      maintenance:{...s.maintenance!,resetSupported:false}} : s);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).not.toContain('Data reset is unavailable');
    expect(root.querySelector('[aria-label="Reset data"]')).toBeNull();
    const button = root.querySelector('[aria-label="Restart Billing"]') as HTMLButtonElement;
    button.click(); fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    const request = http.expectOne('actions/restart-app');
    expect(request.request.body).toEqual({componentId:'app-billing'});
    expect(request.request.headers.get('X-Fluxzero-Console')).toBe('1');
    http.expectNone('actions/restart-application');
    expect(button.querySelector('.spinner-border')).not.toBeNull();
    expect(root.querySelector('[aria-label="Restart Orders"] .spinner-border')).toBeNull();
    request.flush(null); await fixture.whenStable();
  });
  it('keeps memory readings and graph controls visible during resource updates', async () => {
    const root: HTMLElement = fixture.nativeElement;
    const state = fixture.componentInstance.status()!;
    for (let i = 0; i < 8; i++) {
      const memory = (298 + i / 10) * 1048576;
      push({status: {...state, components: state.components!.map(c => c.application ? {...c, memoryBytes: memory} : c),
        resourceHistory: Array.from({length: 60}, (_, n) => ({at: 5000 * (i + n + 1), applicationMemory: memory + n * 1048576, devserverMemory: 125829120 + n * 1048576, monitoringStorage: 100 + n}))
      }, environments: []});
      fixture.detectChanges();
      await fixture.whenStable();
      await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
      for (const row of root.querySelectorAll('.component-table tbody tr')) {
        const cell = row.querySelector('.component-memory')!;
        const cellRect = cell.getBoundingClientRect();
        expect(cell.querySelector('dev-resource-chart')).toBeNull();
        const graphRect = cell.querySelector('dev-resource-graph button')!.getBoundingClientRect();
        const valueRect = cell.querySelector('.resource-value')!.getBoundingClientRect();
        expect(graphRect.width).toBeGreaterThan(0);
        expect(graphRect.right).toBeLessThanOrEqual(cellRect.right + 1);
        expect(valueRect.top).toBeGreaterThanOrEqual(cellRect.top);
        expect(valueRect.bottom).toBeLessThanOrEqual(cellRect.bottom);
      }
    }
  });
  it('keeps a single application card compact and opens separate memory and storage graphs', async () => {
    const root: HTMLElement = fixture.nativeElement;
    const card = root.querySelector('.application-overview .component-table-scroll')!;
    expect(card.getBoundingClientRect().width).toBeLessThanOrEqual(420);
    fixture.componentInstance.status.update(s => s ? {...s, resourceHistory:[{
      at:5000,applicationMemory:1048576,devserverMemory:2097152,monitoringStorage:524288,
      componentMemory:{app:1048576}}]} : s);
    fixture.detectChanges();
    for (const [selector,title] of [['.application-overview .component-memory','Repair Café · Memory'],
      ['.infrastructure-section .component-storage','Monitoring storage']]) {
      const graph = root.querySelector(selector + ' dev-resource-graph')!;
      (graph.querySelector('button') as HTMLButtonElement).click(); fixture.detectChanges();
      const dialog = graph.querySelector('dialog')!;
      expect(dialog.open).toBeTrue();
      expect(dialog.querySelector('h2')!.textContent).toBe(title);
      expect(dialog.querySelector('.graph-line')).not.toBeNull();
      (dialog.querySelector('button') as HTMLButtonElement).click();
      await fixture.whenStable(); fixture.detectChanges();
      expect(dialog.open).toBeFalse();
    }
  });
  it('keeps resource columns equal while adapting to longer formatted values', () => {
    const root: HTMLElement = fixture.nativeElement;
    const cells = root.querySelector('.infrastructure-section .component-table tbody tr')!.children;
    const initialWidth = cells[2].getBoundingClientRect().width;
    expect(initialWidth).toBeGreaterThan(0);
    expect(cells[3].getBoundingClientRect().width).toBe(initialWidth);
    fixture.componentInstance.status.update(s => s ? {...s, monitoring:{enabled:true, resources:{
      storageDiskBytes:512 * 1048576, diskRetentionThresholdBytes:1099511627776
    }}} : s);
    fixture.detectChanges();
    const expandedWidth = cells[2].getBoundingClientRect().width;
    expect(cells[3].getBoundingClientRect().width).toBe(expandedWidth);
    expect(expandedWidth).toBeLessThanOrEqual(root.clientWidth);
    expect(root.querySelector('.infrastructure-section .component-storage')!.textContent).toContain('512.0 MiB / 1.0 TiB');
  });
  it('fits long project names and paths without horizontal scroll in the compact layout', () => {
    if (matchMedia('(min-width:1201px)').matches) return;
    const root: HTMLElement = fixture.nativeElement;
    fixture.componentInstance.status.update(s => s ? {...s, project:'customer-'.repeat(15), projectDirectory:'/projects/'+'nested-folder/'.repeat(15),
      components:s.components!.map(c => c.application ? {...c,name:'CustomerApplication'.repeat(10)} : c)} : s);
    fixture.componentInstance.environments.update(projects => projects.map((p, index) => ({...p,projectName:'OtherProject'.repeat(20),projectDirectory:'/projects/'+'long-folder/'.repeat(15)+index})));
    fixture.detectChanges();
    for (const element of root.querySelectorAll('.current-project,.component-table-scroll,.project-table,.page')) {
      expect(element.scrollWidth).withContext(element.className).toBeLessThanOrEqual(element.clientWidth + 1);
    }
  });
  it('confirms maintenance before dispatching the protected DOM command', async () => {
    chooseCompleteEnvironment();
    const root: HTMLElement = fixture.nativeElement;
    (root.querySelector('[aria-label="Restart All"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectNone('actions/restart-devserver');
    expect(root.querySelector<HTMLDialogElement>('dev-environment .maintenance-confirm')?.textContent).toContain('In-memory application data will be reset');
    expect(root.querySelector('dev-environment .maintenance-confirm')?.textContent).toContain('Stored monitoring history (VictoriaLogs) is deleted too');
    (root.querySelector('dev-environment .maintenance-confirm .primary-button') as HTMLButtonElement).click();
    const request = http.expectOne('actions/restart-devserver');
    expect(request.request.headers.get('X-Fluxzero-Console')).toBe('1');
    request.flush(null);
    await fixture.whenStable(); fixture.detectChanges();
    expect((root.querySelector('[aria-label="Restart All"]') as HTMLButtonElement).disabled).toBeTrue();
  });
  it('cancels the full restart without resetting data', () => {
    chooseCompleteEnvironment();
    const root: HTMLElement = fixture.nativeElement;
    (root.querySelector('[aria-label="Restart All"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const dialog = root.querySelector<HTMLDialogElement>('dev-environment .maintenance-confirm')!;
    expect(dialog.open).toBeTrue();
    expect(document.activeElement).toBe(dialog.querySelector('[autofocus]'));
    expect(dialog.querySelector('input')).toBeNull();
    (dialog.querySelector('.secondary-button') as HTMLButtonElement).click();
    expect(dialog.open).toBeFalse();
    TestBed.inject(HttpTestingController).expectNone('actions/restart-devserver');
  });
  it('requires confirmation on every full restart, including with a legacy opt-out', async () => {
    chooseCompleteEnvironment();
    const root: HTMLElement = fixture.nativeElement;
    const http = TestBed.inject(HttpTestingController);
    expect(localStorage.getItem('devConfirmTruncate')).toBe('false');
    for (let attempt = 0; attempt < 2; attempt++) {
      (root.querySelector('[aria-label="Restart All"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(root.querySelector<HTMLDialogElement>('dev-environment .maintenance-confirm')!.open).toBeTrue();
      expect(root.querySelector('dev-environment .maintenance-confirm input')).toBeNull();
      http.expectNone('actions/restart-devserver');
      (root.querySelector('dev-environment .maintenance-confirm .primary-button') as HTMLButtonElement).click();
      http.expectOne('actions/restart-devserver').flush(null);
      await fixture.whenStable();
      expect(root.querySelector<HTMLDialogElement>('dev-environment .maintenance-confirm')!.open).toBeFalse();
      fixture.componentInstance.status.update(s => s ? {...s, maintenance: {...s.maintenance, busy:false, error:''}} : s);
      fixture.detectChanges();
    }
  });
  it('changes themes through the footer DOM command and marks the current preference', () => {
    const root: HTMLElement = fixture.nativeElement;
    fixture.componentInstance.setTheme('light'); fixture.detectChanges();
    expect(root.querySelector('nav a[href="#settings"]')).toBeNull();
    const trigger = root.querySelector('.theme-trigger') as HTMLButtonElement;
    expect(trigger.querySelector('.bi-sun')).not.toBeNull();
    trigger.click(); fixture.detectChanges();
    const items = root.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]');
    expect(Array.from(items, item => item.textContent?.trim())).toEqual(['Light', 'Dark', 'System']);
    expect(items[0].getAttribute('aria-checked')).toBe('true');
    expect(items[1].getAttribute('aria-checked')).toBe('false');
    items[1].click(); fixture.detectChanges();
    expect(document.documentElement.getAttribute('data-bs-theme')).toBe('dark');
    expect(localStorage.getItem('dashboardTheme')).toBe('dark');
    expect(root.querySelector('[role="menu"]')).toBeNull();
    expect(document.activeElement).toBe(trigger);
    expect(trigger.querySelector('.bi-moon-stars')).not.toBeNull();
    trigger.click(); fixture.detectChanges();
    expect(root.querySelector('[role="menuitemradio"][aria-checked="true"]')?.textContent?.trim()).toBe('Dark');
    (root.querySelectorAll('[role="menuitemradio"]')[2] as HTMLButtonElement).click(); fixture.detectChanges();
    expect(localStorage.getItem('dashboardTheme')).toBe('system');
    expect(trigger.querySelector('.bi-display')).not.toBeNull();
  });
  it('supports keyboard theme navigation, Escape and dismissal outside the menu', () => {
    const root: HTMLElement = fixture.nativeElement;
    fixture.componentInstance.setTheme('light'); fixture.detectChanges();
    const trigger = root.querySelector('.theme-trigger') as HTMLButtonElement;
    trigger.click(); fixture.detectChanges();
    const items = root.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]');
    expect(document.activeElement).toBe(items[0]);
    items[0].dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowDown',bubbles:true}));
    expect(document.activeElement).toBe(items[1]);
    items[1].dispatchEvent(new KeyboardEvent('keydown', {key:'End',bubbles:true}));
    expect(document.activeElement).toBe(items[2]);
    items[2].dispatchEvent(new KeyboardEvent('keydown', {key:'Escape',bubbles:true})); fixture.detectChanges();
    expect(root.querySelector('[role="menu"]')).toBeNull();
    expect(document.activeElement).toBe(trigger);
    trigger.click(); fixture.detectChanges();
    (root.querySelector('h1') as HTMLElement).click(); fixture.detectChanges();
    expect(root.querySelector('[role="menu"]')).toBeNull();
    expect(fixture.componentInstance.themePreference()).toBe('light');
    trigger.click(); fixture.detectChanges();
    window.dispatchEvent(new Event('blur')); fixture.detectChanges();
    expect(root.querySelector('[role="menu"]')).toBeNull();
  });
  it('redirects old Settings bookmarks to Projects', () => {
    history.replaceState(null, '', '#settings');
    fixture.componentInstance.readRoute(); fixture.detectChanges();
    expect(location.hash).toBe('#projects');
    expect(fixture.nativeElement.querySelector('h1')?.textContent).toBe('Workspace');
  });
  it('shows live progress without repeating the completed summary', () => {
    openTests();
    const root: HTMLElement = fixture.nativeElement;
    fixture.componentInstance.status.update(s => s ? {...s, testResults: {available:true, running:true, passed:2, failed:1, skipped:0, total:10, totalKnown:true, expectedTotal:3, label:'Running'}} : s);
    fixture.detectChanges();
    expect(root.querySelector('.test-status')?.textContent).toContain('2 passed · 1 failed');
    expect(root.querySelector('.test-heading .badge')).toBeNull();
    expect(root.querySelector('.current-project-meta')).toBeNull();
    fixture.componentInstance.status.update(s => s ? {...s, testResults: {...s.testResults!, passed:6, total:10}} : s);
    fixture.detectChanges();
    expect(root.querySelector('.test-status')?.textContent).toContain('6 passed');
    expect(root.querySelector('.test-status')?.textContent).toContain('3 expected');
    expect(root.querySelector('.test-bar')).toBeNull();
  });
  it('uses one confirmation for application and monitoring data', async () => {
    chooseCompleteEnvironment();
    const root: HTMLElement = fixture.nativeElement;
    fixture.componentInstance.status.update(s => s ? {...s, monitoring: {enabled:true, storage:'victorialogs'}, components:[{id:'storage',name:'Monitoring database',state:'running',application:false,memoryBytes:0}]} : s);
    fixture.detectChanges();
    (root.querySelector('[aria-label="Restart All"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(root.querySelector<HTMLDialogElement>('dev-environment .maintenance-confirm')!.open).toBeTrue();
    expect(root.querySelector('dev-environment .maintenance-confirm input')).toBeNull();
    (root.querySelector('dev-environment .maintenance-confirm .secondary-button') as HTMLButtonElement).click();
    (root.querySelector('[aria-label="Restart All"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(root.querySelector<HTMLDialogElement>('dev-environment .maintenance-confirm')!.open).toBeTrue();
    TestBed.inject(HttpTestingController).expectNone('actions/restart-devserver');
    (root.querySelector('dev-environment .maintenance-confirm .primary-button') as HTMLButtonElement).click();
    TestBed.inject(HttpTestingController).expectOne('actions/restart-devserver').flush(null);
    await fixture.whenStable();
    expect(root.querySelector<HTMLDialogElement>('dev-environment .maintenance-confirm')!.open).toBeFalse();
  });
  it('keeps a stopped customer application separate from a running development environment', () => {
    const root: HTMLElement = fixture.nativeElement;
    fixture.componentInstance.status.update(s => s ? {...s, project:'fluxzero-dev-server-monitoring',
      maintenance:{busy:false, error:'', restartSupported:true, applicationRestartSupported:false}, components:[
        {id:'app',name:'fluxzero-dev-server-monitoring',state:'stopped',application:true,memoryBytes:null,runningProcesses:0,totalProcesses:1},
        {id:'devserver',name:'Fluxzero Dev Server',state:'running',application:false,memoryBytes:20971520,runningProcesses:1,totalProcesses:1},
        {id:'testserver',name:'Fluxzero Test Server',state:'running',application:false,memoryBytes:104857600,runningProcesses:1,totalProcesses:1}
      ]} : s);
    fixture.detectChanges();
    const rows = root.querySelectorAll('.component-table tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('th')?.textContent).toContain('fluxzero-dev-server-monitoring');
    expect(rows[0].querySelector('.badge')?.textContent).toBe('stopped');
    expect(rows[0].querySelector('dev-resource-detail')).toBeNull();
    expect(rows[0].querySelector('.application-link')).toBeNull();
    expect((root.querySelector('[aria-label="Restart Apps"]') as HTMLButtonElement).disabled).toBeTrue();
    expect(rows[1].querySelector('.badge')?.textContent).toBe('running');
    expect(rows[1].querySelector('.component-memory')?.textContent).toBe('120.0 MiB');
  });
  it('groups processes and exposes component counts and memory in tooltips', () => {
    const root: HTMLElement = fixture.nativeElement;
    fixture.componentInstance.status.update(s => s ? {...s, resourceHistory:[{at:5000,applicationMemory:1048576,devserverMemory:2097152,monitoringStorage:0,componentMemory:{devserver:2097152,storage:0}}], components: [
      {id:'app', name:'Customer', state:'running', application:true, memoryBytes:1048576, runningProcesses:2, totalProcesses:2},
      {id:'devserver', name:'Supervisor', version:'1.2.3', state:'running', application:false, memoryBytes:2097152, memoryUsedBytes:1048576, memoryMaxBytes:4194304, runningProcesses:1, totalProcesses:1},
      {id:'storage', name:'Monitoring database', state:'failed', application:false, memoryBytes:null, runningProcesses:0, totalProcesses:2}
    ]} : s);
    fixture.detectChanges();
    const rows = root.querySelectorAll('.component-table tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('th')?.textContent).toContain('Customer');
    expect(rows[1].querySelector('.badge')?.textContent).toBe('degraded');
    expect(rows[1].querySelector('.badge')?.classList.contains('degraded')).toBeTrue();
    const statusDetail = rows[1].querySelector('dev-resource-detail')!;
    statusDetail.dispatchEvent(new MouseEvent('mouseenter')); fixture.detectChanges();
    expect(statusDetail.querySelector('[role=tooltip]')?.textContent).toContain('Monitoring database');
    expect(statusDetail.querySelector('[role=tooltip]')?.textContent).toContain('failed');
    expect(statusDetail.querySelector('.resource-version')).toBeNull();
    statusDetail.dispatchEvent(new MouseEvent('mouseleave')); fixture.detectChanges();
    expect(statusDetail.querySelector('[role=tooltip]')).toBeNull();
    const memoryDetail = rows[1].querySelector('.component-memory dev-resource-detail')!;
    expect(memoryDetail.querySelector(':scope > dev-resource-chart')).toBeNull();
    expect(rows[1].querySelector('.component-memory dev-resource-graph button')).not.toBeNull();
    memoryDetail.dispatchEvent(new FocusEvent('focus')); fixture.detectChanges();
    expect(memoryDetail.querySelector('[role=dialog]')?.textContent).toContain('Supervisor');
    expect(memoryDetail.querySelectorAll('.resource-version').length).toBe(1);
    expect(memoryDetail.querySelector('.resource-version')?.textContent).toBe('1.2.3');
    expect(memoryDetail.querySelector('[role=dialog]')?.textContent).toContain('1.0 MiB / 4.0 MiB');
    expect(memoryDetail.querySelectorAll('.resource-tooltip dev-resource-graph button.graph-button').length).toBe(2);
    const graphButton = memoryDetail.querySelector<HTMLButtonElement>('.resource-tooltip dev-resource-graph button.graph-button')!;
    graphButton.click(); fixture.detectChanges();
    memoryDetail.dispatchEvent(new MouseEvent('mouseleave')); fixture.detectChanges();
    const graph = memoryDetail.querySelector<HTMLDialogElement>('dialog')!;
    expect(graph.open).toBeTrue();
    expect(graph.textContent).toContain('Supervisor · Memory');
    (graph.querySelector('[aria-label="Close graph"]') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(document.activeElement).toBe(graphButton);
    memoryDetail.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape'})); fixture.detectChanges();
    expect(memoryDetail.querySelector('[role=dialog]')).toBeNull();
    expect(rows[1].querySelector('.component-storage .resource-value[title]')).toBeNull();
    fixture.componentInstance.status.update(s => s ? {...s, components:s.components!.map(c => ({...c, memoryBytes:null, memoryUsedBytes:null}))} : s);
    fixture.detectChanges();
    expect(rows[1].querySelector('.component-memory')?.textContent).toBe('— / 4.0 MiB');
  });
  for (const [states, expected] of [
    ['running,running', 'running'], ['running,stopped', 'degraded'],
    ['running,failed', 'degraded'], ['running,starting', 'degraded'],
    ['stopped,stopped', 'stopped'], ['stopped,failed', 'failed'], ['starting,stopped', 'starting']
  ]) {
    it('summarises ' + states + ' as ' + expected, () => {
      fixture.componentInstance.status.update(s => s ? {...s, components: states.split(',').map((state, i) => ({
        id:'component-' + i, name:'Component ' + i, state, application:false, memoryBytes:null,
        runningProcesses:state === 'running' ? 1 : 0, totalProcesses:1
      }))} : s);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.infrastructure-section .badge').textContent).toBe(expected);
    });
  }
  it('shows degraded when only part of a component process tree is running', () => {
    fixture.componentInstance.status.update(s => s ? {...s, components:[{
      id:'component', name:'Component', state:'running', application:false, memoryBytes:null,
      runningProcesses:1, totalProcesses:2
    }]} : s);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.infrastructure-section .badge').textContent).toBe('degraded');
  });
  it('aligns equally sized status labels in the table and component tooltip', () => {
    fixture.componentInstance.status.update(s => s ? {...s, components: ['running','starting','stopped','failed','degraded'].map(state => ({
      id:state, name:state, state, application:false, memoryBytes:null
    }))} : s);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    root.querySelector('dev-resource-detail')!.dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    const badges = Array.from(root.querySelectorAll<HTMLElement>('.badge'));
    expect(root.querySelectorAll('[role=tooltip] .badge').length).toBe(5);
    for (const badge of badges) {
      expect(badge.getBoundingClientRect().width).toBeCloseTo(badges[0].getBoundingClientRect().width, 1);
      expect(getComputedStyle(badge).textAlign).toBe('left');
      expect(getComputedStyle(badge).justifyContent).toBe('flex-start');
      expect(badge.scrollWidth).toBeLessThanOrEqual(badge.clientWidth);
    }
  });
  it('defaults to stopping only the workspace, confirms, and offers Start after it stops', async () => {
    const http = TestBed.inject(HttpTestingController);
    const root: HTMLElement = fixture.nativeElement;
    (root.querySelector('.stop-action') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(root.querySelector('dialog[open] h2')?.textContent).toBe('Stop workspace?');
    expect(root.querySelector('dialog[open]')?.textContent).toContain('Dashboard controls remain available');
    http.expectNone('actions/stop-workspace');
    (root.querySelector('dialog[open] .primary-button') as HTMLButtonElement).click(); fixture.detectChanges();
    http.expectOne('actions/stop-workspace').flush(null); await fixture.whenStable();
    push({status:{...fixture.componentInstance.status()!, state:'idle', maintenance:{busy:false,error:'',stopSupported:true,workspaceStopped:true}},environments:[]});
    await fixture.whenStable();
    expect(root.textContent).toContain('Workspace stopped.');
    expect(root.querySelector('.restart-action')).toBeNull();
    expect(root.querySelector('.stop-action')?.textContent).toContain('Start');
    expect(root.querySelector('.stop-choice')).toBeNull();
    expect(root.querySelector('#stop-scope-menu')).toBeNull();
    (root.querySelector('.stop-all-action') as HTMLButtonElement).click();fixture.detectChanges();
    expect(root.querySelector('dialog[open] h2')?.textContent).toBe('Stop All?');
    http.expectNone('actions/stop-devserver');
    (root.querySelector('dialog[open] .dialog-cancel') as HTMLButtonElement).click();fixture.detectChanges();
    (root.querySelector('.stop-action') as HTMLButtonElement).click(); fixture.detectChanges();
    http.expectOne('actions/start-workspace').flush(null); await fixture.whenStable();
  });
  it('makes full shutdown optional and explains how to start again', async () => {
    const http = TestBed.inject(HttpTestingController);
    const root: HTMLElement = fixture.nativeElement;
    (root.querySelector('.stop-choice') as HTMLButtonElement).click(); fixture.detectChanges();
    const choices = root.querySelectorAll<HTMLButtonElement>('#stop-scope-menu button');
    expect(choices[0].getAttribute('aria-checked')).toBe('true');
    choices[1].click(); fixture.detectChanges();
    http.expectNone('actions/stop-devserver');
    expect(root.querySelector('.stop-action')?.textContent).toContain('Stop All');
    (root.querySelector('.stop-action') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(root.querySelector('dialog[open]')?.textContent).toContain('run fz dev');
    (root.querySelector('dialog[open] .dialog-cancel') as HTMLButtonElement).click(); fixture.detectChanges();
    http.expectNone('actions/stop-devserver');
    (root.querySelector('.stop-action') as HTMLButtonElement).click(); fixture.detectChanges();
    (root.querySelector('dialog[open] .primary-button') as HTMLButtonElement).click(); fixture.detectChanges();
    http.expectOne('actions/stop-devserver').flush(null); await fixture.whenStable(); fixture.detectChanges();
    expect(root.textContent).toContain('Dev server shutting down.');
    expect(root.querySelector('.stop-action')).toBeNull();
  });
  function chooseCompleteEnvironment() {
    const root: HTMLElement = fixture.nativeElement;
    (root.querySelector('[aria-label="Choose restart scope"]') as HTMLButtonElement).click(); fixture.detectChanges();
    (root.querySelectorAll('#restart-scope-menu button')[1] as HTMLButtonElement).click(); fixture.detectChanges();
  }
  it('remembers the restart scope across dashboard navigation', () => {
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('.restart-action')?.textContent).toContain('Restart Apps');
    chooseCompleteEnvironment();
    (root.querySelector('nav a[href="#application"]') as HTMLAnchorElement).click(); fixture.detectChanges();
    (root.querySelector('nav a[href="#projects"]') as HTMLAnchorElement).click(); fixture.detectChanges();
    expect(root.querySelector('.restart-action')?.textContent).toContain('Restart All');
    TestBed.inject(HttpTestingController).expectNone(request => request.url.startsWith('actions/'));
  });
  it('changes restart scope without executing it and supports keyboard dismissal', () => {
    const root: HTMLElement = fixture.nativeElement;
    const trigger = root.querySelector('[aria-label="Choose restart scope"]') as HTMLButtonElement;
    trigger.click(); fixture.detectChanges();
    const options = root.querySelectorAll<HTMLButtonElement>('#restart-scope-menu button');
    expect(options[1].querySelector('small strong')?.textContent).toBe('This resets all data.');
    expect(document.activeElement).toBe(options[0]);
    options[0].dispatchEvent(new KeyboardEvent('keydown', {key:'ArrowDown',bubbles:true}));
    expect(document.activeElement).toBe(options[1]);
    options[1].click(); fixture.detectChanges();
    expect(root.querySelector('.restart-action')?.textContent).toContain('All');
    expect(document.activeElement).toBe(trigger);
    TestBed.inject(HttpTestingController).expectNone(request => request.url.startsWith('actions/'));
    trigger.click(); fixture.detectChanges();
    trigger.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape',bubbles:true})); fixture.detectChanges();
    expect(root.querySelector('#restart-scope-menu')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });
  for (const [label, action] of [['Restart Apps','restart-application'], ['Restart All','restart-devserver']]) {
    it('dispatches ' + action + ' with confirmation only for the complete environment', async () => {
      if (action === 'restart-devserver') chooseCompleteEnvironment();
      const button = fixture.nativeElement.querySelector('[aria-label="' + label + '"]') as HTMLButtonElement;
      button.click();
      fixture.detectChanges();
      if (action === 'restart-devserver') {
        TestBed.inject(HttpTestingController).expectNone('actions/restart-devserver');
        fixture.nativeElement.querySelector('dev-environment .maintenance-confirm .primary-button').click();
        fixture.detectChanges();
      }
      expect(button.querySelector('.spinner-border[role="status"]')).not.toBeNull();
      expect(button.querySelector('.bi-arrow-clockwise')).toBeNull();
      expect(button.disabled).toBeTrue();
      expect(fixture.nativeElement.querySelectorAll('.spinner-border').length).toBe(1);
      expect(fixture.nativeElement.textContent).not.toContain('Maintenance in progress');
      const request = TestBed.inject(HttpTestingController).expectOne('actions/' + action);
      expect(request.request.headers.get('X-Fluxzero-Console')).toBe('1');
      request.flush(null);
      await fixture.whenStable();
      expect(fixture.nativeElement.querySelector('dev-environment .maintenance-confirm').open).toBeFalse();
      expect(button.querySelector('.spinner-border')).not.toBeNull();
      const status = fixture.componentInstance.status()!;
      push({status:{...status,maintenance:{...status.maintenance,busy:true,error:''}},environments:[]});
      await fixture.whenStable();
      expect(button.querySelector('.spinner-border')).not.toBeNull();
      push({status:{...status,maintenance:{...status.maintenance,busy:false,error:''}},environments:[]});
      await fixture.whenStable();
      expect(button.querySelector('.spinner-border')).toBeNull();
      expect(button.querySelector('.bi-arrow-clockwise')).not.toBeNull();
      expect(button.disabled).toBeFalse();
    });
  }
  it('shows restart progress only after confirmation and restores it after a reconnect snapshot', async () => {
    chooseCompleteEnvironment();
    const root: HTMLElement = fixture.nativeElement;
    const button = root.querySelector('[aria-label="Restart All"]') as HTMLButtonElement;
    button.click(); fixture.detectChanges();
    expect(root.querySelector('.spinner-border')).toBeNull();
    (root.querySelector('dev-environment .maintenance-confirm .primary-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(button.querySelector('.spinner-border[aria-label="Restarting"]')).not.toBeNull();
    expect(button.querySelector('.bi-arrow-clockwise')).toBeNull();
    TestBed.inject(HttpTestingController).expectOne('actions/restart-devserver').flush(null);
    await fixture.whenStable();
    connected(false); await fixture.whenStable();
    expect(button.querySelector('.spinner-border')).not.toBeNull();
    push({status:{...fixture.componentInstance.status()!},environments:[]});
    connected(true); await fixture.whenStable();
    expect(button.querySelector('.spinner-border')).toBeNull();
    expect(button.querySelector('.bi-arrow-clockwise')).not.toBeNull();
    expect(button.disabled).toBeFalse();
  });
  it('restores the icon when the maintenance request fails', async () => {
    const environment = fixture.debugElement.query(By.directive(EnvironmentComponent)).componentInstance as EnvironmentComponent;
    const maintain = spyOn(environment,'maintain').and.callThrough();
    const button = fixture.nativeElement.querySelector('[aria-label="Restart Apps"]') as HTMLButtonElement;
    button.click(); fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('actions/restart-application')
      .flush({error:'Unable to restart application.'},{status:503,statusText:'Unavailable'});
    await maintain.calls.mostRecent().returnValue;
    await fixture.whenStable();
    expect(button.querySelector('.spinner-border')).toBeNull();
    expect(button.disabled).toBeFalse();
    expect(fixture.nativeElement.textContent).toContain('Unable to restart application.');
  });
  it('restores the icon after sixty seconds without a completion update and allows retrying', async () => {
    const nativeTimeout = window.setTimeout.bind(window);
    let expire: () => void = () => {throw Error('Maintenance timeout was not scheduled');};
    spyOn(window,'setTimeout').and.callFake(((handler: TimerHandler, delay?: number, ...args: any[]) => {
      if (delay === 60_000) expire = handler as () => void;
      return nativeTimeout(handler,delay,...args);
    }) as typeof window.setTimeout);
    chooseCompleteEnvironment();
    const button = fixture.nativeElement.querySelector('[aria-label="Restart All"]') as HTMLButtonElement;
    const http = TestBed.inject(HttpTestingController);
    button.click(); fixture.detectChanges();
    fixture.nativeElement.querySelector('dev-environment .maintenance-confirm .primary-button').click(); fixture.detectChanges();
    http.expectOne('actions/restart-devserver').flush(null);
    await fixture.whenStable();
    expect(button.querySelector('.spinner-border')).not.toBeNull();
    connected(false); expire(); await fixture.whenStable();
    expect(button.querySelector('.spinner-border')).toBeNull();
    expect(button.disabled).toBeFalse();
    button.click(); fixture.detectChanges();
    fixture.nativeElement.querySelector('dev-environment .maintenance-confirm .primary-button').click(); fixture.detectChanges();
    expect(button.querySelector('.spinner-border')).not.toBeNull();
    http.expectOne('actions/restart-devserver').flush(null);
    await fixture.whenStable();
    http.verify();
  });
  it('follows system appearance changes while preserving the system preference', () => {
    const system = window.matchMedia('(prefers-color-scheme: dark)');
    const previous = system.matches;
    fixture.componentInstance.setTheme('system');
    expect(fixture.componentInstance.dark()).toBe(previous);
    // Notify the registered listener using the same MediaQueryList instance.
    const media = (fixture.componentInstance as any).systemTheme as MediaQueryList;
    Object.defineProperty(media, 'matches', {configurable:true, value:!previous});
    media.dispatchEvent(new Event('change'));
    expect(fixture.componentInstance.dark()).toBe(!previous);
    expect(localStorage.getItem('dashboardTheme')).toBe('system');
    fixture.componentInstance.setTheme('light');
    media.dispatchEvent(new Event('change'));
    expect(fixture.componentInstance.dark()).toBeFalse();
    delete (media as any).matches;
  });
  it('shows connection health and the theme menu in the sidebar without a top bar', () => {
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('.dashboard-topbar')).toBeNull();
    expect(root.querySelector('.sidebar-footer .theme-trigger')).not.toBeNull();
    expect(root.querySelector('.sidebar-footer')?.textContent).toBe('Connected');
    fixture.componentInstance.error.set('Connection lost');
    fixture.detectChanges();
    expect(root.querySelector('.sidebar-footer')?.textContent).toBe('Disconnected');
    expect(root.querySelector('.error-banner')).toBeNull();
    expect(root.querySelector('.sidebar-footer .bi-plug')).not.toBeNull();
    fixture.componentInstance.error.set('');
    fixture.detectChanges();
    expect(root.querySelector('.sidebar-footer')?.textContent).toBe('Connected');
  });
  it('restricts iframe navigation to known local monitoring views', () => {
    expect(monitoringPath('/messages?term=RegisterTicket')).toBe('/messages?term=RegisterTicket');
    for (const path of ['//example.org', 'https://example.org', '/unknown', '/messages-bad', null]) expect(monitoringPath(path)).toBeNull();
  });
});
