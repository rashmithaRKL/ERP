import { useEffect, useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { ChevronLeft, ChevronRight, LoaderCircle, Pencil, Plus, ScanBarcode, Search } from 'lucide-react';
import { useWorkspace } from '../app/workspace';
import { Badge, Empty, Panel, Table } from '../components';
import { request } from '../lib/api';
import type { Row } from '../lib/api';
import { fmt, money, num, text } from '../lib/format';
import { Tabs } from '../motion/motion';
import { FamilyDrawer } from '../features/products/FamilyDrawer';
import { ItemDrawer } from '../features/products/ItemDrawer';
import { ItemTypeBadge } from '../features/products/ItemTypeBadge';
import { ProductMark } from '../features/products/ProductMark';
import { createCategory, createFamily, createUnit, editCategory, editUnit } from '../features/products/forms';
import {
  itemTypePlural,
  itemTypes,
  useCategories,
  useFamilies,
  useItemMasterSync,
  useItemPage,
  useUnits,
} from '../features/products/itemMaster';
import type { ItemFilters } from '../features/products/itemMaster';

const PAGE_SIZE = 25;

/** Route `/products`: the item master (server-paged items, variant families, categories and units). */
export default function ProductsPage() {
  const workspace = useWorkspace();
  const { can, notify } = workspace;
  useItemMasterSync();
  const [search, setSearch] = useState('');
  const [filters, setFilters] = useState<ItemFilters>({ q: '', type: '', category: '', active: '' });
  const [cursors, setCursors] = useState<string[]>(['']);
  const [itemId, setItemId] = useState<string>();
  const [familyId, setFamilyId] = useState<string>();
  const page = useItemPage(filters, cursors[cursors.length - 1], PAGE_SIZE);
  const categories = useCategories();
  const families = useFamilies();
  const units = useUnits();
  const editable = can('PLANNER');

  // Debounced server-side search; any filter change restarts paging.
  useEffect(() => {
    const q = search.trim();
    const timeout = setTimeout(() => {
      setFilters((prev) => (prev.q === q ? prev : { ...prev, q }));
      setCursors((prev) => (prev.length === 1 ? prev : ['']));
    }, 250);
    return () => clearTimeout(timeout);
  }, [search]);
  function setFilter(patch: Partial<ItemFilters>) {
    setFilters((prev) => ({ ...prev, ...patch }));
    setCursors(['']);
  }

  /** Enter in the search box looks the text up as a barcode and opens the item it belongs to. */
  async function scan() {
    const code = search.trim();
    if (!code || /\s/.test(code)) return;
    try {
      const hit = await request<{
        barcode: string;
        unit: string;
        baseQuantity: number;
        baseUnit: string;
        item: Row;
      }>(`/items/by-barcode?code=${encodeURIComponent(code)}`);
      setItemId(hit.item.id);
      notify(
        `Barcode ${hit.barcode}: 1 ${hit.unit}${hit.unit !== hit.baseUnit ? ` = ${fmt(hit.baseQuantity, 6)} ${hit.baseUnit}` : ''} of ${text(hit.item.sku)}`,
      );
    } catch {
      /* Not a barcode: the list already shows the text search results. */
    }
  }

  const facets = page.data?.facets || {};
  const rows = page.data?.items || [];
  const total = page.data?.total || 0;
  const start = (cursors.length - 1) * PAGE_SIZE;
  const filtered = !!(filters.q || filters.type || filters.category || filters.active);
  const categoryRows = categories.data?.items || [];

  return (
    <>
      <div className="summary-strip">
        <div>
          <span>Active items</span>
          <strong>{page.data ? fmt(facets.active) : '—'}</strong>
        </div>
        <div>
          <span>Semi-finished</span>
          <strong>{page.data ? fmt(facets.SEMI_FINISHED) : '—'}</strong>
        </div>
        <div>
          <span>Variant families</span>
          <strong>{families.data ? families.data.items.length : '—'}</strong>
        </div>
        <div>
          <span>Categories</span>
          <strong>{categories.data ? categoryRows.length : '—'}</strong>
        </div>
      </div>

      <Panel>
        <Tabs
          label="Item type"
          items={[
            {
              value: '',
              label: 'All items',
              count: page.data ? num(facets.active) + num(facets.inactive) : undefined,
            },
            ...itemTypes.map((t) => ({
              value: t,
              label: itemTypePlural[t],
              count: page.data ? num(facets[t]) : undefined,
            })),
          ]}
          value={filters.type}
          onChange={(type) => setFilter({ type })}
        />
        <div className="item-toolbar">
          <div className="search-input">
            <Search size={16} />
            <input
              aria-label="Search SKU, name, description or barcode"
              placeholder="Search SKU, name or scan a barcode…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') void scan();
              }}
            />
            <ScanBarcode size={16} aria-hidden="true" />
          </div>
          <select
            className="filter-select"
            aria-label="Category"
            value={filters.category}
            onChange={(e) => setFilter({ category: e.target.value })}
          >
            <option value="">All categories</option>
            <option value="none">Uncategorized</option>
            {categoryRows.map((c) => (
              <option key={c.id} value={c.id}>
                {`${'   '.repeat(num(c.depth))}${text(c.name)}`}
              </option>
            ))}
          </select>
          <select
            className="filter-select"
            aria-label="Status"
            value={filters.active}
            onChange={(e) => setFilter({ active: e.target.value })}
          >
            <option value="">All statuses</option>
            <option value="true">Active</option>
            <option value="false">Inactive</option>
          </select>
          <span className="grow" />
          <span className="muted">{page.data ? `${fmt(total)} records` : ''}</span>
          {page.isFetching && !page.isPending && <LoaderCircle className="spin" size={14} />}
        </div>
        {page.isPending ? (
          <div className="item-list-state">
            <LoaderCircle className="spin" />
          </div>
        ) : page.isError ? (
          <div className="item-list-state error" role="alert">
            {(page.error as Error).message}
          </div>
        ) : rows.length === 0 ? (
          <Empty
            title={filtered ? 'No matching items' : 'No items yet'}
            body={
              filtered
                ? 'Try a different search, type, category or status.'
                : 'Create your first product or material to start planning.'
            }
          />
        ) : (
          <div className={page.isPlaceholderData ? 'is-refreshing' : undefined}>
            <Table
              compact
              pageSize={500}
              rows={rows}
              onRow={(r) => setItemId(r.id)}
              columns={[
                {
                  label: 'Item',
                  render: (r) => (
                    <div className="product-cell">
                      <ProductMark kind={r.name} type={r.type} small />
                      <div className="cell-stack">
                        <strong>{text(r.name)}</strong>
                        <span>
                          {text(r.sku)}
                          {r.template_code ? ` · ${text(r.template_code)} variant` : ''}
                        </span>
                      </div>
                    </div>
                  ),
                },
                { label: 'Type', render: (r) => <ItemTypeBadge type={r.type} /> },
                { label: 'Category', render: (r) => text(r.category) || '—' },
                { label: 'Base unit', render: (r) => text(r.uom) },
                { label: 'Available', render: (r) => `${fmt(r.available, 3)} ${text(r.uom)}` },
                { label: 'Safety stock', render: (r) => fmt(r.reorder_level, 3) },
                { label: 'Standard cost', render: (r) => money(r.standard_cost) },
                { label: 'State', render: (r) => <Badge value={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
              ]}
            />
          </div>
        )}
        <div className="table-footer">
          <span>
            {rows.length ? `Showing ${start + 1}–${start + rows.length} of ${fmt(total)}` : 'Showing 0 of 0'}
          </span>
          <div>
            <button
              className="icon-button"
              aria-label="Previous page"
              disabled={cursors.length === 1}
              onClick={() => setCursors(cursors.slice(0, -1))}
            >
              <ChevronLeft size={16} />
            </button>
            <span>
              {cursors.length} / {Math.max(1, Math.ceil(total / PAGE_SIZE))}
            </span>
            <button
              className="icon-button"
              aria-label="Next page"
              disabled={!page.data?.next || page.isPlaceholderData}
              onClick={() => page.data?.next && setCursors([...cursors, page.data.next])}
            >
              <ChevronRight size={16} />
            </button>
          </div>
        </div>
      </Panel>

      <div className="master-grid">
        <Panel
          title="Variant families"
          subtitle="Templates with attributes; every variant is a real item with its own SKU and stock"
          action={
            editable && (
              <button className="button secondary small" onClick={() => void createFamily(workspace)}>
                <Plus size={13} />
                New family
              </button>
            )
          }
        >
          {families.isPending ? (
            <div className="item-list-state">
              <LoaderCircle className="spin" />
            </div>
          ) : families.isError ? (
            <div className="item-list-state error">{(families.error as Error).message}</div>
          ) : (
            <Table
              compact
              pageSize={500}
              rows={families.data.items}
              onRow={(r) => setFamilyId(r.id)}
              columns={[
                {
                  label: 'Family',
                  render: (r) => (
                    <div className="cell-stack">
                      <strong>{text(r.name)}</strong>
                      <span>
                        {text(r.code)} · {text(r.uom)}
                      </span>
                    </div>
                  ),
                },
                { label: 'Type', render: (r) => <ItemTypeBadge type={r.type} /> },
                {
                  label: 'Attributes',
                  render: (r) => (
                    <span className="family-attributes">
                      {(families.data.items.find((f) => f.id === r.id)?.attributes || []).map((a) => (
                        <span className="chip" key={a.name}>
                          <strong>{a.name}</strong> {a.values.length}
                        </span>
                      ))}
                    </span>
                  ),
                },
                { label: 'Variants', key: 'variants' },
              ]}
            />
          )}
        </Panel>

        <Panel
          title="Categories"
          subtitle="Group items for search, filters and reporting"
          action={
            editable && (
              <button
                className="button secondary small"
                onClick={() => createCategory(workspace, categoryRows)}
              >
                <Plus size={13} />
                New category
              </button>
            )
          }
        >
          {categories.isPending ? (
            <div className="item-list-state">
              <LoaderCircle className="spin" />
            </div>
          ) : categories.isError ? (
            <div className="item-list-state error">{(categories.error as Error).message}</div>
          ) : (
            <Table
              compact
              pageSize={500}
              rows={categoryRows}
              onRow={(r) => {
                setFilter({ category: r.id });
                window.scrollTo({ top: 0, behavior: 'smooth' });
              }}
              columns={[
                {
                  label: 'Category',
                  render: (r) => (
                    <span className="tree-name" style={{ paddingLeft: num(r.depth) * 16 }}>
                      {num(r.depth) > 0 && <i />}
                      {text(r.name)}
                    </span>
                  ),
                },
                { label: 'Items', render: (r) => fmt(r.total_items) },
                { label: 'State', render: (r) => <Badge value={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
                ...(editable
                  ? [
                      {
                        label: '',
                        render: (r: Row) => (
                          <span className="row-actions">
                            <button
                              className="icon-button"
                              aria-label={`Add a category under ${text(r.name)}`}
                              onClick={(e) => {
                                e.stopPropagation();
                                createCategory(workspace, categoryRows, r);
                              }}
                            >
                              <Plus size={14} />
                            </button>
                            <button
                              className="icon-button"
                              aria-label={`Edit ${text(r.name)}`}
                              onClick={(e) => {
                                e.stopPropagation();
                                editCategory(workspace, categoryRows, r);
                              }}
                            >
                              <Pencil size={14} />
                            </button>
                          </span>
                        ),
                      },
                    ]
                  : []),
              ]}
            />
          )}
        </Panel>
      </div>

      <Panel
        className="spaced-panel"
        title="Units of measure"
        subtitle="Stock is kept in each item's base unit; alternate units convert through item-specific factors"
        action={
          editable && (
            <button className="button secondary small" onClick={() => createUnit(workspace)}>
              <Plus size={13} />
              New unit
            </button>
          )
        }
      >
        {units.isPending ? (
          <div className="item-list-state">
            <LoaderCircle className="spin" />
          </div>
        ) : units.isError ? (
          <div className="item-list-state error">{(units.error as Error).message}</div>
        ) : (
          <Table
            rows={units.data.items.map((u) => ({ ...u, id: text(u.code) }))}
            pageSize={8}
            columns={[
              {
                label: 'Unit',
                render: (r) => (
                  <div className="cell-stack">
                    <strong>{text(r.code)}</strong>
                    <span>{text(r.name)}</span>
                  </div>
                ),
              },
              {
                label: 'Kind',
                render: (r) => <span className="capitalize">{text(r.kind).toLowerCase()}</span>,
              },
              { label: 'Decimals', key: 'decimals' },
              { label: 'Fixed size', render: (r) => (r.ratio == null ? 'Per item' : fmt(r.ratio, 12)) },
              { label: 'Base unit of', render: (r) => `${fmt(r.base_items)} items` },
              { label: 'Conversions', key: 'conversions' },
              { label: 'State', render: (r) => <Badge value={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
              ...(editable
                ? [
                    {
                      label: '',
                      render: (r: Row) => (
                        <button
                          className="icon-button"
                          aria-label={`Edit ${text(r.code)}`}
                          onClick={() => editUnit(workspace, r)}
                        >
                          <Pencil size={14} />
                        </button>
                      ),
                    },
                  ]
                : []),
            ]}
          />
        )}
      </Panel>

      <AnimatePresence>
        {itemId && (
          <ItemDrawer
            key={`item-${itemId}`}
            itemId={itemId}
            onClose={() => setItemId(undefined)}
            onOpenItem={(id) => setItemId(id)}
            onOpenFamily={(id) => {
              setItemId(undefined);
              setFamilyId(id);
            }}
          />
        )}
        {familyId && (
          <FamilyDrawer
            key={`family-${familyId}`}
            familyId={familyId}
            onClose={() => setFamilyId(undefined)}
            onOpenItem={(id) => {
              setFamilyId(undefined);
              setItemId(id);
            }}
          />
        )}
      </AnimatePresence>
    </>
  );
}
