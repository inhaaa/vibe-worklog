// node scripts/verify-employment-leave.cjs <EmploymentLeaveBrowser fixture.json> <pc|mobile> <evidence-directory>
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { leaveBrowser } = require('./verify-leave-browser.cjs');

const [fixtureFile, device, evidence] = process.argv.slice(2);
const { cfg, result, named, days, check, open, tap, fill, post, screenshot, table, metrics, approved, signInForm, login, execute } =
  leaveBrowser(fixtureFile, device, evidence);
const employment = '/admin/leave-balances/employment';
const markers = path.dirname(path.resolve(fixtureFile));
assert.equal(path.resolve(cfg.exitMarker), path.join(markers, 'exit-day'));
assert.equal(path.resolve(cfg.departedMarker), path.join(markers, 'departed'));
assert.equal(cfg.today, '2026-07-15');
assert.equal(cfg.leaveDate, '2026-07-16');
assert.equal(cfg.lastOn, '2026-07-17');
const periods = [
  { start: '2026-04-15', end: '2026-05-14', date: '2026-05-15', decision: 'FULL', label: '개근', quantity: 1, reason: '첫 달 출근 자료와 출근 간주 내역 대조' },
  { start: '2026-05-15', end: '2026-06-14', date: '2026-06-15', decision: 'NOT_FULL', label: '미개근', quantity: 0, reason: '둘째 달 결근 일자와 회사 확인서 대조' },
  { start: '2026-06-15', end: '2026-07-14', date: '2026-07-15', decision: 'FULL', label: '개근', quantity: 1, reason: '셋째 달 근태자료와 휴가 근거 대조' },
];
const settledReason = '마지막 재직일까지 월별 발생과 반차 사용 원장 대조';
const includedReason = '5월 발생분을 이미 사용한 기초 원장의 최초 부여 명세와 대조';
result.monthly = [];
const button = name => named('button', name);

async function selectWorker(admin, person = cfg.worker) {
  await open(admin, '/admin/leave-balances');
  await tap(admin, named('link', '입사·퇴사 연차 정산'));
  await fill(admin, named('searchbox', '사번 또는 이름'), person.employeeNo);
  await tap(admin, button('사원 검색'));
  await admin.waitForFunction(no => new URL(location.href).searchParams.get('q') === no, {}, person.employeeNo);
  await tap(admin, named('link', person.employeeNo + ' ' + person.name + ' 선택'));
  await admin.waitForSelector('table[aria-label="월별 발생 근거"]', { visible: true });
  const url = new URL(admin.url());
  assert.equal(url.searchParams.get('employeeId'), person.employeeId);
  assert.equal(url.searchParams.get('q'), person.employeeNo);
  return url.href;
}

async function capture(page, selector, label) {
  await page.$eval(selector, el => el.scrollIntoView({ block: 'start', behavior: 'instant' }));
  await screenshot(page, label);
}

async function workerLedger(worker, stage, expected, counts) {
  const summary = await metrics(worker);
  assert.equal(days(summary['현재 사용 가능 연차']), expected);
  await tap(worker, named('link', '내 연차 원장'));
  await worker.waitForSelector('#annual-usable', { visible: true });
  assert.equal(days(await worker.$eval('.annual-ledger-balance strong', e => e.textContent)), expected);
  const rows = await worker.$('table[aria-label="연차 변동 이력"]') ? (await table(worker, '연차 변동 이력')).rows : [];
  const entries = rows.map(([label, quantity, year, effectiveOn, leaveDate, reason]) => {
    const kind = label.startsWith('월별 연차 발생') ? '월별 연차 발생' : label;
    return { kind, decision: label.slice(kind.length).trim(), quantity: days(quantity), year, effectiveOn, leaveDate, reason };
  });
  for (const [kind, n] of Object.entries(counts)) assert.equal(entries.filter(row => row.kind === kind).length, n, stage + ' ' + kind);
  assert.equal(entries.length, Object.values(counts).reduce((a, b) => a + b, 0), stage + ' 전체 행');
  result.balances[stage] = { available: expected, requestAvailable: days(summary['현재 사용 가능 연차']), entries };
  return entries;
}

