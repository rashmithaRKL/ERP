import { text } from '../lib/format';

export function Badge({ value }: { value: unknown }) {
  const label = text(value).replaceAll('_', ' ').toLowerCase();
  const tone = /passed|released|completed|closed|delivered|running|approved/.test(label)
    ? 'green'
    : /failed|rejected|urgent|cancelled/.test(label)
      ? 'red'
      : /hold|quarantine|quality|pending|maintenance|high|partial/.test(label)
        ? 'amber'
        : /progress|reserved|confirmed|normal/.test(label)
          ? 'blue'
          : 'gray';
  return (
    <span className={`badge ${tone}`}>
      <i />
      {label}
    </span>
  );
}
