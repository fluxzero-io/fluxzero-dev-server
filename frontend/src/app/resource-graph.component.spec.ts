import {ComponentFixture, TestBed} from '@angular/core/testing';
import {ResourceGraphComponent} from './resource-graph.component';
import {ResourceSample} from './resource-history';

describe('Resource graph dialog', () => {
  let fixture: ComponentFixture<ResourceGraphComponent>;
  const mib = 1024 * 1024;
  const sample = (at: number, value: number | null): ResourceSample => ({at, applicationMemory:99 * mib,
    devserverMemory:500 * mib,monitoringStorage:800,componentMemory:{orders:value}});
  beforeEach(() => {
    fixture = TestBed.createComponent(ResourceGraphComponent);
    fixture.componentRef.setInput('title','Orders · Memory');
    fixture.componentRef.setInput('metric','applicationMemory');
    fixture.componentRef.setInput('componentId','orders');
    fixture.componentRef.setInput('current',2 * mib);
    fixture.componentRef.setInput('limit',9 * 1024 * mib);
    fixture.detectChanges();
  });
  afterEach(() => fixture.destroy());
  it('uses the selected component and auto-scales bytes independently of its large limit', () => {
    fixture.componentRef.setInput('samples',[sample(5000,mib),sample(10000,2*mib)]);
    fixture.componentInstance.open(); fixture.detectChanges();
    expect(fixture.componentInstance.peak()).toBe(2*mib);
    expect(fixture.componentInstance.ceiling()).toBe(4*mib);
    expect(fixture.nativeElement.querySelector('.graph-summary').textContent).toContain('9.0 GiB');
    expect(fixture.nativeElement.querySelector('.graph-y-axis').textContent).toContain('4.0 MiB');
    expect(fixture.nativeElement.querySelectorAll('.graph-x-axis span').length).toBe(3);
    fixture.componentRef.setInput('samples',[sample(5000,mib),sample(10000,2*mib),sample(15000,3*mib)]);
    fixture.detectChanges();
    expect(fixture.componentInstance.peak()).toBe(3*mib);
    expect(fixture.nativeElement.querySelector('dialog').open).toBeTrue();
  });
  it('preserves missing measurements as gaps and excludes expired peaks', () => {
    fixture.componentRef.setInput('samples',[sample(0,100*mib),sample(305000,mib),sample(310000,null),sample(315000,2*mib)]);
    fixture.detectChanges();
    expect(fixture.componentInstance.peak()).toBe(2*mib);
    expect(fixture.componentInstance.segments().length).toBe(2);
  });
  it('distinguishes unknown measurements from measured zero', () => {
    fixture.componentRef.setInput('samples',[sample(5000,null)]);
    fixture.componentInstance.open(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=status]').textContent).toContain('No measurements');
    expect(fixture.nativeElement.querySelector('svg')).toBeNull();
    fixture.componentRef.setInput('samples',[sample(10000,0)]); fixture.detectChanges();
    expect(fixture.componentInstance.peak()).toBe(0);
    expect(fixture.nativeElement.querySelector('[role=status]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.graph-line')).not.toBeNull();
  });
  it('opens a labelled modal and restores focus to the graph button on close', async () => {
    const trigger = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    trigger.click(); fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    expect(dialog.open).toBeTrue();
    expect(dialog.getAttribute('aria-labelledby')).toBe(fixture.nativeElement.querySelector('h2').id);
    expect(document.activeElement).toBe(dialog.querySelector('button'));
    dialog.querySelector('button')!.click();
    await fixture.whenStable(); fixture.detectChanges();
    expect(dialog.open).toBeFalse();
    expect(document.activeElement).toBe(trigger);
  });
  it('dismisses only a complete backdrop click, not padding or a drag out of the graph', () => {
    fixture.componentInstance.open(); fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    const rect = dialog.getBoundingClientRect();
    const inside = {clientX:rect.left + 4,clientY:rect.top + 4,bubbles:true};
    const outside = {clientX:rect.left - 8,clientY:rect.top - 8,bubbles:true};
    dialog.dispatchEvent(new PointerEvent('pointerdown', inside));
    dialog.dispatchEvent(new MouseEvent('click', inside));
    expect(dialog.open).toBeTrue();
    dialog.dispatchEvent(new PointerEvent('pointerdown', inside));
    dialog.dispatchEvent(new MouseEvent('click', outside));
    expect(dialog.open).toBeTrue();
    dialog.dispatchEvent(new PointerEvent('pointerdown', outside));
    dialog.dispatchEvent(new MouseEvent('click', outside));
    expect(dialog.open).toBeFalse();
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('.graph-button'));
  });
  it('closes on the native Escape cancel event', () => {
    fixture.componentInstance.open(); fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    dialog.dispatchEvent(new Event('cancel', {cancelable:true}));
    expect(dialog.open).toBeFalse();
  });
});
