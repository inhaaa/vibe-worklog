import React, { useState } from 'react';
import type { DemoAccount, PageProps } from '../types';
import { Form, Field, TextAreaField, ErrorNotice, fmtDateTime } from '../ui';
import './DemoAccess.css';
import './DemoLanding.css';

interface Flags {
  error?: boolean;
  logout?: boolean;
  invited?: boolean;
  reset?: boolean;
  requested?: boolean;
  saved?: boolean;
}
interface AuthModel {
  flags?: Flags;
  error?: string;
  localDemo?: boolean;
  publicDemo?: boolean;
  companyCode?: string;
  inviteByCompany?: boolean;
  demoAccounts?: DemoAccount[];
}
interface Tenant {
  id: string;
  companyCode: string;
  displayName: string;
  active: boolean;
  version: number;
}
interface TenantForm {
  companyCode: string;
  companyName: string;
  employeeNo: string;
  employeeName: string;
  joinedOn: string;
  email: string;
  relationshipBasis: string;
  reason: string;
  commandId: string;
}
interface OperatorAudit {
  id: string;
  createdAt: string;
  action: string;
  reason: string;
  dbActor: string;
}
interface TenantsModel extends AuthModel {
  tenants: Tenant[];
  audit: OperatorAudit[];
  form: TenantForm;
}
const hidden = (name: string, value: string | number | boolean | undefined) => (
  <input type="hidden" name={name} value={value == null ? '' : String(value)} />
);

