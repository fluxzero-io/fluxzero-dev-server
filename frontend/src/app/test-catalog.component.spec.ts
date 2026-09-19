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
  it('keeps passing tests hidden until requested and renders their names safely', () => {
    const initial=http.expectOne(r=>r.url==='tests.json');
    expect(initial.request.params.get('state')).toBe('attention');initial.flush(empty);fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('286 tests passed');
    expect(fixture.nativeElement.querySelector('.scenario-row')).toBeNull();
    const show=Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(b=>b.textContent?.includes('Show passed'))!;
    show.click();fixture.detectChanges();
    const request=http.expectOne(r=>r.url==='tests.json');expect(request.request.params.get('state')).toBe('passed');
    request.flush({...empty,total:1,items:[{key:'a',project:'shop',suite:'Checkout',name:'<img src=x> Customer can pay',state:'passed',source:'id'}]});fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.scenario-name').textContent).toContain('<img src=x>');
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(fixture.nativeElement.querySelector('.scenario-state').textContent).toBe('Passed');
  });
  it('shows failures and incomplete tests as distinct states, then searches and resets pagination', () => {
    http.expectOne(r=>r.url==='tests.json').flush({...empty,total:72,counts:{passed:286,failed:1,pending:71},items:[
      {key:'a',project:'shop',suite:'Checkout',name:'Customer can pay',state:'failed',source:'id'},
      {key:'b',project:'shop',suite:'Checkout',name:'Customer can cancel',state:'pending',source:'id2'}]});fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.scenario-row').length).toBe(2);
    expect(fixture.nativeElement.textContent).toContain('Not completed');
    fixture.componentInstance.go(50);
    const page=http.expectOne(r=>r.url==='tests.json');expect(page.request.params.get('offset')).toBe('50');
    const input=fixture.nativeElement.querySelector('input') as HTMLInputElement;input.value='payment';input.dispatchEvent(new Event('input'));fixture.detectChanges();
    expect(page.cancelled).toBeTrue();
    const search=http.expectOne(r=>r.url==='tests.json');expect(search.request.params.get('q')).toBe('payment');expect(search.request.params.get('offset')).toBe('0');
    search.flush(empty);fixture.detectChanges();expect(fixture.nativeElement.textContent).toContain('No matching scenarios');
  });
  it('shows an error instead of implying missing results are passing and cancels work on navigation', () => {
    http.expectOne(r=>r.url==='tests.json').flush(null,{status:503,statusText:'Unavailable'});fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('Unable to refresh');
    expect(fixture.nativeElement.textContent).not.toContain('No tests need attention');
    fixture.componentInstance.refresh();const retry=http.expectOne(r=>r.url==='tests.json');
    fixture.destroy();expect(retry.cancelled).toBeTrue();
  });
});
