export type CloudCommand = {action:string; [key:string]:string};
export interface CloudOrganization {id:string;name:string;plan:string;applications:number;limit:number;role:string;}
export interface CloudDomain {kind:string;hostname:string;status:string;records:{type:string;name:string;value:string}[];}
export interface CloudProposal {
  id:string;revision:number;expiresAt:number;organization:string;team?:string;plan:string;cluster:string;application:string;
  environment:string;repository:string;branch:string;commit:string;change:string;planImpact:string;
}
export interface CloudSetup {applicationConfigured:boolean;size:string;pipelineConfigured:boolean;workflowPath:string;workflowStatus:'not_checked'|'checked';workflowApplication?:string;workflowCluster?:string;workflowContents?:string;workflowSource?:'suggested'|'local'|'github';}
export interface CloudState {
  version:1;revision:number;
  availability:'available'|'offline'|'forbidden'|'not_connected';
  capabilities?:{actions:string[]};applicationSizes?:string[];branches?:string[];
  organizations:CloudOrganization[];organizationId:string;team?:string;plannedPlan:string;canDeploy:boolean;capacityAvailable:boolean;
  githubConnected:boolean;repositories:string[];repository:string;branch:string;integration:string;
  availableClusters:string[];cluster:string;application:string;environment:string;proposal:CloudProposal|null;
  deployment:{id:string;state:'running'|'failed'|'healthy';commit:string;artifact:string;url:string;steps:{name:string;state:string}[]}|null;
  setup?:CloudSetup;domains:CloudDomain[];emailPreview:Record<string,string>;authTested:boolean;message:string;
}

export interface CloudApi {call(command:CloudCommand):Promise<CloudState>;}
