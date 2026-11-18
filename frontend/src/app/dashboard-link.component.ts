import {Component, inject} from '@angular/core';
import {AccountConnection} from './account-connection';

@Component({selector:'dev-dashboard-link',standalone:true,template:`
  @if(account.dashboardUrl();as url){<a class="dashboard-link" [href]="url" target="_blank" rel="noopener noreferrer">Dashboard</a>}
  @else{<button class="dashboard-link" type="button" disabled title="Dashboard is unavailable for this connection">Dashboard</button>}
`})
export class DashboardLinkComponent {readonly account=inject(AccountConnection);}
