import {Component, HostListener, computed, effect, inject, input, output, signal} from '@angular/core';
import {CloudConnection} from './cloud-api';

@Component({selector:'dev-cloud-demo',standalone:true,templateUrl:'./cloud-demo.component.html',styleUrl:'./cloud-demo.component.scss'})
export class CloudDemoComponent {
  readonly section=input('overview');
  readonly projectName=input('');
  readonly pageChanged=output<string>();
  readonly cloud=inject(CloudConnection);
  readonly data=this.cloud.state;
  readonly organization=computed(()=>this.data()?.organizations.find(o=>o.id===this.data()?.organizationId));
  readonly healthy=computed(()=>this.data()?.deployment?.state==='healthy');
  readonly sourceNeeded=computed(()=>!this.data()?.githubConnected || !this.data()?.repository);
  readonly title=computed(()=>({overview:'Overview',applications:'Applications',clusters:'Clusters',integrations:'Git & CI',domains:'Domains & email'}[this.section()] || 'Overview'));
  readonly organizationName=signal('My team');
  readonly repository=signal('acme/parcel-service');
  readonly branch=signal('main');
  readonly application=signal('parcel-service');
  readonly environment=signal('Production');
  readonly cluster=signal('existing');
  readonly applicationDomain=signal('app.acme.example');
  readonly authDomain=signal('login.acme.example');
  readonly emailDomain=signal('mail.acme.example');
  readonly editingDestination=signal(false);
  readonly reviewing=signal(false);
  readonly appPreview=signal(false);
  readonly provider=signal(false);
  readonly copied=signal('');
  readonly groups=['Start','Delivery','Domains','Recovery'];
  private returnFocus?:HTMLElement;
  private destinationOrganization?:string;
  constructor() {
    this.cloud.initialize();
    effect(()=>{
      const state=this.data();
      if(state && this.destinationOrganization !== state.organizationId) {
        this.destinationOrganization=state.organizationId;
        this.cluster.set(state.availableClusters.length ? 'existing' : 'new');
      }
    });
  }
  value(event:Event) {return (event.target as HTMLInputElement).value;}
  async scenario(value:string) {
    this.closeDialog();this.editingDestination.set(false);
    await this.cloud.call({action:'scenario',scenario:value});
    const data=this.data();if(data) {this.application.set(data.application);this.environment.set(data.environment);this.repository.set(data.repository || 'acme/parcel-service');this.branch.set(data.branch);}
  }
  async selectSource() {await this.cloud.call({action:'select-source',repository:this.repository(),branch:this.branch()});}
  async configure() {
    await this.cloud.call({action:'configure',application:this.application(),environment:this.environment(),cluster:this.cluster()});
    if(!this.cloud.error()) {this.editingDestination.set(false);this.pageChanged.emit('cloud/overview');}
  }
  async review() {
    this.returnFocus=document.activeElement as HTMLElement;
    await this.cloud.call({action:'review'});
    if(this.data()?.proposal && !this.cloud.error()) {this.reviewing.set(true);setTimeout(()=>document.querySelector<HTMLElement>('.cloud-proposal .dialog-cancel')?.focus());}
  }
  async deploy() {
    const proposal=this.data()?.proposal;if(!proposal) return;
    await this.cloud.call({action:'deploy',proposalId:proposal.id});
    if(!this.cloud.error()) {this.reviewing.set(false);this.pageChanged.emit('cloud/overview');}
  }
  async configureDomains() {
    await this.cloud.call({action:'configure-domains',applicationDomain:this.applicationDomain(),authDomain:this.authDomain(),emailDomain:this.emailDomain()});
  }
  async copy(value:string) {
    try {await navigator.clipboard.writeText(value);this.copied.set(value);} catch {this.copied.set('');}
  }
  navigate(event:MouseEvent,page:string) {
    if(event.button || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();this.pageChanged.emit(page);
  }
  closeDialog() {this.reviewing.set(false);this.appPreview.set(false);this.provider.set(false);this.returnFocus?.focus();}
  openProvider() {
    this.returnFocus=document.activeElement as HTMLElement;this.provider.set(true);
    setTimeout(()=>document.querySelector<HTMLElement>('.provider-dialog .dialog-cancel')?.focus());
  }
  async connectProvider() {await this.cloud.call({action:'connect-github'});if(!this.cloud.error()) this.closeDialog();}
  async cancelProvider() {await this.cloud.call({action:'cancel-github'});this.closeDialog();}
  openApplication() {
    this.returnFocus=document.activeElement as HTMLElement;this.appPreview.set(true);
    setTimeout(()=>document.querySelector<HTMLElement>('.app-demo .dialog-cancel')?.focus());
  }
  @HostListener('document:keydown',['$event']) trapFocus(event:KeyboardEvent) {
    if(event.key!=='Tab' || !this.reviewing() && !this.appPreview() && !this.provider()) return;
    const dialog=document.querySelector<HTMLElement>('.cloud-proposal,.app-demo,.provider-dialog');
    const items=Array.from(dialog?.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],input:not(:disabled),select:not(:disabled)') || []);
    if(!items.length) {event.preventDefault();dialog?.focus();return;}
    if(event.shiftKey && document.activeElement===items[0]) {event.preventDefault();items.at(-1)?.focus();}
    else if(!event.shiftKey && document.activeElement===items.at(-1)) {event.preventDefault();items[0].focus();}
  }
  @HostListener('document:keydown.escape',['$event']) escape(event:Event) {
    if((this.reviewing() || this.appPreview() || this.provider()) && !this.cloud.busy()) {event.preventDefault();this.closeDialog();}
  }
}
