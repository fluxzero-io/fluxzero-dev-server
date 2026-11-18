import {Component, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, signal} from '@angular/core';
import {AccountConnection} from './account-connection';
import {CloudConnection} from './cloud-api';
import {dashboardDestination} from './cloud-page.component';
import {DashboardLinkComponent} from './dashboard-link.component';

@Component({selector:'dev-publish-setup',standalone:true,imports:[DashboardLinkComponent],templateUrl:'./publish-setup.component.html',styleUrl:'./publish-setup.component.scss'})
export class PublishSetupComponent {
  readonly projectName=input('');
  readonly pageChanged=output<string>();
  readonly account=inject(AccountConnection);
  readonly cloud=inject(CloudConnection);
  readonly data=this.cloud.state;
  readonly organization=computed(()=>this.data()?.organizations.find(o=>o.id===this.data()?.organizationId));
  readonly dashboardUrl=computed(()=>dashboardDestination(this.account.dashboardUrl()));
  readonly githubUrl=computed(()=>dashboardDestination(this.account.dashboardUrl(),'github'));
  readonly storageLocked=computed(()=>(this.account.issue() || this.account.account()?.issue)==='storage_locked');
  readonly accountReady=computed(()=>this.account.signedIn() && !this.storageLocked() && !this.account.account()?.pendingAction);
  readonly applicationReady=computed(()=>this.accountReady() && !!this.organization() && !!this.data()?.setup?.applicationConfigured);
  readonly deliveryReady=computed(()=>this.applicationReady() && !!this.data()?.setup?.pipelineConfigured);
  readonly current=computed(()=>!this.accountReady() ? 0 : !this.organization() ? 1 : !this.applicationReady() ? 2 : !this.deliveryReady() ? 3 : 4);
  readonly editing=signal<number|null>(null);
  readonly expanded=computed(()=>this.editing() ?? this.current());
  readonly organizationName=signal('My team');
  readonly application=signal('parcel-service');
  readonly size=signal('Starter');
  readonly cluster=signal('automatic');
  readonly repository=signal('acme/parcel-service');
  readonly branch=signal('main');
  private context?:string;
  private readonly element=inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector=inject(Injector);
  private focusCurrent(){afterNextRender(()=>this.element.nativeElement.querySelector<HTMLElement>('.setup-timeline .current .primary-button,.setup-timeline .current .sign-in-action,.setup-timeline .current input,.setup-timeline .current select')?.focus({preventScroll:true}),{injector:this.injector});}
  constructor(){
    this.account.initialize();
    effect(()=>{
      const state=this.data();
      const context=state && state.scenario+':'+state.organizationId;
      if(state && context!==this.context){
        this.context=context;this.editing.set(null);
        this.application.set(state.application);this.size.set(state.setup?.size || 'Starter');
        this.cluster.set(state.availableClusters.includes(state.cluster) ? 'existing' : 'automatic');
        this.repository.set(state.repository || state.repositories[0]);this.branch.set(state.branch);
      }
    });
  }
  value(event:Event){return (event.target as HTMLInputElement).value;}
  edit(step:number){this.editing.set(this.expanded()===step ? null : step);}
  async chooseOrganization(id:string){await this.cloud.call({action:'select-organization',organizationId:id});if(!this.cloud.error()){this.editing.set(null);this.focusCurrent();}}
  async createOrganization(){await this.cloud.call({action:'create-organization',name:this.organizationName()});if(!this.cloud.error()){this.editing.set(null);this.focusCurrent();}}
  async saveApplication(){await this.cloud.call({action:'configure-application',application:this.application(),size:this.size(),cluster:this.cluster()});if(!this.cloud.error()){this.editing.set(null);this.focusCurrent();}}
  async connectGitHub(){await this.cloud.call({action:'connect-github'});if(!this.cloud.error())this.focusCurrent();}
  async saveDelivery(){
    await this.cloud.call({action:'select-source',repository:this.repository(),branch:this.branch()});
    if(!this.cloud.error())await this.cloud.call({action:'configure-pipeline'});
    if(!this.cloud.error()){this.editing.set(null);this.focusCurrent();}
  }
  navigate(event:MouseEvent,route:string){if(event.button || event.metaKey || event.ctrlKey || event.altKey || event.shiftKey)return;event.preventDefault();this.pageChanged.emit(route);}
}
