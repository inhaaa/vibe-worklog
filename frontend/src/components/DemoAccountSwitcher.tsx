import { useEffect, useRef, useState } from 'react';
import type { DemoAccount } from '../types';
import { fetchPage } from '../transport';
import { Form } from '../ui';
import { Modal } from './Modal';
import './DemoAccountSwitcher.css';

function validAccount(value: unknown): value is DemoAccount {
  if (!value || typeof value !== 'object') return false;
  const account = value as Partial<DemoAccount>;
  return typeof account.employeeNo === 'string' && account.employeeNo.length > 0 &&
    typeof account.employeeName === 'string' && typeof account.password === 'string' && account.password.length > 0 &&
    Array.isArray(account.roles) && account.roles.every(role => typeof role === 'string');
}

function AccountDialog({ currentEmployeeNo, busy, onClose }: {
  currentEmployeeNo?: string; busy: boolean; onClose: () => void;
}) {
  const [accounts, setAccounts] = useState<DemoAccount[] | null>(null);
  const [employeeNo, setEmployeeNo] = useState('');
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const submitting = useRef(false);
  useEffect(() => {
    const resetSubmission = () => { submitting.current = false; };
    const confirmNavigation = (event: BeforeUnloadEvent) => {
      // 기존 미저장 경고에서 이동을 취소하면 같은 폼으로 다시 시도할 수 있다.
      if (event.defaultPrevented) window.setTimeout(resetSubmission, 0);
    };
    window.addEventListener('beforeunload', confirmNavigation);
    window.addEventListener('pageshow', resetSubmission);
    return () => {
      window.removeEventListener('beforeunload', confirmNavigation);
      window.removeEventListener('pageshow', resetSubmission);
    };
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    setAccounts(null);
    setError('');
    void fetchPage('/demo', { signal: controller.signal }).then(result => {
      if (controller.signal.aborted) return;
      if (result.status >= 400 || result.envelope.page !== 'demo' || !Array.isArray(result.envelope.data.demoAccounts))
        throw new Error('테스트 사원 목록을 불러오지 못했습니다. 다시 시도해 주세요.');
      const available = result.envelope.data.demoAccounts.filter(validAccount);
      if (!available.length) {
        setError('현재 선택할 수 있는 테스트 사원이 없습니다.');
        return;
      }
      setAccounts(available);
      setEmployeeNo(available.some(account => account.employeeNo === currentEmployeeNo) ? currentEmployeeNo! : '');
    }).catch(() => {
      if (!controller.signal.aborted) setError('테스트 사원 목록을 불러오지 못했습니다. 다시 시도해 주세요.');
    });
    return () => controller.abort();
  }, [attempt, currentEmployeeNo]);
  const selected = accounts?.find(account => account.employeeNo === employeeNo);
  const canSwitch = Boolean(selected && selected.employeeNo !== currentEmployeeNo);
  return <Modal title="사번 변경" size="s" onClose={onClose} busy={busy} className="t-demo-account-modal">
    <p>다른 테스트 사원으로 로그인합니다. 선택한 사원의 보유 권한으로 업무를 확인할 수 있습니다.</p>
    {error ? <>
      <p className="notice error" role="alert">{error}</p>
      <button type="button" className="t-btn" disabled={busy} onClick={() => setAttempt(value => value + 1)}>다시 불러오기</button>
    </> : accounts ? <Form action="/login" method="post" onSubmit={event => {
      // 인증 주체를 바꿀 때는 문서도 새로 열어 이전 화면과 권한 선택을 남기지 않는다.
      event.stopPropagation();
      if (submitting.current || busy || !canSwitch || (document.querySelector('[data-unsaved-changes="true"]') &&
        !window.confirm('저장하지 않은 변경이 있습니다. 편집 내용을 버리고 다른 사원으로 로그인할까요?'))) event.preventDefault();
      else submitting.current = true;
    }}>
      <label htmlFor="workspace-demo-account">테스트 사원</label>
      <select id="workspace-demo-account" className="t-select" value={employeeNo} required disabled={busy}
        onChange={event => setEmployeeNo(event.target.value)}>
        <option value="" disabled>전환할 사원 선택</option>
        {accounts.map(account => <option key={account.employeeNo} value={account.employeeNo} disabled={account.employeeNo === currentEmployeeNo}>
          {account.employeeNo} {account.employeeName}{account.employeeNo === currentEmployeeNo ? ' (현재 사원)' : ''}
        </option>)}
      </select>
      <input type="hidden" name="email" value={selected?.employeeNo ?? ''} />
      <input type="hidden" name="password" value={selected?.password ?? ''} />
      <button type="submit" className="t-btn primary" disabled={busy || !canSwitch}>선택한 사원으로 전환</button>
    </Form> : <p role="status">테스트 사원 목록을 불러오는 중입니다.</p>}
  </Modal>;
}

export function DemoAccountSwitcher({ currentEmployeeNo, busy = false }: { currentEmployeeNo?: string; busy?: boolean }) {
  const [open, setOpen] = useState(false);
  return <>
    <button className="t-btn t-demo-account-trigger" type="button" aria-haspopup="dialog" disabled={busy} onClick={() => setOpen(true)}>사번 변경</button>
    {open && <AccountDialog currentEmployeeNo={currentEmployeeNo} busy={busy} onClose={() => setOpen(false)} />}
  </>;
}
