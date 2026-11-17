import {Component, computed, inject, input} from '@angular/core';
import {AccountConnection, accountLink} from './account-connection';

export const cloudPages = [
  {key:'overview',label:'Overview',icon:'house'},
  {key:'applications',label:'Applications',icon:'boxes'},
  {key:'clusters',label:'Clusters',icon:'hdd-network'},
  {key:'integrations',label:'Integrations',icon:'plug'},
];

@Component({selector:'dev-cloud-page',standalone:true,template:`
  <section class="cloud-page" aria-labelledby="cloud-title">
    <header><span class="eyebrow">Fluxzero Cloud</span><h1 id="cloud-title">{{page().label}}</h1></header>
    @if(account.message() || account.account()?.message) {<p class="cloud-notice" role="status">{{account.message() || account.account()?.message}}</p>}
    @if(account.account() === undefined) {<p role="status">Loading account…</p>}
    @else if(account.account()?.state === 'unavailable') {
      <div class="cloud-card"><h2>Account unavailable</h2><button type="button" class="secondary-button" [disabled]="account.busy()" (click)="account.load()">Try again</button></div>
    }
    @else if(account.account()?.state === 'signing_in') {
      <div class="cloud-card"><h2>Finish signing in</h2><p>Continue in your browser.</p><button type="button" class="secondary-button" [disabled]="account.busy()" (click)="account.act('cancel')">Cancel sign-in</button></div>
    } @else if(!account.signedIn()) {
      <div class="cloud-card"><i class="bi bi-cloud cloud-symbol" aria-hidden="true"></i><h2>Sign in to Fluxzero Cloud</h2><p>Manage your organizations, applications and clusters.</p>
        <button type="button" class="primary-button" [disabled]="account.busy()" (click)="account.act('login')">Sign in</button>
      </div>
    } @else {
      @if(section() === 'overview') {
        <div class="cloud-grid"><div class="cloud-card"><h2>Account</h2><strong>{{account.account()?.displayName || 'Fluxzero account'}}</strong>@if(account.account()?.email){<p>{{account.account()?.email}}</p>}
          @if(profileUrl();as url){<a class="quiet-link" [href]="url" target="_blank" rel="noopener noreferrer">Profile<i class="bi bi-box-arrow-up-right" aria-hidden="true"></i></a>}
        </div><div class="cloud-card"><h2>Organization & plan</h2><p>View your organizations and plans in Dashboard.</p>
          @if(dashboardUrl();as url){<a class="primary-button" [href]="url" target="_blank" rel="noopener noreferrer">Open Dashboard<i class="bi bi-box-arrow-up-right" aria-hidden="true"></i></a>}
        </div></div>
      } @else {
        <div class="cloud-card"><i [class]="'bi bi-' + page().icon + ' cloud-symbol'" aria-hidden="true"></i><h2>{{page().label}}</h2><p>Open Dashboard to choose an organization and manage your {{page().label.toLowerCase()}}.</p>
          @if(dashboardUrl();as url){<a class="primary-button" [href]="url" target="_blank" rel="noopener noreferrer">Open Dashboard<i class="bi bi-box-arrow-up-right" aria-hidden="true"></i></a>}
        </div>
      }
    }
  </section>
`,styles:`
  :host{display:block;min-width:0;}
  .cloud-page{max-width:1050px;margin:0 auto;padding:32px;box-sizing:border-box;color:var(--dashboard-text);}
  header{margin-bottom:28px;}h1{font-size:28px;margin:6px 0 0;font-weight:650;}h2{font-size:18px;margin:0 0 16px;font-weight:600;}
  .eyebrow{color:var(--dashboard-muted);font-size:12px;letter-spacing:.05em;}
  .cloud-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;}
  .cloud-card{padding:28px;border:1px solid var(--dashboard-border);border-radius:12px;background:var(--dashboard-surface);overflow-wrap:anywhere;}
  p{margin:0 0 20px;line-height:1.6;color:var(--dashboard-muted);}strong+p{margin-top:6px;}
  .cloud-symbol{font-size:26px;color:var(--dashboard-active);display:block;margin-bottom:20px;}
  a{display:inline-flex;align-items:center;gap:12px;text-decoration:none;}a.quiet-link{color:var(--dashboard-active);font-size:14px;}
  .cloud-notice{padding:12px 16px;border:1px solid var(--dashboard-border);border-radius:8px;}
  button:focus-visible,a:focus-visible{outline:2px solid var(--dashboard-active);outline-offset:4px;}
  @media(max-width:700px){.cloud-page{padding:22px 16px;}.cloud-grid{grid-template-columns:1fr;}.cloud-card{padding:22px;}}
`})
export class CloudPageComponent {
  readonly section=input('overview');
  readonly page=computed(()=>cloudPages.find(page=>page.key===this.section()) || cloudPages[0]);
  readonly account=inject(AccountConnection);
  readonly dashboardUrl=computed(()=>accountLink(this.account.account()?.dashboardUrl));
  readonly profileUrl=computed(()=>accountLink(this.account.account()?.accountUrl));
}
