import {Component, ElementRef, inject, input, signal} from '@angular/core';
import {Status} from './models';
import {TestOutputComponent} from './test-output.component';
import {TestCatalogComponent} from './test-catalog.component';
import {Handler, sendCommand} from './dom-handlers';

@Component({selector:'dev-tests', standalone:true, imports:[TestOutputComponent, TestCatalogComponent], template:`
  <section class="page tests-page environment-tests" aria-labelledby="tests-title">
    <header class="page-heading"><div><h1 id="tests-title">Tests</h1><p>Explore the scenarios checked by your tests.</p></div></header>
    @if(status(); as state) {
      @if(state.maintenance?.workspaceStopped || state.state === 'shutdown') {
        <p class="workspace-stopped" role="status">Workspace stopped. Start it from <a href="#projects">Dev environment</a> to run tests.</p>
      } @else {
      <div class="tests-card">
        <dev-test-catalog>
          <div test-actions class="test-actions">
            <button class="icon-button" type="button" aria-label="Rerun" title="Rerun"
              [disabled]="startingTests() || state.testResults?.running || !state.testResults?.runnable || busy()" (click)="runTests()">
              <i class="bi bi-arrow-clockwise" aria-hidden="true"></i><span>Rerun</span>
            </button>
            <button class="icon-button" type="button" [attr.aria-label]="state.testResults?.paused ? 'Resume' : 'Pause'"
              [title]="state.testResults?.paused ? 'Resume automatic tests' : 'Pause automatic tests'"
              [disabled]="changingTestPause() || !state.testResults?.runnable || busy()" [attr.aria-pressed]="!!state.testResults?.paused" (click)="toggleTests()">
              <i [class]="state.testResults?.paused ? 'bi bi-play-fill' : 'bi bi-pause-fill'" aria-hidden="true"></i>
              <span>{{state.testResults?.paused ? 'Resume' : 'Pause'}}</span>
            </button>
          </div>
          <div test-status class="test-status" aria-live="polite">
            @if(state.testResults; as results) {
              @if(results.incomplete && !results.running) {<p>Run interrupted or incomplete.</p>}
              @if(results.running) {<p>Running tests · {{results.passed}} passed · {{results.failed}} failed@if(results.expectedTotal) { · {{results.expectedTotal}} {{results.live ? 'discovered' : 'expected'}}}</p>}
              @if(results.paused) {<p class="test-pause-status">Automatic tests paused.@if(results.running) { Current test run will finish.}</p>}
            }
            @if(testError()) {<p role="alert">{{testError()}}</p>}
          </div>
          <dev-test-output test-output [lines]="state.testOutput || []"/>
        </dev-test-catalog>
      </div>
      }
    } @else {<p>Connecting to the workspace…</p>}
  </section>`, styles:`
    .tests-page {margin-top:0;}
    .tests-page > header {margin-bottom:28px;}
    .tests-page > header h1 {font-size:32px;font-weight:800;margin:0;}
    .tests-page > header p {color:var(--dashboard-muted);margin:4px 0 0;font-size:14px;}
    .test-actions {display:flex;flex-direction:column;align-items:flex-end;gap:2px;}
    .test-actions .icon-button {width:auto;height:30px;padding:0 6px;gap:6px;font-size:12px;white-space:nowrap;}
    .test-status {font-size:12px;color:var(--dashboard-muted);}
    .test-status p {margin:0 0 14px;}
    @media(max-width:650px) {.tests-page > header h1 {font-size:28px;}}
  `})
@Handler()
export class TestsComponent {
  readonly status = input<Status>();
  readonly elementRef = inject<ElementRef<HTMLElement>>(ElementRef);
  busy() {return !!this.status()?.maintenance?.busy || !!this.status()?.maintenance?.workspaceStopped || this.status()?.state === 'shutdown';}
  readonly changingTestPause = signal(false);
  async toggleTests() {
    if (this.changingTestPause()) return;
    this.changingTestPause.set(true); this.testError.set('');
    try { await sendCommand<Promise<void>>(this.elementRef.nativeElement, 'maintainEnvironment',
      this.status()?.testResults?.paused ? 'resume-tests' : 'pause-tests'); }
    catch (error: any) { this.testError.set(error?.error?.error || 'Unable to change automatic tests.'); }
    finally { this.changingTestPause.set(false); }
  }
  readonly startingTests = signal(false);
  readonly testError = signal('');
  async runTests() {
    this.startingTests.set(true);this.testError.set('');
    try {await sendCommand<Promise<void>>(this.elementRef.nativeElement, 'runTests');}
    catch(error:any) {this.testError.set(error?.error?.error || 'Unable to start tests.');}
    finally {this.startingTests.set(false);}
  }
}
