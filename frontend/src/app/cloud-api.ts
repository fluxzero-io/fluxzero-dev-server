import {DestroyRef, Injectable, InjectionToken, inject, signal} from '@angular/core';
import {HttpErrorResponse} from '@angular/common/http';
import {CloudApi, CloudCommand, CloudState} from './cloud-model';
import {CLOUD_DEMO_ENABLED, DemoCloudApi} from './cloud-demo-api';
export * from './cloud-model';
export {CLOUD_DEMO_ENABLED} from './cloud-demo-api';

/** The native Cloud adapter can replace this provider without changing any views. */
@Injectable({providedIn:'root'})
export class UnconnectedCloudApi implements CloudApi {
  async call(command:CloudCommand):Promise<CloudState>{
    if(command.action!=='status')throw Error('Connect Cloud through Dashboard to continue.');
    return {version:1,revision:0,availability:'not_connected',capabilities:{actions:[]},organizations:[],organizationId:'',plannedPlan:'',canDeploy:false,capacityAvailable:false,githubConnected:false,repositories:[],repository:'',branch:'',integration:'',availableClusters:[],cluster:'',application:'',environment:'',proposal:null,deployment:null,domains:[],emailPreview:{},authTested:false,message:''};
  }
}
export const CLOUD_API=new InjectionToken<CloudApi>('Cloud API',{providedIn:'root',factory:()=>inject(CLOUD_DEMO_ENABLED) ? inject(DemoCloudApi) : inject(UnconnectedCloudApi)});

@Injectable({providedIn:'root'})
export class CloudConnection {
  readonly state=signal<CloudState|undefined>(undefined);
  readonly busy=signal(false);
  readonly error=signal('');
  private readonly api=inject(CLOUD_API);
  private started=false;
  private destroyed=false;
  private timer?:ReturnType<typeof setTimeout>;
  private request=0;
  constructor() {inject(DestroyRef).onDestroy(()=>{this.destroyed=true;++this.request;clearTimeout(this.timer);});}
  initialize() {if(!this.started) {this.started=true;void this.call({action:'status'});}}
  supports(action:string){return !!this.state()?.capabilities?.actions.includes(action);}
  async call(command:CloudCommand) {
    if(this.destroyed || this.busy()) return;
    if(command.action!=='status' && !this.supports(command.action)){this.error.set('This action is not available for this Cloud connection.');return;}
    const request=++this.request;
    clearTimeout(this.timer);
    if(command.action !== 'status') this.busy.set(true);
    this.error.set('');
    try {
      const state=await this.api.call(command);
      if(this.destroyed || request !== this.request) return;
      if(state.version !== 1 || !Array.isArray(state.organizations)) throw Error('Unsupported Cloud response');
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
