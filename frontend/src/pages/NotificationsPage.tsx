import { Form } from '../ui';
import { PageHeader } from '../components/PageHeader';
import { WorklogSheet, sheetColumn } from '../components/WorklogSheet';

const kinds = { APPROVAL_REQUESTED: ['결재 요청', 'blue'], APPROVED: ['승인', 'green'], REJECTED: ['반려', 'amber'] } as const;

export interface NotificationRow {
  id: string; requestId: string; kind: keyof typeof kinds; requesterName: string; summary: string; createdAt: string; read: boolean;
}

export function NotificationsPage({ rows, unreadCount = 0 }: { rows: NotificationRow[]; unreadCount?: number }) {
  const unread = unreadCount > 0 || rows.some(row => !row.read);
  return <>
    <PageHeader title="알림" actions={unread ? <Form action="/me/notifications/read-all"><button type="submit" className="t-btn">모두 읽음</button></Form> : undefined} />
    <section className="wl-section">
      <WorklogSheet
        label="알림 목록"
        title="최근 알림"
        rows={rows}
        getRowId={(row) => row.id}
        scope="all"
        emptyMessage="받은 알림이 없습니다."
        columns={[
          sheetColumn<NotificationRow>({ colId: 'kind', headerName: '구분', valueGetter: ({ data }) => data && kinds[data.kind][0], minWidth: 110 },
            (row) => <span className={`t-tag ${kinds[row.kind][1]}`}>{kinds[row.kind][0]}</span>),
          { colId: 'requesterName', headerName: '신청자', valueGetter: ({ data }) => data?.requesterName, minWidth: 110 },
          sheetColumn<NotificationRow>({ colId: 'summary', headerName: '신청 내용', valueGetter: ({ data }) => data?.summary, minWidth: 200, flex: 1 },
            (row) => <a href={`/requests/${row.requestId}`}>{row.summary}</a>),
          { colId: 'createdAt', headerName: '시각', valueGetter: ({ data }) => data?.createdAt, minWidth: 150 },
          sheetColumn<NotificationRow>({ colId: 'read', headerName: '읽음', valueGetter: ({ data }) => (data?.read ? '읽음' : '안 읽음'), minWidth: 90 },
            (row) => row.read ? <span className="t-subtle">읽음</span> : <strong>안 읽음</strong>),
        ]}
      />
    </section>
  </>;
}
