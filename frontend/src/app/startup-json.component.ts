import {Component, DestroyRef, inject, input, OnInit, signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';

@Component({selector:'dev-startup-json', standalone:true, template:`
  <div class="command-json">
    @if(json(); as content) {
      <div class="json-toolbar"><span>JSON</span><button class="dashboard-action" type="button" (click)="copy()"><i class="bi bi-copy" aria-hidden="true"></i>{{copied() ? 'Copied' : 'Copy'}}</button></div>
      <pre tabindex="0" aria-label="Command JSON">{{content}}</pre>
    } @else if(error()) {<p role="alert">{{error()}} <button type="button" class="dashboard-action" (click)="load()">Retry</button></p>}
    @else {<p role="status">Loading JSON…</p>}
    @if(copyError()) {<p role="alert">Could not copy. Select the JSON to copy it manually.</p>}
    <span class="copy-status" role="status">{{copied() ? 'JSON copied' : ''}}</span>
  </div>`,styles:`
    :host {display:block;min-width:0;}
    .command-json {margin:0 0 16px 26px;padding:12px;border-radius:8px;background:var(--dashboard-action-bg);}
    .json-toolbar {display:flex;align-items:center;justify-content:space-between;color:var(--dashboard-muted);font-size:12px;}
    pre {white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;line-height:1.6;margin:12px 0 0;max-height:400px;overflow:auto;}
    p {font-size:13px;color:var(--dashboard-muted);}
    .copy-status {position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);}
    @media(max-width:650px) {.command-json {margin-left:0;}}
  `})
export class StartupJsonComponent implements OnInit {
  readonly sessionId=input<string>(); readonly id=input.required<string>(); readonly hash=input<string>();
  readonly json=signal(''); readonly error=signal(''); readonly copied=signal(false); readonly copyError=signal(false);
  private readonly http=inject(HttpClient); private readonly destroyRef=inject(DestroyRef);
  ngOnInit() {this.load();}
  load() {
    this.error.set('');
    this.http.post<unknown>('startup-command.json',{sessionId:this.sessionId(),id:this.id(),hash:this.hash()},
      {headers:{'X-Fluxzero-Console':'1'},timeout:10000}).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next:value=>this.json.set(JSON.stringify(value,null,2)),
        error:()=>this.error.set('Could not load this command. It may have changed.')
      });
  }
  async copy() {
    this.copyError.set(false);
    try {await navigator.clipboard.writeText(this.json());this.copied.set(true);}
    catch {this.copied.set(false);this.copyError.set(true);}
  }
}
