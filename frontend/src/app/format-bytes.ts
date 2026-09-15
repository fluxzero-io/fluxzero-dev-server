/** IEC units, shared by resource totals, limits and component details. */
export function formatBytes(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value) || value < 0) return '—';
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
  return (unit === 0 ? Math.round(value).toString() : value.toFixed(1)) + ' ' + units[unit];
}
