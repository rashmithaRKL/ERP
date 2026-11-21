import { useNavigate } from 'react-router-dom';
import { ArrowUpRight, Factory, Package, Search } from 'lucide-react';
import { Badge, Overlay } from '../components';
import { text } from '../lib/format';
import { navItems } from './routes';
import { useWorkspace } from './workspace';

/** Ctrl/⌘+K workspace search: pages from the route registry, manufacturing orders and items. */
export function CommandPalette({
  search,
  onSearch,
  onClose,
}: {
  search: string;
  onSearch: (value: string) => void;
  onClose: () => void;
}) {
  const navigate = useNavigate();
  const { allowed, data, openOrder } = useWorkspace();
  return (
    <Overlay title="Search your workspace" onClose={onClose}>
      <div className="command-input">
        <Search size={20} />
        <input
          autoFocus
          placeholder="Find pages, products or manufacturing orders…"
          aria-label="Search workspace"
          value={search}
          onChange={(e) => onSearch(e.target.value)}
        />
        <kbd>ESC</kbd>
      </div>
      <div className="command-results">
        <h3>Pages</h3>
        {navItems
          .filter(
            (n) => (!n.roles || allowed(n.roles)) && n.label.toLowerCase().includes(search.toLowerCase()),
          )
          .map((n) => (
            <button
              key={n.path}
              onClick={() => {
                navigate(n.path);
                onClose();
              }}
            >
              <n.icon size={17} />
              <span>{n.label}</span>
              <ArrowUpRight size={14} />
            </button>
          ))}
        {search && data && (
          <>
            <h3>Manufacturing orders</h3>
            {data.orders
              .filter((o) =>
                `${o.number} ${o.product} ${o.customer}`.toLowerCase().includes(search.toLowerCase()),
              )
              .slice(0, 8)
              .map((o) => (
                <button
                  key={o.id}
                  onClick={() => {
                    openOrder(o.id);
                    onClose();
                  }}
                >
                  <Factory size={17} />
                  <span>
                    {text(o.number)} · {text(o.product)}
                  </span>
                  <Badge value={o.status} />
                </button>
              ))}
            <h3>Products & materials</h3>
            {data.items
              .filter((i) => `${i.sku} ${i.name}`.toLowerCase().includes(search.toLowerCase()))
              .map((i) => (
                <button
                  key={i.id}
                  onClick={() => {
                    navigate('/products');
                    onClose();
                  }}
                >
                  <Package size={17} />
                  <span>
                    {text(i.sku)} · {text(i.name)}
                  </span>
                </button>
              ))}
          </>
        )}
      </div>
    </Overlay>
  );
}