function compareMonths(entries) {
  for (const [i, period] of periods.entries()) {
    const row = entries.find(entry => entry.kind === '월별 연차 발생' && entry.effectiveOn === period.date);
    assert.ok(row, period.date + ' 발생 행');
    assert.equal(row.quantity, period.quantity);
    assert.equal(row.decision, period.label);
    assert.equal(row.year, '2026년');
    assert.equal(row.leaveDate, '해당 없음');
    const counts = result.monthly[i];
    for (const expected of [period.reason, period.start, period.end, period.label, '소정 ' + counts.scheduled + '일',
      '출근 확인 ' + counts.attended + '일', '승인 휴가 ' + counts.leave + '일', '미확인 ' + counts.unresolved + '일'])
      assert.ok(row.reason.includes(expected), period.date + ' 원장 근거: ' + expected);
  }
}

function count(text, label) {
  const match = new RegExp(label + '\\s*(\\d+)일').exec(text);
  assert.ok(match, label + ' 일수');
  return Number(match[1]);
}

async function confirmMonth(admin, i, selected, worker) {
  const p = periods[i], n = i + 1;
  const before = (await table(admin, '월별 발생 근거')).rows[i];
  assert.ok(before[1].includes(p.start) && before[1].includes(p.end));
  assert.equal(before[2], p.date);
  assert.ok(before[4].includes('판단 대기'));
  assert.equal(before[5], '미확정');
  const scheduled = count(before[3], '소정근로'), attended = count(before[3], '확인 출근');
  const leave = count(before[3], '승인 휴가'), unresolved = count(before[3], '미확정');
  const weekdays = [];
  for (const date = new Date(p.start + 'T00:00:00Z'); date.toISOString().slice(0, 10) <= p.end; date.setUTCDate(date.getUTCDate() + 1)) {
    if (![0, 6].includes(date.getUTCDay())) weekdays.push(date.toISOString().slice(0, 10));
  }
  const missing = weekdays.includes('2026-06-01') ? 1 : 0;
  assert.deepEqual([scheduled, attended, leave, unresolved], [weekdays.length, weekdays.length - missing, 0, missing],
    p.date + ' 소정근로·출근·승인휴가·미확정 일수를 가상 회사 일정과 대조');
  await tap(admin, button(n + '개월차 검토'));
  await admin.select(named('combobox', '개근 판단'), p.decision);
  await fill(admin, named('textbox', '월별 판단 사유'), p.reason);
  await tap(admin, button('월별 발생 미리보기'));
  const preview = await admin.waitForSelector(named('region', '월별 판단 미리보기'), { visible: true });
  const text = await preview.evaluate(e => e.textContent);
  for (const value of [p.label, '발생 ' + p.quantity + '일', p.reason]) assert.ok(text.includes(value), value);
  if (i === 0) {
    await capture(admin, '#employment-month-preview', 'monthly-preview');
    await workerLedger(worker, 'preview', 0, {});
  }
  await post(admin, employment + '/monthly', '월별 판단 확정', [200]);
  await admin.waitForFunction(reason => [...document.querySelectorAll('table[aria-label="월별 발생 근거"] tbody tr')]
    .some(row => row.textContent.includes(reason)), {}, p.reason);
  assert.equal(admin.url(), selected, '확정 후 같은 사원·검색어·페이지');
  const after = (await table(admin, '월별 발생 근거')).rows[i];
  assert.equal(after[3], before[3]);
  assert.equal(days(after[5]), p.quantity);
  assert.ok(after[4].includes(p.label) && after[4].includes(p.reason));
  result.monthly.push({ n, ...p, scheduled, attended, leave, unresolved });
}

