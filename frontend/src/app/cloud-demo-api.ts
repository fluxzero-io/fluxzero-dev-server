import {DestroyRef, Injectable, InjectionToken, inject, signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {firstValueFrom, timeout} from 'rxjs';
import {CloudApi, CloudCommand, CloudState} from './cloud-model';
import {githubWorkflowSnippet} from './github-workflow';

export const CLOUD_DEMO_ENABLED=new InjectionToken<boolean>('explicit Cloud fixture',{providedIn:'root',factory:()=> (window as Window & {fluxzeroCloudDemo?:boolean}).fluxzeroCloudDemo===true});
export interface DemoCloudState extends CloudState {
  mode:'demo';scenario:string;scenarios:{id:string;label:string;group:string}[];
  account:'signed_out'|'signed_in'|'expired'|'locked';accountName:string;
}
export function fixtureAccountStatus(state:DemoCloudState){return {state:state.account==='expired' ? 'sign_in_required' as const : state.account==='signed_out' ? 'signed_out' as const : 'signed_in' as const,displayName:state.accountName,email:'alex@acme.example',environment:'Cloud fixture',issue:state.account==='locked' ? 'storage_locked' : undefined};}
/** Fixture metadata and examples stay at this boundary, outside the application views. */
export function cloudFixtureState(raw:DemoCloudState):CloudState {
  const {mode,scenario,scenarios,account,accountName,...state}=raw;
  const plan=state.organizations.find(org=>org.id===state.organizationId)?.plan;
  return {...state,capabilities:{actions:['sign-in','sign-out','unlock','recover',...(account==='signed_in' ? ['select-organization','create-organization'] : []),...(state.canDeploy ? ['configure-application','connect-github','select-source','configure-pipeline'] : [])]},applicationSizes:plan==='Pro' ? ['Starter','Small'] : ['Starter'],branches:['main','release'],setup:state.setup ? {...state.setup,workflowContents:githubWorkflowSnippet,workflowSource:'suggested'} : undefined};
}
@Injectable({providedIn:'root'})
export class DemoCloudApi implements CloudApi {
  private readonly http=inject(HttpClient);
  private request=0;
  readonly session=signal<DemoCloudState|undefined>(undefined);
  constructor(){inject(DestroyRef).onDestroy(()=>++this.request);}
  async call(command:CloudCommand):Promise<CloudState>{
    const request=++this.request;
    const raw=await firstValueFrom(this.http.post<DemoCloudState>('cloud-demo/action',command,{headers:{'X-Fluxzero-Console':'1'}}).pipe(timeout(5000)));
    if(raw.version!==1 || raw.mode!=='demo')throw Error('Unsupported fixture response');
    if(request===this.request)this.session.set(raw);
    return cloudFixtureState(raw);
  }
}
