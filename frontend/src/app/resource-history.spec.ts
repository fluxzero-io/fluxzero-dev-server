import {appendSample, resourceChart, resourceLevels, ResourceSample, totalMemory} from './resource-history';

describe('Resource history projection', () => {
  const sample: ResourceSample = {at:0,applicationMemory:100,devserverMemory:200,monitoringStorage:50};
  it('bounds appended server samples, deduplicates buckets and preserves gaps', () => {
    let samples: ResourceSample[] = [];
    for (let i=0;i<100;i++) samples=appendSample(samples,{...sample,at:i*5000});
    expect(samples.length).toBe(60);
    expect(samples[0].at).toBe(200000);
    expect(appendSample(samples,{...sample,at:495001}).length).toBe(60);
    const gap=appendSample([sample],{...sample,at:15000,devserverMemory:null});
    expect(resourceLevels(gap,'devserverMemory').slice(-4)).toEqual([100,null,null,null]);
  });
  it('preserves observed-peak scaling for legacy snapshots without storage limits', () => {
    expect(resourceLevels([sample,{...sample,at:5000,monitoringStorage:100},{...sample,at:10000,monitoringStorage:0}], 'monitoringStorage').slice(-3)).toEqual([50,100,0]);
  });
  it('scales storage against the configured limit at each sample and shows truncation as zero', () => {
    const gib = 1024 ** 3;
    const samples = [
      {...sample, monitoringStorage:gib / 4, monitoringStorageMax:gib},
      {...sample, at:5000, monitoringStorage:gib / 2, monitoringStorageMax:gib},
      {...sample, at:10000, monitoringStorage:gib / 2, monitoringStorageMax:2 * gib},
      {...sample, at:15000, monitoringStorage:0, monitoringStorageMax:gib}
    ];
    expect(resourceLevels(samples, 'monitoringStorage').slice(-4)).toEqual([25,50,25,0]);
    expect(resourceLevels([{...sample,monitoringStorageMax:null}], 'monitoringStorage').at(-1)).toBeNull();
  });
  it('fills contiguous measurements to the baseline without bridging missing samples', () => {
    const segments = resourceChart([
      sample, {...sample, at:5000, monitoringStorage:100},
      {...sample, at:15000, monitoringStorage:0}
    ], 'monitoringStorage');
    expect(segments).toEqual([
      {line:'M 56 50 L 56.5 50 L 57.5 0 L 58 0', area:'M 56 50 L 56.5 50 L 57.5 0 L 58 0 L 58 100 L 56 100 Z'},
      {line:'M 59 100 L 59.5 100 L 60 100', area:'M 59 100 L 59.5 100 L 60 100 L 60 100 L 59 100 Z'}
    ]);
  });
  it('renders a single measured bucket and leaves unmeasured history empty', () => {
    expect(resourceChart([], 'applicationMemory')).toEqual([]);
    expect(resourceChart([{...sample, applicationMemory:null}], 'applicationMemory')).toEqual([]);
    expect(resourceChart([sample], 'applicationMemory')).toEqual([
      {line:'M 59 0 L 59.5 0 L 60 0', area:'M 59 0 L 59.5 0 L 60 0 L 60 100 L 59 100 Z'}
    ]);
  });
  it('keeps component histories separate and accepts snapshots without component measurements', () => {
    const samples = [sample, {...sample,at:5000,componentMemory:{devserver:200,storage:25}},
      {...sample,at:10000,componentMemory:{devserver:null,storage:0}}];
    expect(resourceLevels(samples, 'devserverMemory', 'devserver').slice(-3)).toEqual([null,100,null]);
    expect(resourceLevels(samples, 'devserverMemory', 'storage').slice(-3)).toEqual([null,100,0]);
    expect(resourceChart(samples, 'devserverMemory', 'absent')).toEqual([]);
  });
  it('scales memory against its measured limit instead of the observed peak', () => {
    const samples = [{...sample,devserverMemory:100,devserverMemoryMax:400,componentMemory:{jvm:100},componentMemoryMax:{jvm:200}},
      {...sample,at:5000,devserverMemory:200,devserverMemoryMax:400,componentMemory:{jvm:100},componentMemoryMax:{jvm:400}}];
    expect(resourceLevels(samples, 'devserverMemory').slice(-2)).toEqual([25,50]);
    expect(resourceLevels(samples, 'devserverMemory', 'jvm').slice(-2)).toEqual([50,25]);
    expect(resourceLevels([{...sample,devserverMemoryMax:null}], 'devserverMemory').at(-1)).toBeNull();
  });
  it('sums matching usage and limits while keeping unavailable measurements unknown', () => {
    const components = [{id:'jvm',name:'JVM',state:'running',application:false,memoryBytes:900,memoryUsedBytes:100,memoryMaxBytes:400},
      {id:'go',name:'Go',state:'running',application:false,memoryBytes:800,memoryUsedBytes:50,memoryMaxBytes:200}];
    expect(totalMemory(components)).toBe(150);
    expect(totalMemory(components,true)).toBe(600);
    expect(totalMemory([{...components[0],memoryUsedBytes:null}])).toBeNull();
    expect(totalMemory([{...components[0],memoryMaxBytes:null}],true)).toBeNull();
  });
  it('counts stopped components as zero but keeps unmeasured live processes unknown', () => {
    const component={id:'app',name:'App',state:'stopped',application:true,memoryBytes:null,runningProcesses:0};
    expect(totalMemory([component])).toBe(0);
    expect(totalMemory([{...component,runningProcesses:1}])).toBeNull();
  });
});