function Demo({ model: m }: { model: AuthModel }) {
  const administrator = m.demoAccounts?.find(account =>
    account.employeeNo === '99999' && account.roles.includes('ATTENDANCE_ADMIN') && account.password,
  );
  const start = <button className="t-landing-start" type="submit" disabled={!administrator}>WorkLog 시작하기</button>;
  return (
    <section className="t-intro t-worklog-landing" aria-labelledby="demo-title">
      <header className="t-intro-nav">
        <div className="t-wordmark">
          <span className="t-logo" aria-hidden="true">
            W
          </span>
          <span>
            WorkLog<span className="t-brand-sub">근태관리 워크스페이스</span>
          </span>
        </div>
      </header>
      <div className="t-hero">
        <div className="t-hero-copy">
          <h1 id="demo-title">
            일하는 하루,
            <br />
            기록이 되다.
          </h1>
          <p className="t-hero-description">
            출퇴근부터 휴가까지,
            <br />
            우리 팀의 하루를 연결하는 근태관리.
          </p>
          {administrator ? (
            <Form id="demo-login-form" action="/login" method="post">
              {hidden('email', administrator.employeeNo)}
              {hidden('password', administrator.password)}
              {start}
            </Form>
          ) : (
            <>
              {start}
              <p className="notice" role="status">기본 데모 관리자 계정을 사용할 수 없습니다.</p>
            </>
          )}
          {m.publicDemo && <p className="t-enter-note">모든 방문자가 함께 사용하는 가상 데이터입니다. 실제 개인정보를 입력하지 마세요. 체험 자료는 초기화될 수 있습니다.</p>}
          <div className="t-feature-labels">
            <span>출퇴근 기록</span>
            <span>근무 일정</span>
            <span>휴가·직원 관리</span>
          </div>
          {m.flags?.error && (
            <p className="notice error" role="alert">
              데모에 입장하지 못했습니다. 다시 시도해 주세요.
            </p>
          )}
          {m.flags?.logout && (
            <p className="notice" role="status">
              로그아웃했습니다.
            </p>
          )}
        </div>
        <div className="t-workday-scene" aria-hidden="true">
          <div className="t-scene-orbit" />
          <div className="t-scene-floor" />
          <div className="t-calendar-object">
            <div className="t-calendar-sheet t-calendar-sheet-back" />
            <div className="t-calendar-sheet t-calendar-sheet-middle" />
            <div className="t-calendar-face">
              <div className="t-calendar-binding"><i /><i /></div>
              <div className="t-calendar-top"><span>WorkLog</span><span>나의 일주일</span></div>
              <div className="t-calendar-grid">
                {['월', '화', '수', '목', '금'].map((day, index) => (
                  <div className={index === 2 ? 't-calendar-day is-leave' : 't-calendar-day'} key={day}>
                    <span>{day}</span>
                    <div className="t-calendar-mark">{index === 2 ? <svg viewBox="0 0 32 32"><path d="M7 25C9 12 15 7 26 6c0 12-5 20-14 18M9 24l11-11" /></svg> : <svg viewBox="0 0 32 32"><path d="m9 16 5 5 10-11" /></svg>}</div>
                    <span>{index === 2 ? '휴가' : '근무'}</span>
                  </div>
                ))}
              </div>
              <div className="t-calendar-bottom"><span>일정과 기록을 한곳에</span><span>평일 일정</span></div>
            </div>
          </div>
          <div className="t-clock-object">
            <div className="t-clock-dial">
              <i className="t-clock-tick tick-12" /><i className="t-clock-tick tick-3" /><i className="t-clock-tick tick-6" /><i className="t-clock-tick tick-9" />
              <i className="t-clock-hand t-clock-hour" /><i className="t-clock-hand t-clock-minute" /><i className="t-clock-pin" />
            </div>
          </div>
          <div className="t-record-object">
            <span className="t-record-check"><svg viewBox="0 0 24 24"><path d="m5 12 4 4L19 6" /></svg></span>
            <span><strong>하루의 시작과 끝</strong><small>출퇴근 기록</small></span>
          </div>
          <span className="t-scene-caption">근무와 휴가가 함께하는 한 주</span>
        </div>
      </div>
      <section className="t-landing-workflow" aria-labelledby="demo-guide-title">
        <h2 id="demo-guide-title">일하는 시간도,<br />쉬어가는 시간도.</h2>
        <dl>
          <div><dt>출퇴근</dt><dd>하루의 시작과 끝을 기록해요.</dd></div>
          <div><dt>근무 일정</dt><dd>우리 팀의 흐름을 살펴봐요.</dd></div>
          <div><dt>휴가와 신청</dt><dd>신청부터 확인까지 이어져요.</dd></div>
        </dl>
      </section>
      <footer className="t-intro-footer">
        <span>WorkLog <span className="t-intro-footer-tagline">일하는 하루의 기준</span></span>
        <span>{m.publicDemo ? '가상 자료로 체험하는 공개 데모' : '합성 자료로 둘러보는 로컬 데모'}</span>
      </footer>
    </section>
  );
}
function Login({ model: m }: { model: AuthModel }) {
  return (
    <main className="login-shell">
      <section className="panel" aria-labelledby="login-title">
        <h1 id="login-title">로그인</h1>
        <p className="t-auth-context"><span>WorkLog</span><span>{m.localDemo ? '데모 계정' : '회사 계정'}</span></p>
        <p>
          {m.localDemo
            ? '사번과 비밀번호로 로그인해 주세요.'
            : '초대 메일의 회사 코드와 계정 정보를 입력해 주세요.'}
        </p>
        {m.flags?.error && (
          <p className="notice error" role="alert">
            로그인 정보를 확인해 주세요.
          </p>
        )}
        {m.flags?.logout && (
          <p className="notice" role="status">
            로그아웃했습니다.
          </p>
        )}
        {!m.localDemo && m.flags?.invited && (
          <p className="notice" role="status">
            초대를 수락했습니다. 메일에 적힌 회사 코드와 방금 설정한 비밀번호로 로그인해 주세요.
          </p>
        )}
        {m.flags?.reset && (
          <p className="notice" role="status">
            비밀번호를 재설정했습니다. 새 비밀번호로 로그인해 주세요.
          </p>
        )}
        <ErrorNotice message={m.error} />
        <Form action="/login" method="post">
          {!m.localDemo && (
            <Field
              id="companyCode"
              label="회사 코드"
              name="companyCode"
              autoComplete="organization"
              maxLength={80}
              required
            />
          )}
          <Field
            id="email"
            label={m.localDemo ? '사번' : '사번 또는 초대 이메일'}
            name="email"
            type="text"
            autoComplete="username"
            maxLength={254}
            required
          />
          <Field
            id="password"
            label="비밀번호"
            name="password"
            type="password"
            autoComplete="current-password"
            minLength={m.localDemo ? 5 : 12}
            maxLength={256}
            required
            aria-describedby="password-help"
          />
          <p id="password-help" className="hint">
            {m.localDemo
              ? '담당자 사번은 99999입니다. 데모 상단의 사번 변경에서 다른 테스트 사원을 선택할 수 있습니다.'
              : '공백을 포함한 12~128자의 비밀번호를 사용합니다.'}
          </p>
          <button type="submit">로그인</button>
        </Form>
        {!m.localDemo && (
          <>
            <p>
              <a href="/password-reset">비밀번호 재설정</a>
            </p>
            <p className="hint">
              처음 이용하신다면 초대 메일을 열어 비밀번호를 설정해 주세요.
            </p>
          </>
        )}
      </section>
    </main>
  );
}
function Invite({ model: m }: { model: AuthModel }) {
  const action = m.inviteByCompany ? '/invite/accept' : `/invite/${encodeURIComponent(m.companyCode ?? '')}/accept`;
  return (
    <main className="login-shell">
      <section className="panel" aria-labelledby="invite-title">
        <h1 id="invite-title">초대 수락</h1>
        <p className="t-auth-context"><span>WorkLog</span><span>계정 초대</span></p>
        <p>
          회사 코드 <strong>{m.companyCode}</strong>
        </p>
        <p>
          초대를 받은 이메일 주소와 코드를 입력해 주세요. 코드는 발급 후 24시간 동안 유효합니다.
          초대 메일을 여러 번 받았다면 만료 시각이 가장 늦은 메일의 코드를 사용하세요.
        </p>
        <ErrorNotice message={m.error} />
        <Form action={action} method="post">
          {m.inviteByCompany && hidden('companyCode', m.companyCode)}
          <Field
            id="invite-email"
            label="초대 이메일"
            name="email"
            type="email"
            maxLength={254}
            autoComplete="username"
            required
          />
          <Field
            id="invite-token"
            label="초대코드"
            name="token"
            type="password"
            maxLength={43}
            autoComplete="one-time-code"
            required
          />
          <Field
            id="invite-password"
            label="새 비밀번호"
            name="password"
            type="password"
            autoComplete="new-password"
            required
            aria-describedby="password-help"
          />
          <Field
            id="invite-confirmation"
            label="비밀번호 확인"
            name="confirmation"
            type="password"
            autoComplete="new-password"
            required
          />
          <p className="hint" id="password-help">
            공백을 포함해 12~128자로 설정해 주세요.
          </p>
          <button type="submit">초대 수락·비밀번호 설정</button>
        </Form>
        <p>
          <a href="/login">로그인으로</a>
        </p>
        <p className="hint">
          코드가 만료되면 초대를 보낸 담당자에게 재발급을 요청해 주세요.
        </p>
      </section>
    </main>
  );
}
function PasswordReset({ model: m }: { model: AuthModel }) {
  return (
    <main className="login-shell">
      <section className="panel" aria-labelledby="reset-title">
        <h1 id="reset-title">비밀번호 재설정</h1>
        <p className="t-auth-context"><span>WorkLog</span><span>계정 보안</span></p>
        {m.flags?.requested && (
          <p className="notice" role="status">
            일치하는 계정이 있으면 등록된 이메일로 재설정 코드를 보냅니다. 받은메일함과 스팸함을 확인해 주세요.
          </p>
        )}
        <ErrorNotice message={m.error} />
        <h2>코드 요청</h2>
        <Form action="/password-reset/request" method="post">
          <Field
            id="request-company"
            label="회사 코드"
            name="companyCode"
            maxLength={80}
            required
          />
          <Field
            id="request-email"
            label="이메일"
            name="email"
            type="email"
            maxLength={254}
            autoComplete="username"
            required
          />
          <button type="submit">재설정 코드 요청</button>
        </Form>
        <h2>새 비밀번호 설정</h2>
        <p>
          코드는 발급 후 30분 동안 한 번만 사용할 수 있습니다. 재설정 메일을 여러 번 받았다면 만료 시각이 가장 늦은 메일의 코드를 입력하세요.
        </p>
        <Form action="/password-reset/complete" method="post">
          <Field
            id="complete-company"
            label="회사 코드"
            name="companyCode"
            maxLength={80}
            required
          />
          <Field
            id="complete-email"
            label="이메일"
            name="email"
            type="email"
            maxLength={254}
            autoComplete="username"
            required
          />
          <Field
            id="reset-token"
            label="재설정 코드"
            name="token"
            type="password"
            maxLength={43}
            autoComplete="one-time-code"
            required
          />
          <Field
            id="reset-password"
            label="새 비밀번호"
            name="password"
            type="password"
            autoComplete="new-password"
            required
            aria-describedby="password-help"
          />
          <Field
            id="reset-confirmation"
            label="비밀번호 확인"
            name="confirmation"
            type="password"
            autoComplete="new-password"
            required
          />
          <p className="hint" id="password-help">
            공백을 포함해 12~128자로 설정해 주세요.
          </p>
          <button type="submit">비밀번호 재설정</button>
        </Form>
        <p>
          <a href="/login">로그인으로</a>
        </p>
        <p className="hint">
          완료하면 기존 로그인이 종료됩니다. 새 비밀번호로 다시 로그인해 주세요.
        </p>
      </section>
    </main>
  );
}
function OperatorLogin({ model: m }: { model: AuthModel }) {
  return (
    <main className="login-shell">
      <section className="panel">
        <h1>운영자 로그인</h1>
        <p>고객사 직원 계정과 별도인 운영자 전용 경로입니다.</p>
        {m.flags?.error && <p role="alert">운영자 로그인 정보를 확인해 주세요.</p>}
        <ErrorNotice message={m.error} />
        <Form action="/operator/login" method="post">
          <Field
            id="loginId"
            label="운영자 로그인명"
            name="loginId"
            autoComplete="username"
            maxLength={120}
            required
          />
          <Field
            id="password"
            label="비밀번호"
            name="password"
            type="password"
            autoComplete="current-password"
            required
          />
          <button>운영자 로그인</button>
        </Form>
      </section>
    </main>
  );
}
function TenantCard({ tenant: t }: { tenant: Tenant }) {
  const [commands] = useState(() => ({ state: crypto.randomUUID(), reissue: crypto.randomUUID() }));
  return (
    <article>
      <h3>
        {t.companyCode} · {t.displayName}
      </h3>
      <p>{t.active ? '이용 활성' : '이용 중지'}</p>
      <Form action={`/operator/tenants/${t.id}/state`} method="post" className="admin-form">
        {hidden('active', !t.active)}
        {hidden('expectedVersion', t.version)}
        {hidden('commandId', commands.state)}
        <Field
          id={`state-${t.id}`}
          label="이용 상태 변경 사유"
          name="reason"
          maxLength={500}
          required
        />
        <button>{t.active ? '이용 중지' : '이용 활성'}</button>
      </Form>
      {t.active && (
        <Form
          action={`/operator/tenants/${t.id}/initial-invitations`}
          method="post"
          className="admin-form"
        >
          {hidden('expectedVersion', t.version)}
          {hidden('commandId', commands.reissue)}
          <p>
            최초 미수락 계정의 같은 이메일로만 재발급합니다. 수락한 계정이나 다른 관리자를 만들 수
            없습니다.
          </p>
          <Field
            id={`reissue-${t.id}`}
            label="최초 초대 재발급 사유"
            name="reason"
            maxLength={500}
            required
          />
          <button>최초 미수락 초대 재발급</button>
        </Form>
      )}
    </article>
  );
}
function Tenants({ model: m }: { model: TenantsModel }) {
  const f = m.form;
  return (
    <>
      <header className="site-header">
        <a className="brand" href="/operator/tenants">
          WorkLog 운영
        </a>
        <Form action="/operator/logout" method="post">
          <button>운영자 로그아웃</button>
        </Form>
      </header>
      <main className="content admin-shell">
        <h1>고객사·최초 담당자 연결</h1>
        <p>시각 기준: Asia/Seoul (서울)</p>
        <p>
          회사 관계와 연락 경로 확인 후 신규 고객사만 등록합니다. 고객사 근태 열람·대리 승인 기능은
          제공하지 않습니다.
        </p>
        {m.flags?.saved && (
          <p className="notice" role="status">
            요청을 처리했습니다. 초대를 발급했다면 첫 담당자가 등록한 이메일에서 수락할 수 있습니다.
          </p>
        )}
        <ErrorNotice message={m.error} />
        <section className="panel">
          <h2>고객사 이용 상태</h2>
          {m.tenants.map((t) => (
            <TenantCard key={`${t.id}:${t.version}`} tenant={t} />
          ))}
        </section>
        <section className="panel">
          <h2>신규 연결</h2>
          <Form action="/operator/tenants" method="post" className="admin-form">
            {hidden('commandId', f.commandId)}
            <Field
              id="companyCode"
              label="회사 코드"
              name="companyCode"
              value={f.companyCode}
              maxLength={80}
              required
            />
            <Field
              id="companyName"
              label="회사명"
              name="companyName"
              value={f.companyName}
              maxLength={120}
              required
            />
            <Field
              id="employeeNo"
              label="최초 담당자 사번"
              name="employeeNo"
              value={f.employeeNo}
              maxLength={40}
              required
            />
            <Field
              id="employeeName"
              label="최초 담당자 이름"
              name="employeeName"
              value={f.employeeName}
              maxLength={120}
              required
            />
            <Field
              id="joinedOn"
              label="확인한 입사일"
              name="joinedOn"
              type="date"
              value={f.joinedOn}
              required
            />
            <Field
              id="email"
              label="최초 담당자 업무 이메일"
              name="email"
              type="email"
              value={f.email}
              maxLength={254}
              required
            />
            <TextAreaField
              id="relationshipBasis"
              label="회사 관계·연락 경로 검토 근거"
              name="relationshipBasis"
              value={f.relationshipBasis}
              maxLength={500}
              required
            />
            <TextAreaField
              id="reason"
              label="등록 사유"
              name="reason"
              value={f.reason}
              maxLength={500}
              required
            />
            <button>신규 회사·최초 초대 연결</button>
          </Form>
        </section>
        <section className="panel">
          <h2>운영 감사 (최근100개)</h2>
          <ul>
            {m.audit.map((a) => (
              <li key={a.id}>
                {fmtDateTime(a.createdAt)} · {a.action} · {a.reason} · DB {a.dbActor}
              </li>
            ))}
          </ul>
        </section>
      </main>
    </>
  );
}

export function AuthPages({ page, data }: PageProps): React.ReactNode | null {
  const model = data as unknown as AuthModel;
  switch (page) {
    case 'demo':
      return <Demo model={model} />;
    case 'login':
      return <Login model={model} />;
    case 'invite':
      return <Invite model={model} />;
    case 'password-reset':
      return <PasswordReset model={model} />;
    case 'operator/login':
      return <OperatorLogin model={model} />;
    case 'operator/tenants':
      return <Tenants model={data as unknown as TenantsModel} />;
    default:
      return null;
  }
}
