import {formatBytes} from './format-bytes';

describe('Resource units', () => {
  it('uses IEC units at binary thresholds for current values, limits and tooltips', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1023)).toBe('1023 B');
    expect(formatBytes(1024)).toBe('1.0 KiB');
    expect(formatBytes(1048576)).toBe('1.0 MiB');
    expect(formatBytes(1073741824)).toBe('1.0 GiB');
    expect(formatBytes(1.5 * 1073741824)).toBe('1.5 GiB');
    expect(formatBytes(1099511627776)).toBe('1.0 TiB');
  });
  it('does not misrepresent missing or invalid measurements as zero', () => {
    for (const value of [null, undefined, NaN, Infinity, -1]) expect(formatBytes(value)).toBe('—');
  });
});
