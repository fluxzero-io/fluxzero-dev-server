import {ServerUpdateComponent} from './server-update.component';
import {ProgressPageComponent, progressCount} from './progress-page.component';
import {PreviewNavigation} from './preview-navigation';
import {Component, ElementRef, HostListener, inject, computed, signal, ViewChild, OnInit, OnDestroy} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {DomSanitizer, SafeResourceUrl} from '@angular/platform-browser';
import {firstValueFrom} from 'rxjs';
import {Handler, HandleCommand, HandleQuery, HandleEvent, publishEvent, sendCommand} from './dom-handlers';
import {Environment, environmentConsoleUrl, applicationUrl, monitoringPath, monitoringViews, Status} from './models';
import {ProfileSelectorComponent} from './profile-selector.component';
import {ProjectsComponent} from './projects.component';
import {EnvironmentSelectorComponent} from './environment-selector.component';
import {ThemeMenuComponent} from './theme-menu.component';
import {TestsComponent} from './tests.component';
import {StartupPageComponent} from './startup-page.component';
import {EnvironmentComponent} from './environment.component';
import {ConsoleConnection, ConsoleState} from './console-connection';

@Component({selector: 'dev-root', standalone: true, imports: [ServerUpdateComponent, ProgressPageComponent, StartupPageComponent, ProfileSelectorComponent, ProjectsComponent, EnvironmentComponent, TestsComponent, ThemeMenuComponent, EnvironmentSelectorComponent],
  templateUrl: './app.component.html'})
