import { useQuery } from '@tanstack/react-query';
import { LoaderCircle } from 'lucide-react';
import { useWorkspace } from '../../app/workspace';
import { Overlay } from '../../components';
import { request } from '../../lib/api';
import { text } from '../../lib/format';
import { recordOutput } from './forms';
import { OrderDetail } from './OrderDetail';
import type { OrderDetailData } from './OrderDetail';

/** Manufacturing order side drawer. Opened from anywhere with `useWorkspace().openOrder(id)`. */
export function OrderDrawer({
  orderId,
  error,
  onClose,
}: {
  orderId: string;
  error: string;
  onClose: () => void;
}) {
  const workspace = useWorkspace();
  const { can, busy, mutate } = workspace;
  const detailQuery = useQuery({
    queryKey: ['order', orderId],
    queryFn: () => request<OrderDetailData>(`/manufacturing/${orderId}`),
    enabled: !!orderId,
  });
  return (
    <Overlay
      title={text(detailQuery.data?.order.number) || 'Manufacturing order'}
      subtitle="Production details, material snapshot and audit history"
      onClose={onClose}
      drawer
      wide
    >
      {detailQuery.isPending ? (
        <div className="drawer-body">
          <LoaderCircle className="spin" />
        </div>
      ) : detailQuery.isError ? (
        <div className="form-error">{(detailQuery.error as Error).message}</div>
      ) : (
        detailQuery.data && (
          <OrderDetail
            data={detailQuery.data}
            error={error}
            can={can}
            busy={busy}
            onAction={(action) => mutate(`/manufacturing/${orderId}/actions/${action}`)}
            onOutput={() => recordOutput(workspace, detailQuery.data!.order)}
          />
        )
      )}
    </Overlay>
  );
}
