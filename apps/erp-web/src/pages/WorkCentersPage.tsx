import { useNavigate } from 'react-router-dom';
import { Activity, ArrowRight, Cpu } from 'lucide-react';
import { useStateData, useWorkspace } from '../app/workspace';
import { Badge, Panel } from '../components';
import { fmt, money, text } from '../lib/format';

/** Route `/machines`: registered work centers and their recorded states. */
export default function WorkCentersPage() {
  const navigate = useNavigate();
  const { setSearch } = useWorkspace();
  const data = useStateData();
  return (
    <>
      <div className="info-banner">
        <Activity size={20} />
        <div>
          <strong>Recorded machine states</strong>
          <p>
            These are registered operational states. Automated machine telemetry and validated OEE measurement
            are scheduled for a later phase.
          </p>
        </div>
      </div>
      <div className="machine-grid">
        {data.machines.map((m) => (
          <Panel key={m.id} className="machine-card">
            <div className="machine-card-top">
              <span className="large-machine-icon">
                <Cpu size={25} />
              </span>
              <Badge value={m.status} />
            </div>
            <h2>{text(m.code)}</h2>
            <p>{text(m.name)}</p>
            <div className="machine-card-meta">
              <span>
                Process<strong>{text(m.kind)}</strong>
              </span>
              <span>
                Recorded output<strong>{fmt(m.output)} pcs</strong>
              </span>
              <span>
                Machine rate<strong>{money(m.hourly_rate)} / hr</strong>
              </span>
            </div>
            <button
              className="text-button"
              onClick={() => {
                navigate('/manufacturing');
                setSearch(text(m.code));
              }}
            >
              View assigned work
              <ArrowRight size={14} />
            </button>
          </Panel>
        ))}
      </div>
    </>
  );
}