@Handler()
export class AppComponent implements OnInit, OnDestroy {
  readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly http = inject(HttpClient);
  private readonly connection = inject(ConsoleConnection);
  private readonly sanitizer = inject(DomSanitizer);
  @ViewChild('frame') frame?: ElementRef<HTMLIFrameElement>;
  readonly status = signal<Status | undefined>(undefined);
  readonly environments = signal<Environment[]>([]);
  readonly error = signal('');
  readonly actionError = signal('');
  readonly route = signal('application');
  readonly applicationOpened = signal(false);
  readonly applicationUrl = computed(() => applicationUrl(this.status()));
  readonly applicationSource = computed(() => {
    const url = this.applicationUrl();
    return this.applicationOpened() && url ? this.sanitizer.bypassSecurityTrustResourceUrl(url) : undefined;
  });
  readonly preview = new PreviewNavigation();
  @ViewChild('applicationFrame') applicationFrame?: ElementRef<HTMLIFrameElement>;
  readonly previewExpanded = signal(false);
  setPreviewExpanded(expanded: boolean) {
    this.menuOpen.set(false);
    this.previewExpanded.set(expanded);
    setTimeout(() => this.elementRef.nativeElement.querySelector<HTMLElement>(
      '.preview-expand')?.focus());
  }
  readonly previewCopied=signal(false);
  readonly previewCopyError=signal('');
  private previewCopyTimer?:ReturnType<typeof setTimeout>;
  async copyPreviewUrl() {
    const url=this.preview.url() || this.applicationUrl();if(!url) return;
    this.previewCopyError.set('');
    try {
      await navigator.clipboard.writeText(url);this.previewCopied.set(true);
      clearTimeout(this.previewCopyTimer);this.previewCopyTimer=setTimeout(()=>this.previewCopied.set(false),2000);
    } catch {this.previewCopyError.set('Could not copy the URL.');}
  }
  previewAddress() {
    const address=this.preview.url() || this.applicationUrl();
    if(!address) return '';
    try {const url=new URL(address);return url.host + url.pathname + url.search + url.hash;} catch {return '';}
  }
  applicationLoaded() {
    if(this.applicationFrame && this.applicationUrl()) this.preview.connect(this.applicationFrame.nativeElement,this.applicationUrl()!);
  }
  reloadApplication() {if(this.applicationUrl()) this.preview.refresh(this.applicationUrl()!);}
  readonly dark = signal(false);
  readonly themePreference = signal('system');
  private readonly systemTheme = matchMedia('(prefers-color-scheme: dark)');
  private readonly systemThemeChanged = () => { if (this.themePreference() === 'system') this.applyTheme(this.systemTheme.matches); };
  readonly menuOpen = signal(false);
  readonly monitoringExpanded = signal(this.preference('devboard.monitoringExpanded') !== 'false');
  readonly sidebarCollapsed = signal(this.preference('devboard.sidebarCollapsed') === 'true');
  readonly sidebarWidth = signal(Math.max(260, Math.min(520, Number(this.preference('devboard.sidebarWidth')) || 300)));
  readonly mobileNavigation = signal(matchMedia('(max-width: 650px)').matches);
  readonly resizingSidebar = signal(false);
  readonly navigationHidden = computed(() => this.previewExpanded() || (this.mobileNavigation() || this.sidebarCollapsed()) && !this.menuOpen());
  private resizePointer?: number;
  private preference(key:string) {try {return localStorage.getItem(key);} catch {return null;}}
  private savePreference(key:string, value:string) {try {localStorage.setItem(key,value);} catch { /* Keep working without storage. */ }}
  toggleMonitoring() {
    this.monitoringExpanded.update(value=>!value);
    this.savePreference('devboard.monitoringExpanded',String(this.monitoringExpanded()));
  }
  togglePinnedNavigation() {
    if(this.mobileNavigation()) {this.closeNavigation();return;}
    this.sidebarCollapsed.update(value=>!value);
    this.savePreference('devboard.sidebarCollapsed',String(this.sidebarCollapsed()));
    this.menuOpen.set(false);
    if(this.sidebarCollapsed()) this.focusNavigationTrigger();
  }
  openNavigation() {
    this.menuOpen.set(true);
    setTimeout(()=>this.elementRef.nativeElement.querySelector<HTMLElement>('.sidebar-toggle')?.focus());
  }
  closeNavigation() {this.menuOpen.set(false);this.focusNavigationTrigger();}
  private focusNavigationTrigger() {setTimeout(()=>this.elementRef.nativeElement.querySelector<HTMLElement>('.navigation-launcher')?.focus());}
  @HostListener('window:resize') viewportChanged() {
    const mobile=matchMedia('(max-width: 650px)').matches;
    if(mobile!==this.mobileNavigation()) {this.mobileNavigation.set(mobile);this.menuOpen.set(false);this.stopSidebarResize();}
  }
  startSidebarResize(event:PointerEvent) {
    if(event.button!==0 || this.mobileNavigation()) return;
    event.preventDefault();this.resizePointer=event.pointerId;this.resizingSidebar.set(true);
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }
  @HostListener('window:pointermove',['$event']) resizeSidebar(event:PointerEvent) {
    if(!this.resizingSidebar() || event.pointerId!==this.resizePointer) return;
    if(event.clientX<176) {this.stopSidebarResize();this.togglePinnedNavigation();return;}
    this.sidebarWidth.set(Math.max(260,Math.min(520,event.clientX)));
  }
  @HostListener('window:pointerup') @HostListener('window:pointercancel') stopSidebarResize() {
    if(this.resizingSidebar()) this.savePreference('devboard.sidebarWidth',String(this.sidebarWidth()));
    this.resizingSidebar.set(false);this.resizePointer=undefined;
  }
  resizeNavigationWithKeyboard(event:KeyboardEvent) {
    let width=this.sidebarWidth();
    if(event.key==='ArrowLeft') {if(width<=260) {event.preventDefault();this.togglePinnedNavigation();return;}width-=20;}
    else if(event.key==='ArrowRight') width+=20;
    else if(event.key==='Home') width=260;
    else if(event.key==='End') width=520;
    else return;
    event.preventDefault();this.sidebarWidth.set(Math.max(260,Math.min(520,width)));
    this.savePreference('devboard.sidebarWidth',String(this.sidebarWidth()));
  }
  @HostListener('document:keydown',['$event']) navigationKeyboard(event:KeyboardEvent) {
    if(!this.menuOpen()) return;
    if(event.key==='Escape') {event.preventDefault();this.closeNavigation();return;}
    if(event.key!=='Tab') return;
    const elements=Array.from(this.elementRef.nativeElement.querySelectorAll('.dashboard-sidebar a[href], .dashboard-sidebar button:not([disabled]), .dashboard-sidebar [tabindex="0"]')) as HTMLElement[];
    const visible=elements.filter(element=>element.getClientRects().length>0);
    const first=visible[0],last=visible[visible.length-1];
    if(event.shiftKey && document.activeElement===first) {event.preventDefault();last?.focus();}
    else if(!event.shiftKey && document.activeElement===last) {event.preventDefault();first?.focus();}
  }
  readonly views = monitoringViews.filter(view => view.key !== 'visualize');
  readonly current = computed(() => this.environments().find(e => e.projectDirectory === this.status()?.projectDirectory));
  readonly currentName = computed(() => this.current()?.projectName || this.status()?.project || 'Select dev server');
  readonly progressBadge = computed(() => progressCount(this.status()?.progress));
  readonly testBadge = computed(() => {
    if (!this.badgesAvailable()) return null;
    const results=this.status()?.testResults;
    return this.resultBadge(results?.passed || 0, results?.failed || 0, 'passed tests', 'failed tests');
  });
  readonly startupBadge = computed(() => {
    if (!this.badgesAvailable()) return null;
    const startup=this.status()?.startup;
    const failed=startup?.actions.filter(a => a.state === 'failed').length || 0;
    if(startup?.state === 'failed' && !failed) return {value:'!',failed:true,label:'Startup data needs attention'};
    return this.resultBadge(startup?.actions.filter(a => a.state === 'succeeded').length || 0, failed, 'completed startup actions', 'failed startup actions');
  });
  private badgesAvailable() {return this.connected() && !this.status()?.maintenance?.workspaceStopped && this.status()?.state !== 'shutdown';}
  private resultBadge(passed:number, failed:number, successLabel:string, failureLabel:string) {
    return failed > 0 ? {value:String(failed),failed:true,label:failed+' '+failureLabel}
      : passed > 0 ? {value:String(passed),failed:false,label:passed+' '+successLabel} : null;
  }
  frameSource?: SafeResourceUrl;
  private ready = false;
  private navigationId = 0;
  private pendingNavigation?: number;
  private wantedPath = '/messages';
  private readonly paths = new Map<string, string>();


