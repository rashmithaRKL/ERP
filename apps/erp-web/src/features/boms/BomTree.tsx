import { useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { ChevronRight, CircleAlert } from 'lucide-react';
import { Badge, Empty } from '../../components';
import { fmt, money, text } from '../../lib/format';
import { ItemTypeBadge } from '../products/ItemTypeBadge';
import type { ExplosionNode, WhereUsedNode } from './api';

/** A node of either tree with a stable key (its path) for expand/collapse. */
type Flat<T> = { key: string; node: T; depth: number; children: number };

function walk<T>(
  nodes: T[],
  childrenOf: (n: T) => T[],
  idOf: (n: T) => string,
  prefix: string,
  depth: number,
  out: Flat<T>[],
) {
  nodes.forEach((n, i) => {
    const key = `${prefix}/${idOf(n)}#${i}`;
    out.push({ key, node: n, depth, children: childrenOf(n).length });
    walk(childrenOf(n), childrenOf, idOf, key, depth + 1, out);
  });
}

/**
 * Expansion state of a tree: nodes are expanded individually, or every node above a chosen level at once
 * ("Level 1" shows the direct components only, "All" every level).
 */
function useExpansion<T>(
  nodes: T[],
  childrenOf: (n: T) => T[],
  idOf: (n: T) => string,
  initialLevels: number,
) {
  const all = useMemo(() => {
    const out: Flat<T>[] = [];
    walk(nodes, childrenOf, idOf, '', 1, out);
    return out;
  }, [nodes, childrenOf, idOf]);
  const upTo = (levels: number) => new Set(all.filter((f) => f.depth < levels).map((f) => f.key));
  const [expanded, setExpanded] = useState<Set<string>>(() => upTo(initialLevels));
  const visible = all.filter((f) => {
    // Visible when every ancestor is expanded.
    let key = f.key;
    for (let d = f.depth; d > 1; d--) {
      key = key.substring(0, key.lastIndexOf('/'));
      if (!expanded.has(key)) return false;
    }
    return true;
  });
  const maxDepth = all.reduce((m, f) => Math.max(m, f.depth), 0);
  const shownLevels = visible.reduce((m, f) => Math.max(m, f.depth), 0);
  return {
    visible,
    maxDepth,
    shownLevels,
    isOpen: (key: string) => expanded.has(key),
    toggle: (key: string) =>
      setExpanded((prev) => {
        const next = new Set(prev);
        if (next.has(key)) next.delete(key);
        else next.add(key);
        return next;
      }),
    showLevels: (levels: number) => setExpanded(upTo(levels)),
  };
}

/** "Level 1 · Level 2 · … · All" buttons. */
export function LevelControls({
  maxDepth,
  shownLevels,
  onShow,
  label,
}: {
  maxDepth: number;
  shownLevels: number;
  onShow: (levels: number) => void;
  label: string;
}) {
  if (maxDepth < 2) return null;
  const levels = Array.from({ length: maxDepth }, (_, i) => i + 1);
  return (
    <div className="segmented" role="group" aria-label={label}>
      {levels.map((l) => (
        <button
          type="button"
          key={l}
          aria-pressed={shownLevels === l}
          onClick={() => onShow(l)}
          title={l === maxDepth ? 'Expand every level' : `Show levels 1 to ${l}`}
        >
          {l === maxDepth ? 'All levels' : `Level ${l}`}
        </button>
      ))}
    </div>
  );
}

function Toggle({ open, label, onClick }: { open: boolean; label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      className="tree-toggle"
      aria-expanded={open}
      aria-label={`${open ? 'Collapse' : 'Expand'} ${label}`}
      onClick={onClick}
    >
      <ChevronRight size={14} />
    </button>
  );
}

const explosionChildren = (n: ExplosionNode) => n.children;
const explosionId = (n: ExplosionNode) => n.itemId;

/** Indented explosion of a revision: one row per component and level, collapsible per node or per level. */
export function ExplosionTree({ root }: { root: ExplosionNode }) {
  const tree = useExpansion(root.children, explosionChildren, explosionId, 99);
  return (
    <>
      <div className="bom-tree-controls">
        <span className="muted">
          {tree.maxDepth} {tree.maxDepth === 1 ? 'level' : 'levels'} below {text(root.sku)}
        </span>
        <span className="grow" />
        <LevelControls
          label="Explosion levels"
          maxDepth={tree.maxDepth}
          shownLevels={tree.shownLevels}
          onShow={tree.showLevels}
        />
      </div>
      <div className="bom-tree" role="tree" aria-label={`Bill of materials of ${text(root.name)}`}>
        <div className="bom-tree-row head" aria-hidden>
          <span>Component</span>
          <span className="num">Required</span>
          <span className="num hide-sm">Per parent unit</span>
          <span className="num">Standard cost</span>
        </div>
        <div className="bom-tree-row root" role="treeitem" aria-level={1} aria-selected={false}>
          <div className="bom-tree-name">
            <span className="level-pill">L0</span>
            <div className="cell-stack">
              <strong>{text(root.name)}</strong>
              <span>
                {text(root.sku)} · revision {text(root.revision)} · {fmt(root.outputQty, 6)} out at{' '}
                {fmt(Number(root.yieldFactor) * 100, 2)}% yield
              </span>
            </div>
          </div>
          <span className="num">
            {fmt(root.quantity, 6)} <small>{text(root.uom)}</small>
          </span>
          <span className="num hide-sm">—</span>
          <span className="num">{money(root.extendedCost)}</span>
        </div>
        {tree.visible.map(({ key, node, depth, children }) => (
          <div
            className="bom-tree-row"
            key={key}
            role="treeitem"
            aria-level={depth + 1}
            aria-selected={false}
            aria-expanded={children ? tree.isOpen(key) : undefined}
          >
            <div className="bom-tree-name" style={{ '--depth': depth - 1 } as CSSProperties}>
              {children ? (
                <Toggle open={tree.isOpen(key)} label={text(node.sku)} onClick={() => tree.toggle(key)} />
              ) : (
                <span className="tree-spacer" aria-hidden />
              )}
              <span className="level-pill">L{node.level}</span>
              <div className="cell-stack">
                <strong title={text(node.name)}>{text(node.name)}</strong>
                <span>
                  {text(node.sku)}
                  {node.revision ? ` · BOM ${node.revision}` : ''}
                  {node.missingBom && <span className="tree-flag"> · no approved BOM</span>}
                  {node.cycle && <span className="tree-flag red"> · cycle</span>}
                  {!node.active && <span className="tree-flag"> · inactive</span>}
                </span>
              </div>
              {node.type === 'SEMI_FINISHED' && <ItemTypeBadge type={node.type} />}
            </div>
            <span className="num">
              {fmt(node.quantity, 6)} <small>{text(node.uom)}</small>
            </span>
            <span className="num hide-sm">{fmt(node.perUnit, 6)}</span>
            <span className="num">
              {money(node.extendedCost)}
              <small>{children ? 'rolled up' : `${money(node.standardCost)} / ${text(node.uom)}`}</small>
            </span>
          </div>
        ))}
      </div>
    </>
  );
}

const usedChildren = (n: WhereUsedNode) => n.parents;
const usedId = (n: WhereUsedNode) => n.bomId;

/** Multi-level where-used: the revisions that consume an item, then the revisions that consume those products. */
export function WhereUsedTree({ parents, sku }: { parents: WhereUsedNode[]; sku: string }) {
  const tree = useExpansion(parents, usedChildren, usedId, 99);
  if (!parents.length)
    return (
      <Empty
        title="Not used in any BOM"
        body={`${sku} is not a component of any revision: it is an end product or not planned yet.`}
      />
    );
  return (
    <>
      <div className="bom-tree-controls">
        <span className="muted">
          Used up to {tree.maxDepth} {tree.maxDepth === 1 ? 'level' : 'levels'} above {sku}
        </span>
        <span className="grow" />
        <LevelControls
          label="Where-used levels"
          maxDepth={tree.maxDepth}
          shownLevels={tree.shownLevels}
          onShow={tree.showLevels}
        />
      </div>
      <div className="bom-tree" role="tree" aria-label={`Where ${sku} is used`}>
        <div className="bom-tree-row head" aria-hidden>
          <span>Used in (product · revision)</span>
          <span className="num">Quantity</span>
          <span className="num hide-sm">Per unit</span>
          <span className="num">Revision</span>
        </div>
        {tree.visible.map(({ key, node, depth, children }) => (
          <div
            className="bom-tree-row"
            key={key}
            role="treeitem"
            aria-level={depth}
            aria-selected={false}
            aria-expanded={children ? tree.isOpen(key) : undefined}
          >
            <div className="bom-tree-name" style={{ '--depth': depth - 1 } as CSSProperties}>
              {children ? (
                <Toggle open={tree.isOpen(key)} label={text(node.sku)} onClick={() => tree.toggle(key)} />
              ) : (
                <span className="tree-spacer" aria-hidden />
              )}
              <span className="level-pill">+{node.level}</span>
              <div className="cell-stack">
                <strong title={text(node.name)}>{text(node.name)}</strong>
                <span>
                  {text(node.sku)} · {text(node.revision)}
                  {node.active && ' · active'}
                  {node.cycle && <span className="tree-flag red"> · cycle</span>}
                  {!node.productActive && <span className="tree-flag"> · inactive product</span>}
                </span>
              </div>
            </div>
            <span className="num">
              {fmt(node.lineQty, 6)} <small>per {fmt(node.outputQty)}</small>
            </span>
            <span className="num hide-sm">{fmt(node.perUnit, 6)}</span>
            <span className="num">
              <Badge value={node.status} />
            </span>
          </div>
        ))}
      </div>
    </>
  );
}

/** Small inline warning list (missing sub-BOMs, cycles, truncated structures). */
export function TreeWarnings({ warnings }: { warnings: string[] }) {
  if (!warnings.length) return null;
  return (
    <div className="bom-warnings">
      {warnings.map((w) => (
        <div key={w} className="lock-note">
          <CircleAlert size={14} />
          <span>{w}</span>
        </div>
      ))}
    </div>
  );
}