async function submitHalfDay(worker) {
  const target = '/me/requests/new?' + new URLSearchParams({ kind: 'LEAVE_DAY', mode: 'PAGE', date: cfg.leaveDate, year: '2026' });
  await open(worker, target);
  await worker.waitForSelector('#annual-leave-part', { visible: true });
  await worker.select('#annual-leave-part', 'AM');
  await fill(worker, '#annual-half-date', cfg.leaveDate);
  const usable = 'input[name=bucketId][aria-label*="사용 가능 2일"]';
  const bucket = await worker.waitForSelector(usable, { visible: true });
  if (!await bucket.evaluate(el => el.checked)) await tap(worker, usable);
  await fill(worker, '#annual-reason', '합성 직원의 퇴사 전 오전 반차');
  await tap(worker, button('미리보기'));
  await worker.waitForSelector('.t-request-preview', { visible: true });
  assert.match(await worker.$eval('.t-request-preview', e => e.innerText), /예상 차감 0\.5일/);
  const response = await post(worker, '/me/requests/leave', '신청 제출', [302, 303]);
  const id = new URL(response.headers().location, cfg.baseUrl).searchParams.get('createdId');
  assert.match(id, /^[a-f0-9-]{36}$/);
  await tap(worker, '.t-request-success a[href="/requests/' + id + '"]');
  await worker.waitForSelector('.t-request-detail-status[data-status=PENDING]', { visible: true });
  return id;
}

async function confirmIncludedMonth(admin, comparison) {
  const openingAndUse = { '기초 등록': 1, '휴가 사용': 1 };
  const initial = await workerLedger(comparison, 'importedBefore', 0, openingAndUse);
  const opening = initial.find(row => row.kind === '기초 등록');
  const used = initial.find(row => row.kind === '휴가 사용');
  assert.equal(opening.quantity, 1);
  assert.equal(used.quantity, -1);
  assert.equal(used.leaveDate, '2026-05-18');
  const selected = await selectWorker(admin, cfg.comparison);
  await tap(admin, button('1개월차 검토'));
  await admin.select(named('combobox', '개근 판단'), 'INCLUDED');
  const sourceField = named('combobox', '이미 부여한 원천');
  const sources = await admin.$eval(sourceField, field => [...field.options].filter(option => option.value && !option.disabled)
    .map(option => ({ id: option.value, text: option.textContent.trim() })));
  assert.equal(sources.length, 1);
  const source = sources[0], sourceName = source.text.split(' · 연결 기준 ')[0];
  assert.ok(source.text.includes('연결 기준 1일') && source.text.includes('이미 연결 0일'));
  assert.ok(opening.reason.includes(sourceName), '직원 기초 원장과 담당자가 선택한 원천');
  await admin.select(sourceField, source.id);
  await fill(admin, named('textbox', '월별 판단 사유'), includedReason);
  await tap(admin, button('월별 발생 미리보기'));
  const preview = await admin.waitForSelector(named('region', '월별 판단 미리보기'), { visible: true });
  const text = await preview.evaluate(section => section.textContent);
  for (const value of ['개근 1일', '이번 추가 반영 0일', sourceName, includedReason]) assert.ok(text.includes(value), value);
  await capture(admin, '#employment-month-preview', 'included-preview');
  await post(admin, employment + '/monthly', '월별 판단 확정', [200]);
  await admin.waitForSelector('table[aria-label="월별 발생 근거"] a[href="/admin/leave-balances/buckets/' + source.id + '"]');
  assert.equal(admin.url(), selected);
  const row = (await table(admin, '월별 발생 근거')).rows[0];
  assert.ok(row[4].includes(sourceName));
  assert.equal(row[5], '개근 1일 · 추가 0일');
  const entries = await workerLedger(comparison, 'importedAfter', 0, { ...openingAndUse, '월별 연차 발생': 1 });
  const included = entries.find(entry => entry.kind === '월별 연차 발생');
  assert.equal(included.quantity, 0);
  assert.equal(included.effectiveOn, periods[0].date);
  assert.equal(included.decision, '개근 · 기존 원장에 포함');
  for (const value of [sourceName, includedReason]) assert.ok(included.reason.includes(value), value);
  result.included = { source: sourceName, originalDays: 1, usedDays: 1, additionalDays: included.quantity, decision: included.decision };
  await capture(comparison, '#annual-entries-title', 'included-ledger');
}