  ngOnInit() {
    let theme: string | null = null;
    try { theme = localStorage.getItem('dashboardTheme'); } catch { /* Storage can be disabled. */ }
    this.setTheme(theme && ['light', 'dark', 'system'].includes(theme) ? theme : 'system');
    this.systemTheme.addEventListener('change', this.systemThemeChanged);
    this.readRoute();
    this.connection.initialise(
      state => publishEvent(this.elementRef.nativeElement, 'consoleUpdate', state),
      connected => this.error.set(connected ? '' : 'Disconnected'));
  }
  ngOnDestroy() { clearTimeout(this.previewCopyTimer); this.preview.dispose(); this.connection.close(); this.systemTheme.removeEventListener('change', this.systemThemeChanged); }

  @HandleQuery('getEnvironments') getEnvironments(): Promise<{environments: Environment[]}> {
    return firstValueFrom(this.http.get<{environments: Environment[]}>('environments.json', {timeout: 10000}));
  }
  @HandleQuery('getStatus') getStatus(): Promise<Status> {
    return firstValueFrom(this.http.get<Status>('status.json', {timeout: 10000}));
  }
  @HandleQuery('getCurrentEnvironment') currentEnvironment() { return this.status(); }

  @HandleEvent('consoleUpdate') applyConsoleUpdate(state: ConsoleState) {
    this.status.set(state.status);
    this.environments.set(state.environments);
    this.ensureFrame();
  }

