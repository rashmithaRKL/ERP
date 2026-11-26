import { useNavigate } from 'react-router-dom';
import { Bell, ChevronRight } from 'lucide-react';
import { Empty, Overlay } from '../components';
import { useAttention } from '../features/attention/useAttention';
import { fmt } from '../lib/format';

/**
 * Bell-icon notifications: every group of the shared attention model (`features/attention`), with up to five records
 * each and a "more" link, in the same order and with the same counts as the overview's "Needs your attention" panel.
 */
export function AttentionCenter({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const { groups, total, caughtUp } = useAttention();
  return (
    <Overlay
      title="Attention center"
      subtitle={
        caughtUp
          ? 'Exceptions from current manufacturing records'
          : `${fmt(total)} ${total === 1 ? 'record needs' : 'records need'} attention`
      }
      onClose={onClose}
    >
      <div className="notification-list">
        {groups.flatMap((group) =>
          group.items.map((item) => (
            <button
              key={item.key}
              onClick={() => {
                navigate(item.path);
                onClose();
              }}
            >
              <span className={`attention-symbol ${group.tone}`}>
                <group.icon size={17} />
              </span>
              <span>
                <strong>{item.title}</strong>
                {item.body && <small>{item.body}</small>}
              </span>
              <ChevronRight size={15} />
            </button>
          )),
        )}
        {caughtUp && <Empty title="You’re all caught up" />}
      </div>
    </Overlay>
  );
}

/** Header bell: the dot and label follow the same attention model as the attention center. */
export function NotificationButton({ onOpen }: { onOpen: () => void }) {
  const { total, caughtUp } = useAttention();
  return (
    <button
      className={`icon-button notification-button ${caughtUp ? '' : 'has-notification'}`}
      aria-label={
        caughtUp
          ? 'Open notifications'
          : `Open notifications (${fmt(total)} ${total === 1 ? 'record needs' : 'records need'} attention)`
      }
      onClick={onOpen}
    >
      <Bell size={19} />
    </button>
  );
}
