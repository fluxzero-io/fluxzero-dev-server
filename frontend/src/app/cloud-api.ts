import {DestroyRef, Injectable, InjectionToken, inject, signal} from '@angular/core';
import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {firstValueFrom, timeout} from 'rxjs';

export const CLOUD_DEMO_ENABLED = new InjectionToken<boolean>('explicit Cloud demo', {
  providedIn:'root',factory:()=> (window as Window & {fluxzeroCloudDemo?:boolean}).fluxzeroCloudDemo === true
});
export type CloudCommand = {action:string; [key:string]:string};
export interface CloudOrganization {id:string;name:string;plan:string;applications:number;limit:number;role:string;}
export interface CloudDomain {kind:string;hostname:string;status:string;records:{type:string;name:string;value:string}[];}
export interface CloudProposal {
  id:string;revision:number;expiresAt:number;organization:string;plan:string;cluster:string;application:string;
  environment:string;repository:string;branch:string;commit:string;change:string;planImpact:string;
}
export interface CloudState {
  version:1;mode:'demo';revision:number;scenario:string;scenarios:{id:string;label:string;group:string}[];
  account:'signed_out'|'signed_in'|'expired'|'locked';accountName:string;availability:'available'|'offline'|'forbidden';
  organizations:CloudOrganization[];organizationId:string;plannedPlan:string;canDeploy:boolean;capacityAvailable:boolean;
  githubConnected:boolean;repositories:string[];repository:string;branch:string;integration:string;
  availableClusters:string[];cluster:string;application:string;environment:string;proposal:CloudProposal|null;
  deployment:{id:string;state:'running'|'failed'|'healthy';commit:string;artifact:string;url:string;steps:{name:string;state:string}[]}|null;
  domains:CloudDomain[];emailPreview:Record<string,string>;authTested:boolean;message:string;
}

/** Replaceable transport boundary; the design fixture does not pretend to implement live Cloud authorization. */
export abstract class CloudApi {abstract call(command:CloudCommand):Promise<CloudState>;}
@Injectable({providedIn:'root'})
export class DemoCloudApi extends CloudApi {
  private readonly http=inject(HttpClient);
  call(command:CloudCommand) {
    return firstValueFrom(this.http.post<CloudState>('cloud-demo/action',command,{headers:{'X-Fluxzero-Console':'1'}}).pipe(timeout(5000)));
  }
}
export const CLOUD_API=new InjectionToken<CloudApi>('Cloud API',{providedIn:'root',factory:()=>inject(DemoCloudApi)});

@Injectable({providedIn:'root'})
export class CloudConnection {
  readonly enabled=inject(CLOUD_DEMO_ENABLED);
  readonly state=signal<CloudState|undefined>(undefined);
  readonly busy=signal(false);
  readonly error=signal('');
  private readonly api=inject(CLOUD_API);
  private started=false;
  private destroyed=false;
  private timer?:ReturnType<typeof setTimeout>;
  private request=0;
  constructor() {inject(DestroyRef).onDestroy(()=>{this.destroyed=true;++this.request;clearTimeout(this.timer);});}
  initialize() {if(this.enabled && !this.started) {this.started=true;void this.call({action:'status'});}}
  async call(command:CloudCommand) {
    if(!this.enabled || this.destroyed || this.busy()) return;
    const request=++this.request;
    clearTimeout(this.timer);
    if(command.action !== 'status') this.busy.set(true);
    this.error.set('');
    try {
      const state=await this.api.call(command);
      if(this.destroyed || request !== this.request) return;
      if(state.version !== 1 || state.mode !== 'demo') throw Error('Unsupported Cloud response');
      this.state.set(state);
    } catch(error) {
      if(!this.destroyed && request === this.request) this.error.set(error instanceof HttpErrorResponse && typeof error.error?.error === 'string'
        ? error.error.error : 'Cloud is unavailable. Try again; your local work is still available.');
    } finally {
      if(!this.destroyed && request === this.request) {
        this.busy.set(false);
        if(this.state()?.deployment?.state === 'running') this.timer=setTimeout(()=>void this.call({action:'status'}),1000);
      }
    }
  }
}
