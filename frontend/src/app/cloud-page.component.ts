import {Component, computed, inject, input, output} from '@angular/core';
import {AccountConnection, accountLink} from './account-connection';

export const cloudPages = [
  {key:'overview',label:'Overview',icon:'house'},
  {key:'applications',label:'Applications',icon:'boxes'},
  {key:'clusters',label:'Clusters',icon:'hdd-network'},
  {key:'integrations',label:'Integrations',icon:'plug'},
  {key:'domains',label:'Domains & email',icon:'globe2'},
];

/** Organization/resource routes need authorized IDs; this entry route is independent. */
export function dashboardDestination(base: string | undefined, destination: 'organizations' | 'github' = 'organizations'): string | undefined {
  const trusted = accountLink(base);
  if(!trusted) return undefined;
  const url = new URL(trusted);
  url.pathname = url.pathname.replace(/\/?$/, '/') + (destination === 'github' ? 'github/organisations' : '');
  url.search = ''; url.hash = '';
  return url.href;
}

@Component({selector:'dev-cloud-page',standalone:true,templateUrl:'./cloud-page.component.html',styleUrl:'./cloud-page.component.scss'})
export class CloudPageComponent {
  readonly section=input('overview');
  readonly projectName=input('');
  readonly pageChanged=output<string>();
  readonly page=computed(()=>cloudPages.find(page=>page.key===this.section()) || cloudPages[0]);
  readonly account=inject(AccountConnection);
  readonly dashboardUrl=computed(()=>dashboardDestination(this.account.account()?.dashboardUrl));
  readonly githubUrl=computed(()=>dashboardDestination(this.account.account()?.dashboardUrl,'github'));
  readonly profileUrl=computed(()=>accountLink(this.account.account()?.accountUrl));
  readonly signInRequired=computed(()=>this.account.account()?.state === 'sign_in_required');
  readonly storageLocked=computed(()=>(this.account.issue() || this.account.account()?.issue) === 'storage_locked');
  readonly notice=computed(()=>{
    const status=this.account.account();
    if(this.storageLocked() || status?.pendingAction === 'sign_out_everywhere') return '';
    return this.account.message() || ((!this.account.signedIn() || status?.issue) ? status?.message : '') || '';
  });
  readonly journey=[
    {key:'integrations',label:'Git & CI',icon:'plug',description:'Connect your source and delivery workflow.'},
    {key:'clusters',label:'Clusters',icon:'hdd-network',description:'Choose where your application will run.'},
    {key:'applications',label:'Applications',icon:'boxes',description:'Manage versions and deployed applications.'},
  ];
  readonly resource=computed(()=>({
    applications:{title:'Manage your applications',description:'Choose an organization and a cluster in Dashboard to view its applications.'},
    clusters:{title:'Choose a deployment destination',description:'Choose an organization in Dashboard to view its clusters and plan.'},
    integrations:{title:'Connect your delivery workflow',description:'Manage GitHub connections, or choose an organization to use its existing CI integrations.'},
    domains:{title:'Use your own domains',description:'Choose an organization in Dashboard to manage application, sign-in and email domains.'},
  }[this.section()] || {title:'Fluxzero Cloud',description:'Continue in Dashboard.'}));

  navigate(event:MouseEvent,page:string) {
    if(event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();this.pageChanged.emit(page);
  }
}
