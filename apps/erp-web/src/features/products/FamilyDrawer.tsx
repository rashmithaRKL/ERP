import { useState } from 'react';
import { AnimatePresence } from 'motion/react';
import { LoaderCircle, Pencil, Sparkles } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Badge, Overlay, Table } from '../../components';
import { fmt, money, text } from '../../lib/format';
import { editFamily } from './forms';
import { ItemTypeBadge } from './ItemTypeBadge';
import { useFamily } from './itemMaster';
import { ProductMark } from './ProductMark';
import { VariantGenerator } from './VariantGenerator';

/** Variant family drawer: attributes, generated variants and the generator. */
export function FamilyDrawer({
  familyId,
  onClose,
  onOpenItem,
}: {
  familyId: string;
  onClose: () => void;
  onOpenItem: (id: string) => void;
}) {
  const workspace = useWorkspace();
  const { can } = workspace;
  const family = useFamily(familyId);
  const [generating, setGenerating] = useState(false);
  const t = family.data?.template;
  return (
    <>
      <Overlay
        title={text(t?.code) || 'Variant family'}
        subtitle="Variant family · the template is not stocked; variants are ordinary items"
        onClose={onClose}
        drawer
        wide
      >
        {family.isPending ? (
          <div className="drawer-body item-list-state">
            <LoaderCircle className="spin" />
          </div>
        ) : family.isError ? (
          <div className="drawer-body">
            <div className="form-error" role="alert">
              {(family.error as Error).message}
            </div>
          </div>
        ) : (
          t && (
            <div className="item-drawer">
              <div className="drawer-body">
                <div className="detail-product">
                  <ProductMark kind={t.name} type={t.type} />
                  <div>
                    <h2>{text(t.name)}</h2>
                    <div className="item-drawer-meta">
                      <ItemTypeBadge type={t.type} />
                      <span>
                        Base unit {text(t.unit_name)} ({text(t.uom)})
                      </span>
                      <span>{text(t.category) || 'No category'}</span>
                    </div>
                  </div>
                </div>
                {text(t.description) && <p className="muted">{text(t.description)}</p>}
                <div className="drawer-section">
                  <h3>Attributes</h3>
                  <div className="family-attributes">
                    {t.attributes.map((a) => (
                      <span className="chip" key={a.name}>
                        <strong>{a.name}</strong> {a.values.join(' / ')}
                      </span>
                    ))}
                  </div>
                </div>
                <div className="drawer-section">
                  <h3>
                    <span>Variants · {family.data.variants.length}</span>
                  </h3>
                  <div className="panel-inline">
                    <Table
                      compact
                      pageSize={500}
                      rows={family.data.variants}
                      onRow={(r) => onOpenItem(r.id)}
                      columns={[
                        {
                          label: 'Variant',
                          render: (r) => (
                            <div className="cell-stack">
                              <strong>{text(r.variant_label)}</strong>
                              <span>{text(r.sku)}</span>
                            </div>
                          ),
                        },
                        { label: 'Standard cost', render: (r) => money(r.standard_cost) },
                        { label: 'On hand', render: (r) => `${fmt(r.on_hand, 3)} ${text(r.uom)}` },
                        { label: 'Available', render: (r) => fmt(r.available, 3) },
                        { label: 'State', render: (r) => <Badge value={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
                      ]}
                    />
                  </div>
                </div>
              </div>
              {can('PLANNER') && (
                <div className="dialog-footer detail-footer">
                  <button
                    className="button secondary"
                    onClick={() => void editFamily(workspace, family.data)}
                  >
                    <Pencil size={15} />
                    Edit family
                  </button>
                  <span className="grow" />
                  <button className="button primary" onClick={() => setGenerating(true)}>
                    <Sparkles size={15} />
                    Generate variants
                  </button>
                </div>
              )}
            </div>
          )
        )}
      </Overlay>
      <AnimatePresence>
        {generating && family.data && (
          <VariantGenerator
            key="variant-generator"
            family={family.data}
            onClose={() => setGenerating(false)}
          />
        )}
      </AnimatePresence>
    </>
  );
}