  link(event: MouseEvent, route: string) {
    if (event.button || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    sendCommand(event.currentTarget as Element, 'navigate', route);
  }
  @HandleCommand('navigate') navigate(route: string) {
    if (route.startsWith('monitoring/')) {
      const path = monitoringPath(route.substring('monitoring'.length));
      if (!path) return;
      const key = path.split(/[/?#]/)[1];
      route = 'monitoring' + (path === '/' + key ? this.paths.get(key) || path : path);
    } else if (!['projects', 'environment', 'application', 'tests', 'startup', 'progress'].includes(route)) return;
    if (location.hash !== '#' + route) history.pushState(null, '', '#' + route);
    this.readRoute();
    this.menuOpen.set(false);
  }
  @HandleCommand('openEnvironment') openEnvironment(environment: Environment) {
    const url = environmentConsoleUrl(environment);
    if (!url) return;
    if (environment.projectDirectory === this.status()?.projectDirectory) this.navigate('application');
    else location.assign(url);
  }
  @HandleCommand('startEnvironment') async startEnvironment(id: string) {
    if (!/^[a-f0-9]{64}$/.test(id)) throw Error('Unknown dev server.');
    const environment = await firstValueFrom(this.http.post<Environment>('projects/' + id + '/start', null,
      {headers: {'X-Fluxzero-Console': '1'}, timeout: 130000}));
    this.environments.update(projects => projects.map(p => p.id === id ? environment : p));
    this.openEnvironment(environment);
  }
  @HandleCommand('renameProject') async renameProject({id, name}: {id: string; name: string}) {
    if (!/^[a-f0-9]{64}$/.test(id)) throw Error('Unknown project.');
    const environment = await firstValueFrom(this.http.post<Environment>('projects/' + id + '/rename', {name},
      {headers: {'X-Fluxzero-Console': '1'}, timeout: 10000}));
    this.environments.update(projects => projects.map(p => p.id === id ? environment : p));
  }
  @HandleCommand('openProjectFolder') openProjectFolder(id: string) { return this.projectAction(id, 'open-folder'); }
  @HandleCommand('forgetProject') forgetProject(id: string) { return this.projectAction(id, 'forget'); }
  private async projectAction(id: string, action: 'open-folder' | 'forget') {
    this.actionError.set('');
    if (!/^[a-f0-9]{64}$/.test(id)) return;
    try {
      await firstValueFrom(this.http.post('projects/' + id + '/' + action, null,
        {headers: {'X-Fluxzero-Console': '1'}, timeout: 10000}));
      if (action === 'forget') this.environments.update(projects => projects.filter(p => p.id !== id));
    } catch (error: any) {
      this.actionError.set(error?.error?.error || (action === 'forget' ? 'Unable to remove the project from the overview.' : 'Unable to open the project folder.'));
    }
  }
  @HandleCommand('updateDevServer') async updateDevServer(version:string) {
    await firstValueFrom(this.http.post('actions/update-devserver', {version},
      {headers: {'X-Fluxzero-Console': '1'}, timeout:10000}));
  }
  @HandleCommand('maintainEnvironment') async maintainEnvironment(action: string) {
    if (action.startsWith('restart-app:')) {
      await firstValueFrom(this.http.post('actions/restart-app', {componentId: action.slice('restart-app:'.length)},
        {headers: {'X-Fluxzero-Console': '1'}, timeout: 10000}));
      return;
    }
    if (!['truncate-data', 'restart-devserver', 'restart-application', 'pause-tests', 'resume-tests', 'stop-workspace', 'start-workspace', 'stop-devserver'].includes(action)) return;
    await firstValueFrom(this.http.post('actions/' + action, null,
      {headers: {'X-Fluxzero-Console': '1'}, timeout: 10000}));
    if (action === 'stop-devserver') {
      this.connection.close();
      this.status.update(state => state ? {...state, state:'shutdown', components:[], monitoring:{enabled:false}} : state);
      this.error.set('Disconnected');
    }
  }
  @HandleCommand('switchProfile') async switchProfile(profile: string) {
    await firstValueFrom(this.http.post('actions/switch-profile', {profile},
      {headers: {'X-Fluxzero-Console': '1'}, timeout: 10000}));
  }
  @HandleCommand('refreshApplication') refreshApplication() {
    this.reloadApplication();
    if (this.frame) { this.ready = false; this.frame.nativeElement.src = this.frame.nativeElement.src; }
  }
  @HandleCommand('runTests') async runTests() {
    await firstValueFrom(this.http.post('actions/run-tests', null,
      {headers: {'X-Fluxzero-Console': '1'}, timeout: 10000}));
  }
  @HandleCommand('clearTestOutput') async clearTestOutput() {
    await firstValueFrom(this.http.post('actions/clear-test-output', null,
      {headers: {'X-Fluxzero-Console': '1'}, timeout: 10000}));
  }
  @HandleCommand('setTheme') setTheme(theme: string) {
    if (!['light', 'dark', 'system'].includes(theme)) return;
    this.themePreference.set(theme);
    try { localStorage.setItem('dashboardTheme', theme); } catch { /* Theme works without storage. */ }
    this.applyTheme(theme === 'system' ? this.systemTheme.matches : theme === 'dark');
  }
  connected() { return !!this.status() && !this.error(); }
  private applyTheme(dark: boolean) {
    this.dark.set(dark);
    const theme = dark ? 'dark' : 'light';
    document.documentElement.setAttribute('data-bs-theme', theme);
    document.documentElement.style.colorScheme = theme;
    this.post({type: 'fluxzero-dashboard-theme', theme});
  }

  @HostListener('window:hashchange') @HostListener('window:popstate') readRoute() {
    let route = location.hash.substring(1) || 'application';
    if (route === 'overview' || route === 'settings') {
      route = 'projects';
      history.replaceState(null, '', '#projects');
    }
    if (route === 'monitoring') route = 'monitoring/messages';
    if (route.startsWith('monitoring') && !monitoringPath(route.substring('monitoring'.length))) route = 'projects';
    if (!route.startsWith('monitoring/') && !['projects', 'environment', 'application', 'tests', 'startup', 'progress'].includes(route)) route = 'projects';
    this.route.set(route);
    if (route !== 'application') this.previewExpanded.set(false);
    if (route === 'application') this.applicationOpened.set(true);
    if (this.isMonitoring()) {
      if(this.preference('devboard.monitoringExpanded') === null) this.monitoringExpanded.set(true);
      this.wantedPath = route.substring('monitoring'.length);
      this.ensureFrame();
      if (this.ready) this.navigateFrame();
    }
  }
  isMonitoring() { return this.route().startsWith('monitoring/'); }
  activeView() { return this.route().split(/[/?#]/)[1]; }
  viewLabel() { return monitoringViews.find(v => v.key === this.activeView())?.label || 'Monitoring'; }
  private ensureFrame() {
    if (!this.frameSource && this.isMonitoring() && this.status()?.monitoring.enabled) {
      const url = new URL('monitoring' + this.wantedPath, document.baseURI);
      url.searchParams.set('iframeId', 'dev-monitoring');
      url.searchParams.set('theme', this.dark() ? 'dark' : 'light');
      this.frameSource = this.sanitizer.bypassSecurityTrustResourceUrl(url.href);
    }
  }
  frameLoaded() {
    this.post({type: 'fluxzero-marketplace-host-navigation-probe', iframeId: 'dev-monitoring'});
  }
  private navigateFrame() {
    this.pendingNavigation = ++this.navigationId;
    this.post({type: 'fluxzero-marketplace-host-navigation', iframeId: 'dev-monitoring',
      path: this.wantedPath, navigationId: this.pendingNavigation});
  }
  private post(message: object) { this.frame?.nativeElement.contentWindow?.postMessage(message, location.origin); }

  @HostListener('window:message', ['$event']) onMessage(event: MessageEvent) {
    if (event.origin !== location.origin || event.source !== this.frame?.nativeElement.contentWindow || !event.data) return;
    const message = event.data;
    if (message.type === 'fluxzero-marketplace-host-navigation-ready') {
      this.ready = true;
      this.applyTheme(this.dark());
      this.navigateFrame();
    } else if (message.type === 'fluxzero-marketplace-navigation') {
      const path = monitoringPath(message.path);
      if (!path || !this.isMonitoring()) return;
      if (this.pendingNavigation !== undefined && message.navigationId !== this.pendingNavigation) return;
      this.pendingNavigation = undefined;
      this.wantedPath = path;
      this.paths.set(path.split(/[/?#]/)[1], path);
      const route = 'monitoring' + path;
      if (location.hash !== '#' + route) {
        if (message.historyAction === 'push') history.pushState(null, '', '#' + route);
        else history.replaceState(null, '', '#' + route);
      }
      this.route.set(route);
    if (route !== 'application') this.previewExpanded.set(false);
    }
  }
}
