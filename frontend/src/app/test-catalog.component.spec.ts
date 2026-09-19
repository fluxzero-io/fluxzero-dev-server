import {ComponentFixture, TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting, HttpTestingController} from '@angular/common/http/testing';
import {TestCatalogComponent, TestPage} from './test-catalog.component';

describe('Scenario catalog', () => {
  let fixture:ComponentFixture<TestCatalogComponent>;
  let http:HttpTestingController;
  const empty:TestPage = {items:[],total:0,offset:0,pageSize:50,counts:{passed:286,failed:0,pending:0,skipped:0}};
  beforeEach(() => {
    TestBed.configureTestingModule({imports:[TestCatalogComponent],providers:[provideHttpClient(),provideHttpClientTesting()]});
    http=TestBed.inject(HttpTestingController);fixture=TestBed.createComponent(TestCatalogComponent);fixture.detectChanges();
  });
  afterEach(() => {fixture.destroy();http.verify();});
  it('defaults to All and filters failures while rendering names safely', () => {
    const initial=http.expectOne(r=>r.url==='tests.json');
    expect(initial.request.params.get('state')).toBe('all');
    initial.flush({...empty,total:1,items:[{key:'a',project:'shop',suite:'Checkout',name:'<img src=x> Customer can pay',state:'passed',source:'id'}]});fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.scenario-name').textContent).toContain('<img src=x>');
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(fixture.nativeElement.querySelector('.scenario-state').textContent).toBe('Passed');
    const filters=Array.from(fixture.nativeElement.querySelectorAll('.scenario-filters button') as NodeListOf<HTMLButtonElement>);
    expect(filters.map(b=>b.textContent?.trim().replace(/\d+$/, '').trim())).toEqual(['All','Passed','Failed','Skipped','Output']);
    expect(filters[0].getAttribute('aria-pressed')).toBe('true');
    filters[2].click();fixture.detectChanges();
    const request=http.expectOne(r=>r.url==='tests.json');expect(request.request.params.get('state')).toBe('failed');
    request.flush(empty);fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No failed tests.');
  });
  it('opens output without requesting an output result filter and keeps counts', () => {
    http.expectOne(r=>r.url==='tests.json').flush(empty);fixture.detectChanges();
    fixture.componentInstance.select('output');fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.output-panel').hidden).toBeFalse();
    expect(fixture.nativeElement.querySelector('.scenario-results')).toBeNull();
    expect(fixture.nativeElement.querySelector('.output-tab').getAttribute('aria-pressed')).toBe('true');
    fixture.componentInstance.refresh();http.expectNone(r=>r.url==='tests.json');
    fixture.componentInstance.select('all');fixture.detectChanges();
    http.expectOne(r=>r.url==='tests.json').flush(empty);
    expect(fixture.nativeElement.querySelector('.output-panel').hidden).toBeTrue();
  });
  it('shows failures and incomplete tests as distinct states, then searches and resets pagination', () => {
    http.expectOne(r=>r.url==='tests.json').flush({...empty,total:72,counts:{passed:286,failed:1,pending:71},items:[
      {key:'a',project:'shop',suite:'Checkout',name:'Customer can pay',state:'failed',source:'id'},
      {key:'b',project:'shop',suite:'Checkout',name:'Customer can cancel',state:'pending',source:'id2'}]});fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.scenario-row').length).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('Not completed');
    fixture.componentInstance.loadMore();
    const refresh=http.expectOne(r=>r.url==='tests.json' && r.params.get('offset')==='0');
    const page=http.expectOne(r=>r.url==='tests.json' && r.params.get('offset')==='50');
    const input=fixture.nativeElement.querySelector('input') as HTMLInputElement;input.value='payment';input.dispatchEvent(new Event('input'));fixture.detectChanges();
    expect(page.cancelled).toBeTrue();expect(refresh.cancelled).toBeTrue();
    const search=http.expectOne(r=>r.url==='tests.json');expect(search.request.params.get('q')).toBe('payment');expect(search.request.params.get('offset')).toBe('0');
    search.flush(empty);fixture.detectChanges();expect(fixture.nativeElement.textContent).toContain('No matching scenarios');
  });
  function results(offset:number, total=120):TestPage {
    return {...empty,offset,total,counts:{passed:total,failed:0,pending:0,skipped:0},items:Array.from({length:Math.min(50,total-offset)},(_,i)=>({
      key:String(offset+i),project:'shop',suite:'Checkout',name:'Scenario '+(offset+i),state:'passed',source:'id'+(offset+i)}))};
  }
  function respond(offset:number, total=120) {
    http.expectOne(r=>r.url==='tests.json' && r.params.get('offset')===String(offset)).flush(results(offset,total));
  }
  it('adds fifty entries and preserves expanded results during live refresh', () => {
    respond(0);fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('50 of 120 entries shown');
    (fixture.nativeElement.querySelector('.scenario-pagination button') as HTMLButtonElement).click();
    fixture.componentInstance.loadMore();
    respond(0);respond(50);fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.scenario-row').length).toBe(100);
    fixture.componentInstance.refresh();respond(0);respond(50);fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.scenario-row').length).toBe(100);
    fixture.componentInstance.loadMore();respond(0);respond(50);respond(100);fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('120 of 120 entries shown');
    expect(fixture.nativeElement.querySelector('.scenario-pagination button')).toBeNull();
    fixture.componentInstance.select('passed');fixture.detectChanges();respond(0);fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.scenario-row').length).toBe(50);
  });
  it('keeps rows on failed expansion and deduplicates results when the catalog shrinks', () => {
    respond(0);fixture.componentInstance.loadMore();respond(0);
    http.expectOne(r=>r.url==='tests.json').flush(null,{status:503,statusText:'Unavailable'});fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.scenario-row').length).toBe(50);
    fixture.componentInstance.refresh();respond(0,20);
    http.expectOne(r=>r.url==='tests.json' && r.params.get('offset')==='50').flush(results(0,20));fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.scenario-row').length).toBe(20);
    expect(fixture.nativeElement.textContent).toContain('20 of 20 entries shown');
    fixture.componentInstance.refresh();respond(0,20);
  });
  it('collapses parameterized groups by default, exposes failures and fetches variants only on expansion', () => {
    const group={key:'template',project:'shop',suite:'Validation',name:'Validates input',state:'failed',source:'template',variants:{passed:74,failed:1}};
    const grouped={...empty,total:1,items:[group],counts:{passed:74,failed:1}};
    http.expectOne(r=>r.url==='tests.json' && r.params.get('grouped')==='true').flush(grouped);fixture.detectChanges();
    let toggle=fixture.nativeElement.querySelector('.scenario-group') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(toggle.textContent).toContain('75 scenarios');expect(toggle.textContent).toContain('1 failed');
    http.expectNone(r=>r.params.has('group'));
    toggle.click();fixture.detectChanges();
    const variants=http.expectOne(r=>r.params.get('group')==='template');
    variants.flush({...results(0,75),items:results(0,75).items.map(t=>({...t,name:'Variant '+t.key}))});fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Variant 0');
    fixture.componentInstance.refresh();http.expectOne(r=>!r.params.has('group')).flush(grouped);fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(fixture.nativeElement.textContent).toContain('Variant 0');
    toggle.click();fixture.detectChanges();expect(fixture.nativeElement.textContent).not.toContain('Variant 0');
    fixture.componentInstance.select('failed');fixture.detectChanges();
    http.expectOne(r=>r.params.get('state')==='failed').flush({...grouped,items:[{...group,variants:{failed:1}}]});fixture.detectChanges();
    toggle=fixture.nativeElement.querySelector('.scenario-group');toggle.click();fixture.detectChanges();
    const failed=http.expectOne(r=>r.params.get('group')==='template' && r.params.get('state')==='failed');failed.flush({...results(0,1)});
  });
  it('shows an error instead of implying missing results are passing and cancels work on navigation', () => {
    http.expectOne(r=>r.url==='tests.json').flush(null,{status:503,statusText:'Unavailable'});fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('Unable to refresh');
    expect(fixture.nativeElement.textContent).not.toContain('No tests need attention');
    fixture.componentInstance.refresh();const retry=http.expectOne(r=>r.url==='tests.json');
    fixture.destroy();expect(retry.cancelled).toBeTrue();
  });
});
