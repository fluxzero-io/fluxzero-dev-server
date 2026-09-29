import {matchingFrontend, previewFrontends, applicationUrl, Status} from './models';

describe('Public preview routes',()=>{
  const routes=[{id:'root',path:'/',state:'running'},{id:'inbox',path:'/inbox',state:'running'},
    {id:'nested',path:'/inbox/admin',state:'starting'}];
  it('uses the declared ingress origin instead of the console or private frontend origin',()=>{
    const status={frontends:routes,publicApplicationUrl:'https://app.local:8443'} as Status;
    expect(applicationUrl(status,'http://localhost:4242')).toBe('https://app.local:8443/');
  });
  it('matches the longest whole mount path through deeper navigation',()=>{
    expect(matchingFrontend(routes,'http://localhost/inbox/admin/messages?q=1')?.id).toBe('nested');
    expect(matchingFrontend(routes,'http://localhost/inboxes')?.id).toBe('root');
    expect(matchingFrontend(routes,'http://localhost/inbox#message')?.id).toBe('inbox');
  });
  it('derives only public gateway URLs and rejects private origins and console paths',()=>{
    const invalid=['http://localhost:5173/','//localhost:5173/','/\\evil','/_fluxzero/dev/',
      '/%2fprivate','/inbox/../_fluxzero/dev/','/inbox/%2e%2e/_fluxzero/dev/','/inbox?redirect=1','/%'];
    const status={frontends:[...routes,...invalid.map(path=>({id:'invalid',path,state:'running'}))]} as Status;
    expect(previewFrontends(status)).toEqual(routes);
    expect(applicationUrl(status,'http://localhost:4242')).toBe('http://localhost:4242/');
  });
});
