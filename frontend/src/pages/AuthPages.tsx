import React, { useState } from 'react';
import type { PageProps } from '../types';
import { Form, Field, TextAreaField, ErrorNotice, fmtDateTime } from '../ui';

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
  demoLogin?: string;
  demoPassword?: string;
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
  return (
    <section className="t-intro" aria-labelledby="demo-title">
      <header className="t-intro-nav">
        <div className="t-wordmark">
          <span className="t-logo" aria-hidden="true">
            W
          </span>
          <span>
            WorkLog<span className="t-brand-sub">근태관리 워크스페이스</span>
          </span>
        </div>
        <button className="t-btn secondary" type="submit" form="demo-login-form">
          데모 살펴보기 <span aria-hidden="true">↗</span>
        </button>
      </header>
      <div className="t-hero">
        <div className="t-hero-copy">
          <p className="t-intro-label">
            <span aria-hidden="true" />
            일하는 하루를 더 명확하게
          </p>
          <h1 id="demo-title">
            근무는 한눈에.
            <br />
            관리는 <em>간결하게.</em>
          </h1>
          <p className="t-hero-description">
            출퇴근과 휴가, 근무 일정과 직원 관리.
            <br />
            매일의 업무를 하나의 흐름으로 정리하세요.
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
          <Form id="demo-login-form" action="/login" method="post">
            {hidden('email', m.demoLogin)}
            {hidden('password', m.demoPassword)}
            <button className="t-btn primary t-enter" type="submit">
              데모로 입장 <span aria-hidden="true">→</span>
            </button>
          </Form>
          <p className="t-enter-note">
            {m.publicDemo
              ? '모든 방문자가 함께 사용하는 가상 데이터입니다. 실제 개인정보를 입력하지 마세요. 체험 자료는 초기화될 수 있습니다.'
              : '로그인 입력 없이 로컬 데모 계정으로 입장해요.'}
          </p>
          <div className="t-feature-labels">
            <span>출퇴근 기록</span>
            <span>근무 일정</span>
            <span>휴가·직원 관리</span>
          </div>
        </div>
        <div
          className="t-hero-art"
          role="img"
          aria-label="기록, 일정, 신청을 모아 보여주는 화면 구성 예시"
        >
          <div className="t-mini-window" aria-hidden="true">
            <div className="t-mini-head">
              <span className="t-mini-logo">WorkLog</span>
              <span>화면 구성 예시</span>
              <span className="t-mini-avatar">W</span>
            </div>
            <div className="t-mini-body">
              <p className="t-mini-kicker">MY WORKSPACE</p>
              <h2>오늘의 흐름을 한눈에.</h2>
              <div className="t-mini-summary">
                <div>
                  <span>하루의 시작과 끝</span>
                  <strong>출퇴근</strong>
                  <span className="t-mini-status">기록과 확인 상태를 구분</span>
                </div>
                <div>
                  <span>나의 휴가와 신청</span>
                  <strong>함께 확인</strong>
                  <span>필요한 정보를 모아서</span>
                </div>
              </div>
              <div className="t-mini-weekhead">
                <strong>이번 주 일정</strong>
                <span>월요일부터 금요일까지</span>
              </div>
              <div className="t-mini-week">
                <div className="today">
                  <span>월</span>
                  <b>일정</b>
                  <i />
                </div>
                <div>
                  <span>화</span>
                  <b>일정</b>
                  <i />
                </div>
                <div className="leave">
                  <span>수</span>
                  <b>휴가</b>
                  <small>상태 확인</small>
                </div>
                <div>
                  <span>목</span>
                  <b>일정</b>
                  <i />
                </div>
                <div>
                  <span>금</span>
                  <b>일정</b>
                  <i />
                </div>
              </div>
              <div className="t-mini-footer">
                <span className="t-mini-check">✓</span>
                <div>
                  <strong>신청과 기록을 한곳에서</strong>
                  <span>내 업무에 필요한 정보를 확인하세요</span>
                </div>
                <span className="t-mini-arrow">↗</span>
              </div>
            </div>
          </div>
          <div className="t-floating-note">
            <span className="t-note-icon" aria-hidden="true">
              ✓
            </span>
            <div>
              <strong>일정과 기록을 한곳에서</strong>
              <span>내 업무에 집중하는 작은 변화</span>
            </div>
          </div>
        </div>
      </div>
      <footer className="t-intro-footer">
        <span>WorkLog · 일하는 하루의 기준</span>
        <span>{m.publicDemo ? '가상 자료로 체험하는 공개 데모' : '합성 자료로 둘러보는 로컬 데모'}</span>
      </footer>
    </section>
  );
}
function Login({ model: m }: { model: AuthModel }) {
  return (
    <main className="login-shell">
      <section className="panel" aria-labelledby="login-title">
        <p className="eyebrow">WorkLog · 로컬 개발 환경</p>
        <h1 id="login-title">로그인</h1>
        <p>
          {m.localDemo
            ? '아이디와 비밀번호로 로그인해 주세요.'
            : '소속 회사 코드와 계정으로 로그인해 주세요.'}
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
            초대를 수락했습니다. 회사 코드와 이메일로 로그인해 주세요.
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
            label={m.localDemo ? '아이디' : '이메일'}
            name="email"
            type={m.localDemo ? 'text' : 'email'}
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
              ? '로컬 데모 계정은 admin / admin입니다.'
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
              초대 수락 후 로그인할 수 있습니다. 개발 단계는 로컬 모의 전달만 사용합니다.
            </p>
          </>
        )}
      </section>
    </main>
  );
}
function Invite({ model: m }: { model: AuthModel }) {
  return (
    <main className="login-shell">
      <section className="panel" aria-labelledby="invite-title">
        <p className="eyebrow">WorkLog · 계정 초대</p>
        <h1 id="invite-title">초대 수락</h1>
        <p>
          회사 코드: <span>{m.companyCode}</span>
        </p>
        <p>
          전달받은 이메일과 초대코드를 입력하고 비밀번호를 설정하세요. 초대는24시간 유효하며
          재발급한 이전 코드는 사용할 수 없습니다.
        </p>
        <ErrorNotice message={m.error} />
        <Form action={`/invite/${encodeURIComponent(m.companyCode ?? '')}/accept`} method="post">
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
            autoComplete="off"
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
            공백을 포함한12~128자입니다. 오류 후 비밀번호와 초대코드는 다시 입력해 주세요.
          </p>
          <button type="submit">초대 수락·비밀번호 설정</button>
        </Form>
        <p>
          <a href="/login">로그인으로</a>
        </p>
        <p className="hint">
          현재는 로컬 모의 초대 검증 단계입니다. 실제 메일 발송·이용약관 동의·파일럿 개방은 별도
          검토합니다.
        </p>
      </section>
    </main>
  );
}
function PasswordReset({ model: m }: { model: AuthModel }) {
  return (
    <main className="login-shell">
      <section className="panel" aria-labelledby="reset-title">
        <p className="eyebrow">WorkLog · 계정 보안</p>
        <h1 id="reset-title">비밀번호 재설정</h1>
        {m.flags?.requested && (
          <p className="notice" role="status">
            일치하는 계정이 있으면 재설정 코드를 전달합니다. 현재는 로컬 모의 전달만 사용합니다.
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
          코드는30분 유효하며 한 번만 사용할 수 있습니다. 재발급하면 이전 코드는 사용할 수 없습니다.
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
            autoComplete="off"
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
            공백을 포함한12~128자입니다. 오류 후 비밀번호와 코드는 다시 입력해 주세요.
          </p>
          <button type="submit">비밀번호 재설정</button>
        </Form>
        <p>
          <a href="/login">로그인으로</a>
        </p>
        <p className="hint">
          완료하면 기존 로그인이 종료됩니다. 실제 메일 발송은 제공하지 않는 로컬 검증 단계입니다.
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
          <p className="notice">
            처리했습니다. 신규 초대는 로컬 모의 전달이며 실제 메일을 발송하지 않습니다.
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
