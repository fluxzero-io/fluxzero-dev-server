import {Component, ElementRef, HostListener, inject, signal, ViewChild, OnInit, OnDestroy} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {DomSanitizer, SafeResourceUrl} from '@angular/platform-browser';
import {firstValueFrom} from 'rxjs';
import {Handler, HandleCommand, HandleQuery, HandleEvent, publishEvent, sendCommand} from './dom-handlers';
import {Environment, monitoringPath, monitoringViews, Status} from './models';
import {ProjectsComponent} from './projects.component';
import {ThemeMenuComponent} from './theme-menu.component';
import {EnvironmentComponent} from './environment.component';
import {ConsoleConnection, ConsoleState} from './console-connection';

@Component({selector: 'dev-root', standalone: true, imports: [ProjectsComponent, EnvironmentComponent, ThemeMenuComponent],
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
  readonly route = signal('projects');
  readonly dark = signal(false);
  readonly themePreference = signal('system');
  private readonly systemTheme = matchMedia('(prefers-color-scheme: dark)');
  private readonly systemThemeChanged = () => { if (this.themePreference() === 'system') this.applyTheme(this.systemTheme.matches); };
  readonly menuOpen = signal(false);
  readonly monitoringExpanded = signal(true);
  readonly views = monitoringViews;
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
  ngOnDestroy() { this.connection.close(); this.systemTheme.removeEventListener('change', this.systemThemeChanged); }

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
    } else if (!['projects', 'environment'].includes(route)) return;
    if (location.hash !== '#' + route) history.pushState(null, '', '#' + route);
    this.readRoute();
    this.menuOpen.set(false);
  }
  @HandleCommand('openEnvironment') openEnvironment(environment: Environment) {
    if (!environment.consoleUrl || environment.status !== 'running') return;
    if (environment.projectDirectory === this.status()?.projectDirectory) this.navigate('projects');
    else {
      const url = new URL(environment.consoleUrl);
      if (url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) {
        url.hash = 'projects';
        location.assign(url.href);
      }
    }
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
  @HandleCommand('maintainEnvironment') async maintainEnvironment(action: string) {
    if (!['truncate-data', 'restart-devserver', 'restart-application'].includes(action)) return;
    await firstValueFrom(this.http.post('actions/' + action, null,
      {headers: {'X-Fluxzero-Console': '1'}, timeout: 10000}));
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
    let route = location.hash.substring(1) || 'projects';
    if (route === 'overview' || route === 'settings') {
      route = 'projects';
      history.replaceState(null, '', '#projects');
    }
    if (route === 'monitoring') route = 'monitoring/messages';
    if (route.startsWith('monitoring') && !monitoringPath(route.substring('monitoring'.length))) route = 'projects';
    if (!route.startsWith('monitoring/') && !['projects', 'environment'].includes(route)) route = 'projects';
    this.route.set(route);
    if (this.isMonitoring()) {
      this.monitoringExpanded.set(true);
      this.wantedPath = route.substring('monitoring'.length);
      this.ensureFrame();
      if (this.ready) this.navigateFrame();
    }
  }
  isMonitoring() { return this.route().startsWith('monitoring/'); }
  activeView() { return this.route().split(/[/?#]/)[1]; }
  viewLabel() { return this.views.find(v => v.key === this.activeView())?.label || 'Monitoring'; }
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
    }
  }
}