async function receipt(page) {
  const section = await page.waitForSelector(named('region', '퇴사 정산 결과'), { visible: true });
  const data = await section.$$eval('dl > div', rows => Object.fromEntries(rows.map(row => [row.querySelector('dt').textContent, row.querySelector('dd').textContent])));
  assert.deepEqual(data, { '마지막 재직일': cfg.lastOn, '확정한 정산 일수': '1.5일', '정산 사유': settledReason });
  return data;
}

async function assertProtected(page) {
  const response = await page.goto(cfg.baseUrl + '/me/leave-ledger', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('main', { visible: true });
  const path = new URL(page.url()).pathname;
  assert.ok(path === '/login' || [401, 403].includes(response.status()), '퇴사 다음 날 본인 연차 원장 접근을 회수해야 합니다.');
  assert.equal(await page.$('#annual-usable, #employment-exit-receipt'), null);
  return { path, http: response.status() };
}

async function run(browser) {
  const bundle = await fetch(cfg.baseUrl + '/react/app.js', { redirect: 'error', signal: AbortSignal.timeout(30000) });
  assert.equal(bundle.status, 200);
  result.bundleSha256 = createHash('sha256').update(Buffer.from(await bundle.arrayBuffer())).digest('hex');
  let admin = await login(browser, cfg.admin), worker = await login(browser, cfg.worker);
  let selected, requestId;
  await check('대상 사원을 검색해 3개월의 산정 근거와 퇴사 전 보류 사유 확인', async () => {
    selected = await selectWorker(admin);
    assert.equal((await table(admin, '월별 발생 근거')).rows.length, 3);
    await tap(admin, button('퇴사 정산 미리보기'));
    const preview = await admin.waitForSelector(named('region', '퇴사 정산 미리보기'), { visible: true });
    const text = await preview.evaluate(el => el.textContent);
    for (const value of ['마지막 재직일', ...periods.map(p => p.date)]) assert.ok(text.includes(value), value);
    assert.equal(await admin.$(button('퇴사 정산 확정')), null);
    await capture(admin, '#employment-month-title', 'monthly-basis');
    await workerLedger(worker, 'opening', 0, {});
  });
  await check('개근·미개근·개근 판단과 1일·0일·1일 반영을 원장에서 대조', async () => {
    for (let i = 0; i < periods.length; i++) await confirmMonth(admin, i, selected, worker);
    compareMonths(await workerLedger(worker, 'earned', 2, { '월별 연차 발생': 3 }));
    await capture(worker, '#annual-entries-title', 'employee-accruals');
  });
  await check('이미 사용한 이관 1일에 개근 월을 연결하고 추가 0일과 원천 대조', async () => {
    const comparison = await login(browser, cfg.comparison);
    await confirmIncludedMonth(admin, comparison);
    compareMonths(await workerLedger(worker, 'afterCoworker', 2, { '월별 연차 발생': 3 }));
    await comparison.browserContext().close();
  });
  await check('오전 반차 신청 대기와 최종 승인 후 1.5일 잔여 확인', async () => {
    requestId = await submitHalfDay(worker);
    compareMonths(await workerLedger(worker, 'pending', 2, { '월별 연차 발생': 3 }));
    const approver = await login(browser, cfg.approver);
    await approved(approver, worker, requestId, '7월 16일 오전 반차 승인', 'half-day-approval');
    const entries = await workerLedger(worker, 'approved', 1.5, { '월별 연차 발생': 3, '휴가 사용': 1 });
    const used = entries.find(e => e.kind === '휴가 사용');
    assert.equal(used.quantity, -0.5);
    assert.equal(used.leaveDate, cfg.leaveDate);
    await capture(worker, '#annual-entries-title', 'employee-used');
    await Promise.all([admin, worker, approver].map(page => page.browserContext().close()));
  });
  await check('마지막 재직일에 묶음별 발생·사용·정산 대상과 직원 잔여 비교', async () => {
    fs.writeFileSync(cfg.exitMarker, '', { flag: 'wx' });
    admin = await login(browser, cfg.admin);
    worker = await login(browser, cfg.worker);
    selected = await selectWorker(admin);
    await tap(admin, button('퇴사 정산 미리보기'));
    const rows = (await table(admin, '묶음별 정산 근거')).rows;
    const eligible = rows.filter(row => row.at(-1) === '정산 대상');
    assert.equal(eligible.length, 1);
    assert.ok(eligible[0][0].includes('월별 연차'), '원천 묶음 근거');
    assert.equal(count(eligible[0][1], '발생·부여'), 2);
    assert.match(eligible[0][2], /사용 0\.5일/);
    assert.deepEqual(eligible[0].slice(3, 5).map(days), [1.5, 1.5]);
    result.exitRows = eligible;
    compareMonths(await workerLedger(worker, 'beforeSettlement', 1.5, { '월별 연차 발생': 3, '휴가 사용': 1 }));
    await capture(admin, '#employment-exit-preview', 'exit-preview');
  });
  await check('퇴사 일수 정산 후 담당자·직원 영수증과 원장 0일 대조', async () => {
    await fill(admin, named('textbox', '퇴사 정산 사유'), settledReason);
    await post(admin, employment + '/settle', '퇴사 정산 확정', [200]);
    result.managerReceipt = await receipt(admin);
    assert.equal(admin.url(), selected);
    await capture(admin, '#employment-exit-receipt', 'manager-receipt');
    const entries = await workerLedger(worker, 'settled', 0, { '월별 연차 발생': 3, '휴가 사용': 1, '퇴사 잔여 정산': 1 });
    compareMonths(entries);
    const exit = entries.find(e => e.kind === '퇴사 잔여 정산');
    assert.equal(exit.quantity, -1.5); assert.equal(exit.effectiveOn, cfg.lastOn); assert.ok(exit.reason.includes(settledReason));
    result.employeeReceipt = await receipt(worker);
    assert.deepEqual(result.managerReceipt, result.employeeReceipt);
    await capture(worker, '#employment-exit-receipt', 'employee-receipt');
    const comparison = await login(browser, cfg.comparison);
    const untouched = await workerLedger(comparison, 'comparison', 0, { '월별 연차 발생': 1, '기초 등록': 1, '휴가 사용': 1 });
    assert.deepEqual(untouched, result.balances.importedAfter.entries);
    assert.equal(await comparison.$('#employment-exit-receipt'), null);
    await comparison.browserContext().close();
  });
  await check('퇴사 다음 날 기존 세션과 새 로그인으로 원장 접근 회수', async () => {
    fs.writeFileSync(cfg.departedMarker, '', { flag: 'wx' });
    result.departed = { existing: await assertProtected(worker) };
    const fresh = await signInForm(browser, cfg.worker);
    const [denied] = await Promise.all([
      fresh.waitForResponse(r => new URL(r.url()).pathname === '/login' && r.request().method() === 'POST'),
      tap(fresh, 'form[action="/login"] button'),
    ]);
    assert.equal(denied.status(), 401, '퇴사 다음 날 자격 증명 확인');
    await fresh.waitForFunction(() => document.body.innerText.includes('로그인 정보를 확인해 주세요.'));
    result.departed.signInHttp = denied.status();
    result.departed.newLogin = await assertProtected(fresh);
  });
}

execute(run).catch(error => { console.error(error.stack); process.exitCode = 1; });
