import { BookOpen, ShieldCheck } from 'lucide-react';
import { Overlay } from '../components';

/** "Help & resources" dialog (sidebar, demo ribbon and footer links). */
export function HelpDialog({ onClose }: { onClose: () => void }) {
  return (
    <Overlay
      title="Built around your factory"
      subtitle="DexLanka Manufacturing Pro · Foundation release 0.1.0"
      onClose={onClose}
      wide
    >
      <div className="help-body">
        <div className="info-banner">
          <BookOpen size={22} />
          <div>
            <strong>A real manufacturing workflow</strong>
            <p>
              Sales order → planning → material reservation & issue → production → quality release → dispatch
              → reconciled costing.
            </p>
          </div>
        </div>
        <h3>Getting started</h3>
        <ol>
          <li>Create a sales order and manufacturing order from an approved BOM.</li>
          <li>
            Ask a different authorized team member to approve the order. Demo roles are available in the
            profile menu.
          </li>
          <li>Reserve materials, issue to WIP, and start the manufacturing order.</li>
          <li>
            On the Shop floor, pick your work center and run the order&apos;s operations (start, pause,
            report, finish) when its product has an approved routing. Routings, shift calendars and skills
            live under Routings &amp; shifts.
          </li>
          <li>
            Record partial or final output on the shop floor. Review each resulting lot in Quality control.
          </li>
          <li>
            Dispatch released finished stock from Sales orders. Review cost allocations and journal postings
            in Costs & reports.
          </li>
        </ol>
        <h3>What’s available in this release</h3>
        <p>
          Authenticated sessions, tenant-scoped records, the manufacturing lifecycle, basic MRP, purchases and
          incoming QC, lot inventory, production costs, audit records and CSV exports. Maintenance tracks
          machine downtime by reason, corrective and preventive work orders with checklists, spare parts and
          labour, and MTBF/MTTR per work center.
        </p>
        <p>
          Quality management: inspection plans with measured characteristics, nonconformance reports raised by
          failed inspections or by hand, dispositions (use as is with independent approval, scrap, return to
          supplier, rework into a new re-inspected lot) and CAPA with root-cause analysis and independent
          effectiveness checks.
        </p>
        <p>
          Production schedule: planners run a finite-capacity scheduler over every open operation (shift
          calendars, holidays, overtime, maintenance windows and open downtime, alternate work centers),
          compare the draft with the published schedule, publish it, and pin or drag operations to a work
          center and time. Everyone sees the published Gantt, late orders and capacity load; the Shop floor
          lists the next scheduled operations of each work center.
        </p>
        <p>
          Performance (OEE) measures availability, performance and quality per work center and business day
          from the shift calendar, operation time logs, quantity reports and downtime; days without planned
          time or records are shown as not measured, never as 0 %, and planners close completed days so later
          calendar changes do not rewrite them.
        </p>
        <p>
          Traceability & recalls: trace any lot, manufacturing order, goods receipt, purchase or sales order,
          delivery or supplier backward to its suppliers and forward to the customers who received it, as a
          graph or an indented tree. Quality opens a recall from suspect lots or a supplier’s deliveries,
          contains it (affected stock in hand goes on hold through recall nonconformances), records each
          customer notification, exports the notification list and closes it once everything is dispositioned.
        </p>
        <h3>What remains on the roadmap</h3>
        <p>
          Full finance and tax localization, personnel workflows, extended onboarding, MFA, cloud
          synchronization, signed Windows installation, SaaS billing and AI services. The repository documents
          acceptance criteria and readiness gaps.
        </p>
        <div className="help-callout">
          <ShieldCheck size={18} />
          <p>
            This is an engineering foundation with demonstration data. Production certification, security
            review, installation validation and backup recovery tests remain required before commercial
            operation.
          </p>
        </div>
      </div>
    </Overlay>
  );
}
