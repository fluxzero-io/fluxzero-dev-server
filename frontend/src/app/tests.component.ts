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
      <div class="tests-card"><div class="test-summary">
      <div class="test-controls">
        <button class="icon-button" type="button" aria-label="Rerun tests" title="Rerun tests"
          [disabled]="startingTests() || state.testResults?.running || !state.testResults?.runnable || busy()" (click)="runTests()"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i></button>
        <div class="test-bar" [class.empty-tests]="!state.testResults?.available" [class.running-tests]="state.testResults?.available && state.testResults.running" role="img"
          [attr.aria-label]="state.testResults?.available ? state.testResults.passed + ' passed, ' + state.testResults.failed + ' failed, ' + testTotal(state.testResults) + ' total' + (state.testResults.skipped ? ', ' + state.testResults.skipped + ' skipped' : '') : 'No test results available'">
          @if(!state.testResults?.available) {<span class="test-empty-label">No test results yet</span>}
          @if(state.testResults; as results) {
            @if(results.available) {
              <span class="test-passed" [style.width.%]="percentage(results.passed, results.totalKnown === false ? 0 : results.total)"></span><span class="test-failed" [style.width.%]="percentage(results.failed, results.totalKnown === false ? 0 : results.total)"></span>
              <div class="test-counts" aria-hidden="true">
                <span class="passed-count"><strong>{{results.passed}}</strong> passed</span><span>/</span>
                <span class="failed-count"><strong>{{results.failed}}</strong> failed</span><span>/</span>
                <span [title]="results.skipped ? results.skipped + ' skipped' : ''"><strong>{{testTotal(results)}}</strong> total</span>
              </div>
            }
          }
        </div>
      </div>
      @if(state.testResults; as results) {
        @if(results.incomplete && !results.running) {<small>Run interrupted or incomplete.</small>}
        @if(results.running) {<small>{{results.label}}@if(results.expectedTotal) { · {{results.live ? '' : '~'}}{{results.expectedTotal}} {{results.live ? 'discovered' : 'expected'}}} @else { · total not yet known}</small>}
      }
      @if(testError()) {<small role="alert">{{testError()}}</small>}
    </div>
    @if(state.testResults?.paused) {<p class="test-pause-status" role="status">Automatic tests paused.@if(state.testResults.running) { Current test run will finish.}</p>}

      </div>
      <dev-test-catalog/>
      <section class="test-output-section" aria-labelledby="output-title">
        <h2 id="output-title">Test output</h2>
        <div class="tests-card"><dev-test-output [lines]="state.testOutput || []" [paused]="!!state.testResults?.paused"
          [pauseBusy]="changingTestPause() || !state.testResults?.runnable || busy()" (toggleTests)="toggleTests()"/></div>
      </section>
      }
    } @else {<p>Connecting to the workspace…</p>}
  </section>`, styles:`
    .tests-page {margin-top:0;}
    .tests-page > header {margin-bottom:28px;}
    .tests-page > header h1 {font-size:32px;font-weight:800;margin:0;}
    .tests-page > header p {color:var(--dashboard-muted);margin:4px 0 0;font-size:14px;}
    dev-test-catalog {display:block;margin-top:24px;}
    .test-output-section {margin-top:24px;}
    .test-output-section h2 {margin-bottom:16px;}
    .test-output-section .tests-card {padding:18px;}
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
  testTotal(results: NonNullable<Status['testResults']>) {
    return results.totalKnown === false ? '?' : String(results.total);
  }
  percentage(value: number, total: number) { return total > 0 ? value * 100 / total : 0; }
}
