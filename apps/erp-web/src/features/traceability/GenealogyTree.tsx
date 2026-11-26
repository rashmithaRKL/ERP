import type { ReactNode } from 'react';
import { ArrowLeft, ArrowRight, CornerDownRight } from 'lucide-react';
import { fmt, text } from '../../lib/format';
import { dateTime } from '../inventory/shared';
import { EDGE_LABELS, KIND_LABELS } from './api';
import type { Trace, TraceNode, TreeRow } from './api';
import { KindIcon, NodeStatus } from './shared';

/**
 * The genealogy as an indented two-sided tree: "came from" (backward, read up the supply chain) and "went to"
 * (forward). Every row names the link (issued, produced, delivered…) with its quantity and date; a node reached a
 * second time is listed once more as "see above" instead of being expanded again.
 */
export function GenealogyTree({
  trace,
  selected,
  onSelect,
}: {
  trace: Trace;
  selected?: string;
  onSelect: (id: string) => void;
}) {
  const nodes = new Map(trace.nodes.map((n) => [n.id, n]));
  const root = trace.root;
  return (
    <div className="trace-tree">
      <div className="trace-tree-root">
        <KindIcon kind={root.kind} size={16} />
        <div>
          <strong>
            {KIND_LABELS[root.kind] ?? root.kind} {text(root.label)}
          </strong>
          <span>{text(root.detail)}</span>
        </div>
      </div>
      {trace.direction !== 'FORWARD' && (
        <TreeSide
          title="Came from"
          icon={<ArrowLeft size={14} />}
          rows={trace.tree.backward}
          nodes={nodes}
          selected={selected}
          onSelect={onSelect}
          empty="No recorded origin: opening stock or a document without material links."
        />
      )}
      {trace.direction !== 'BACKWARD' && (
        <TreeSide
          title="Went to"
          icon={<ArrowRight size={14} />}
          rows={trace.tree.forward}
          nodes={nodes}
          selected={selected}
          onSelect={onSelect}
          empty="Nothing has been made from it, moved or shipped yet."
        />
      )}
    </div>
  );
}

function TreeSide({
  title,
  icon,
  rows,
  nodes,
  selected,
  onSelect,
  empty,
}: {
  title: string;
  icon: ReactNode;
  rows: TreeRow[];
  nodes: Map<string, TraceNode>;
  selected?: string;
  onSelect: (id: string) => void;
  empty: string;
}) {
  return (
    <section className="trace-tree-side" aria-label={title}>
      <h3>
        {icon}
        {title}
      </h3>
      {rows.length ? (
        <ul>
          {rows.map((r, i) => {
            const n = nodes.get(r.node);
            if (!n) return null;
            return (
              <li key={`${r.node}-${i}`} style={{ paddingLeft: `${Math.min(r.indent - 1, 12) * 18}px` }}>
                <button
                  type="button"
                  className={`trace-tree-row ${selected === n.id ? 'selected' : ''}`}
                  aria-pressed={selected === n.id}
                  onClick={() => onSelect(n.id)}
                >
                  {r.indent > 1 && (
                    <CornerDownRight size={13} className="trace-tree-branch" aria-hidden="true" />
                  )}
                  <KindIcon kind={n.kind} />
                  <span className="trace-tree-main">
                    <span className="trace-tree-edge">
                      {EDGE_LABELS[r.edge] ?? r.edge}
                      {r.quantity !== null && r.quantity !== undefined
                        ? ` · ${fmt(r.quantity, 3)} ${text(r.uom)}`
                        : ''}
                      {r.at ? ` · ${dateTime(r.at)}` : ''}
                    </span>
                    <strong>
                      {KIND_LABELS[n.kind] ?? n.kind} {text(n.label)}
                      {r.repeated && <em> · see above</em>}
                    </strong>
                    <span className="trace-tree-detail">{text(n.detail)}</span>
                    {!r.repeated && <NodeStatus node={n} />}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="inv-small muted">{empty}</p>
      )}
    </section>
  );
}
