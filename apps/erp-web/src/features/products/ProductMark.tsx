import { Layers, Package } from 'lucide-react';
import { text } from '../../lib/format';

/** Product/material thumbnail chosen from the item name or kind (resin, carton, cap, container…). */
export function ProductMark({ kind = 'bottle', small = false }: { kind?: unknown; small?: boolean }) {
  const k = text(kind).toLowerCase();
  const color = k.includes('cap')
    ? 'violet'
    : k.includes('container') || k.includes('food')
      ? 'teal'
      : k.includes('resin') || k.includes('material')
        ? 'amber'
        : 'blue';
  return (
    <span className={`product-mark ${color} ${small ? 'small' : ''}`}>
      {k.includes('resin') || k.includes('masterbatch') ? (
        <Layers size={small ? 16 : 20} />
      ) : k.includes('carton') || k.includes('box') ? (
        <Package size={small ? 16 : 20} />
      ) : k.includes('cap') ? (
        <svg width="22" height="24" viewBox="0 0 24 24" fill="none">
          <path
            d="M5 10c0-2 3-3 7-3s7 1 7 3v7c0 2-14 2-14 0zM5 10c0 3 14 3 14 0M8 13v4m4-4v4m4-4v4"
            stroke="currentColor"
            strokeWidth="1.5"
          />
        </svg>
      ) : (
        <svg width="22" height="26" viewBox="0 0 24 28" fill="none">
          <path
            d="M9 3h6v4l3 5v11c0 3-12 3-12 0V12l3-5zM9 3V1h6v2M7 14h10M7 20h10"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeLinejoin="round"
          />
        </svg>
      )}
    </span>
  );
}
