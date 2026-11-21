import { PackageOpen } from 'lucide-react';

export function Empty({
  title = 'Nothing here yet',
  body = 'New records will appear here as your team gets to work.',
}: {
  title?: string;
  body?: string;
}) {
  return (
    <div className="empty">
      <PackageOpen size={32} strokeWidth={1.4} />
      <strong>{title}</strong>
      <p>{body}</p>
    </div>
  );
}
