import {Component, inject} from '@angular/core';
import {AccountConnection} from './account-connection';

@Component({selector:'dev-dashboard-link',standalone:true,template:`
  @if(account.dashboardUrl();as url){<a class="dashboard-link" [href]="url" target="_blank" rel="noopener noreferrer" title="Open the online Fluxzero Cloud dashboard"><img class="dashboard-brand-icon" src="assets/fluxzero-logo-light.svg" alt="" aria-hidden="true">Dashboard</a>}
  @else{<button class="dashboard-link" type="button" disabled title="Dashboard is unavailable for this connection"><img class="dashboard-brand-icon" src="assets/fluxzero-logo-light.svg" alt="" aria-hidden="true">Dashboard</button>}
`})
export class DashboardLinkComponent {readonly account=inject(AccountConnection);}
