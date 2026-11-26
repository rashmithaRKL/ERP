import { useMemo } from 'react';
import type { CSSProperties } from 'react';
import { fmt, text } from '../../lib/format';
import { EDGE_LABELS, KIND_LABELS } from './api';
import type { Trace, TraceNode } from './api';
import { edgeMid, edgePath, layoutTrace, NODE_HEIGHT, NODE_WIDTH } from './layout';
import { KindIcon, NodeStatus } from './shared';

/**
 * The genealogy as a left-to-right graph in plain SVG + positioned buttons: where the root came from on the left,
 * where it went on the right, quantities on every link. Nodes are keyboard-focusable buttons; the selected node's
 * links are highlighted. The canvas scrolls inside its frame on narrow screens.
 */
export function GenealogyGraph({
  trace,
  selected,
  onSelect,
}: {
  trace: Trace;
  selected?: string;
  onSelect: (id: string) => void;
}) {
  const layout = useMemo(() => layoutTrace(trace.nodes, trace.edges), [trace]);
  const ordered = useMemo(
    () =>
      [...trace.nodes].sort(
        (a, b) =>
          (layout.positions[a.id]?.col ?? 0) - (layout.positions[b.id]?.col ?? 0) ||
          (layout.positions[a.id]?.row ?? 0) - (layout.positions[b.id]?.row ?? 0),
      ),
    [trace, layout],
  );
  const pad = 24;
  return (
    <div className="trace-graph" role="group" aria-label="Genealogy graph: material flows from left to right">
      <div
        className="trace-canvas"
        style={{ width: layout.width + pad * 2, height: layout.height + pad * 2 + 24 }}
      >
        <svg
          className="trace-edges"
          width={layout.width + pad * 2}
          height={layout.height + pad * 2 + 24}
          aria-hidden="true"
        >
          <defs>
            <marker
              id="trace-arrow"
              viewBox="0 0 8 8"
              refX="7"
              refY="4"
              markerWidth="7"
              markerHeight="7"
              orient="auto"
            >
              <path d="M0,0 L8,4 L0,8 z" />
            </marker>
          </defs>
          <g transform={`translate(${pad},${pad})`}>
            {trace.edges.map((e, i) => {
              const a = layout.positions[e.from];
              const b = layout.positions[e.to];
              if (!a || !b) return null;
              const active = !!selected && (e.from === selected || e.to === selected);
              const mid = edgeMid(a, b);
              const qty =
                e.quantity === null || e.quantity === undefined ? '' : `${fmt(e.quantity, 3)} ${text(e.uom)}`;
              return (
                <g
                  key={`${e.from}-${e.to}-${e.kind}-${i}`}
                  className={`trace-edge ${active ? 'active' : ''} ${text(e.kind).toLowerCase()}`}
                >
                  <path d={edgePath(a, b)} markerEnd="url(#trace-arrow)">
                    <title>
                      {`${EDGE_LABELS[e.kind] ?? e.kind}${qty ? ` · ${qty}` : ''}${e.reference ? ` · ${text(e.reference)}` : ''}${
                        e.at ? ` · ${new Date(text(e.at)).toLocaleString('en-GB')}` : ''
                      }`}
                    </title>
                  </path>
                  {qty && (
                    <text x={mid.x} y={mid.y - 5} textAnchor="middle">
                      {qty}
                    </text>
                  )}
                </g>
              );
            })}
          </g>
        </svg>
        {ordered.map((n) => {
          const p = layout.positions[n.id];
          if (!p) return null;
          return (
            <GraphNode
              key={n.id}
              node={n}
              selected={selected === n.id}
              style={{ left: p.x + pad, top: p.y + pad, width: NODE_WIDTH, height: NODE_HEIGHT }}
              onSelect={() => onSelect(n.id)}
            />
          );
        })}
      </div>
    </div>
  );
}

function GraphNode({
  node,
  selected,
  style,
  onSelect,
}: {
  node: TraceNode;
  selected: boolean;
  style: CSSProperties;
  onSelect: () => void;
}) {
  const kind = KIND_LABELS[node.kind] ?? node.kind;
  return (
    <button
      type="button"
      className={`trace-node kind-${node.kind.toLowerCase()} ${node.side === 'ROOT' ? 'root' : ''} ${selected ? 'selected' : ''}`}
      style={style}
      aria-pressed={selected}
      aria-label={`${kind} ${text(node.label)}, ${text(node.detail)}${node.side === 'ROOT' ? ', traced item' : ''}`}
      onClick={onSelect}
    >
      <span className="trace-node-head">
        <KindIcon kind={node.kind} />
        <strong>{text(node.label)}</strong>
        {node.more && (
          <span className="trace-more" title="More links beyond the depth limit">
            +
          </span>
        )}
      </span>
      <span className="trace-node-detail">{text(node.detail)}</span>
      <NodeStatus node={node} />
    </button>
  );
}
