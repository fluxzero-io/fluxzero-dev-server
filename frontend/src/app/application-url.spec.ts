import {applicationUrl, Status} from './models';

describe('Application preview URL', () => {
  const status = (url: string, application = true) => ({components: [{id: 'app', url, application}]} as Status);
  it('accepts application paths, queries and hashes on the active gateway', () => {
    expect(applicationUrl(status('http://localhost:4200/rooms?a=1#lights'), 'http://localhost:4200'))
      .toBe('http://localhost:4200/rooms?a=1#lights');
  });
  it('waits for the frontend even when the backend already has a public URL', () => {
    const state = status('http://localhost:4200/');
    state.components!.push({id: 'frontend-ui', name: 'UI', state: 'starting', application: true, memoryBytes: null, url: null});
    state.frontend = 'starting';
    expect(applicationUrl(state, 'http://localhost:4200')).toBeNull();
    state.frontend = 'running';
    expect(applicationUrl(state, 'http://localhost:4200')).toBe('http://localhost:4200/');
  });
  it('rejects external, credentialed, malformed and recursive console URLs', () => {
    for (const url of ['http://evil.test/', 'javascript:alert(1)', 'http://user:pass@localhost:4200/',
      'http://localhost:4300/', 'http://localhost:4200/_fluxzero/dev/', 'http://localhost:4200/%5ffluxzero/dev/', 'invalid']) {
      expect(applicationUrl(status(url), 'http://localhost:4200')).withContext(url).toBeNull();
    }
    expect(applicationUrl(status('http://localhost:4200/', false), 'http://localhost:4200')).toBeNull();
    expect(applicationUrl(undefined, 'http://localhost:4200')).toBeNull();
  });
});
