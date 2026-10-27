import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Check, CsrfContext, Field, Form, fmtDate, fmtTime, fmtDateTime, duration } from './ui';

describe('공통 폼과 표시 계약', () => {
  it('서버 CSRF 이름과 토큰을 POST 폼에 넣고 사용자 입력은 이스케이프한다', () => {
    const html = renderToStaticMarkup(
      <CsrfContext
        value={{ token: 'csrf-token', parameterName: '_csrf', headerName: 'X-CSRF-TOKEN' }}
      >
        <Form action="/admin/employees">
          <Field label="이름" name="displayName" value={'<script>alert(1)</script>'} />
        </Form>
      </CsrfContext>,
    );
    expect(html).toContain('name="_csrf" value="csrf-token"');
    expect(html).toContain('&lt;script&gt;');
    expect(html).not.toContain('<script>');
  });
  it('체크 해제와 복수 역할 값을 Spring 폼 계약으로 전달한다', () => {
    const html = renderToStaticMarkup(
      <Check name="roles" label="관리자" value="ATTENDANCE_ADMIN" checked />,
    );
    expect(html).toContain('name="_roles" value="on"');
    expect(html).toContain('value="ATTENDANCE_ADMIN"');
  });
  it('업무 날짜는 시간대 변환 없이 표시하고 시각은 서울 기준이다', () => {
    expect(fmtDate('2026-01-01')).toBe('2026-01-01');
    expect(fmtTime('09:00:30.125')).toBe('09:00:30.125');
    expect(fmtDateTime('2026-01-01T23:30:00Z')).toContain('2026');
    expect(fmtDateTime('2026-01-01T23:30:00Z')).toContain('08:30');
    expect(duration(null)).toBe('미확정');
    expect(duration(3_600_000)).toBe('1:00:00.000');
  });
});
