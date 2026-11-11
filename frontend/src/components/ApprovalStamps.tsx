export interface ApprovalStep {
  role: string;
  name: string;
  detail?: string | null;
  state: string;
  tone?: 'ok' | 'info' | 'warn' | 'danger';
}

const tones = { ok: 'green', info: 'blue', warn: 'amber', danger: 'red' } as const;

/** 결재란(설계 7.8): 단계마다 128px 칸에 역할·이름·소속·상태를 둔다. 모바일에서는 세로 목록이다. */
export function ApprovalStamps({ steps }: { steps: ApprovalStep[] }) {
  return (
    <ol className="wl-stamps" aria-label="결재란">
      {steps.map(step => (
        <li className="wl-stamp" key={step.role}>
          <span className="wl-stamp-role">{step.role}</span>
          <span className="wl-stamp-who"><strong>{step.name}</strong>{step.detail && <small>{step.detail}</small>}</span>
          <span className="wl-stamp-state"><span className={step.tone ? `t-tag ${tones[step.tone]}` : 't-tag'}>{step.state}</span></span>
        </li>
      ))}
    </ol>
  );
}
