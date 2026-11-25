import { text } from '../../lib/format';
import { itemTypeLabels } from './itemMaster';
import './products.css';

const tones: Record<string, string> = {
  RAW_MATERIAL: 'amber',
  SEMI_FINISHED: 'violet',
  FINISHED_GOOD: 'blue',
  PACKAGING: 'teal',
  SPARE_PART: 'gray',
};

/** Item type chip with one stable colour per type (raw, semi-finished, finished, packaging, spare part). */
export function ItemTypeBadge({ type }: { type: unknown }) {
  const code = text(type);
  return (
    <span className={`badge item-type ${tones[code] || 'gray'}`}>
      <i />
      {itemTypeLabels[code] || code.toLowerCase().replaceAll('_', ' ')}
    </span>
  );
}
