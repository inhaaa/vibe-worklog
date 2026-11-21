// Run against a fresh AttendanceMonthCycleBrowser instance for each viewport.
// node scripts/verify-attendance-month-cycle.cjs <fixture.json> <pc|mobile> <output-directory>
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');
const puppeteer = require(process.env.WORKLOG_PUPPETEER_MODULE || 'puppeteer-core');

const [fixtureFile, device, outputDirectory] = process.argv.slice(2);
assert.ok(fixtureFile && ['pc', 'mobile'].includes(device) && outputDirectory, 'fixture.json pc|mobile output-directory');
const cfg = JSON.parse(fs.readFileSync(fixtureFile, 'utf8'));
assert.equal(new URL(cfg.baseUrl).origin, cfg.baseUrl);
assert.equal(new URL(cfg.baseUrl).hostname, '127.0.0.1');
assert.equal(path.resolve(cfg.advanceFile), path.join(path.dirname(path.resolve(fixtureFile)), 'next-month'));
assert.equal(cfg.correctionEmployee.employeeId, cfg.leaveEmployee.employeeId);
const output = path.resolve(outputDirectory);
fs.mkdirSync(output, { recursive: true });
const timeout = 30000;
const views = {
  pc: { width: 1440, height: 900, isMobile: false, hasTouch: false },
  mobile: { width: 390, height: 844, isMobile: true, hasTouch: true },
};
const closeRoute = '/company/attendance?view=month-end&month=' + cfg.month;
const closePanel = 'section[aria-labelledby="month-close-title"]';
const exportPanel = 'section[aria-label="마감 결과 Excel"]';
const daily = 'section[aria-label="일별 근로시간"]';
const observations = { device, viewport: views[device], month: cfg.month, stages: [], inspections: {}, downloads: {} };
const runtimeErrors = [];
let stage = '';

function writeJson(name, data) {
  const target = path.join(output, name);
  const pending = target + '.' + process.pid + '.tmp';
  fs.writeFileSync(pending, JSON.stringify(data, null, 2) + '\n');
  fs.renameSync(pending, target);
}
async function check(name, fn) {
  stage = name;
  console.log(device, name);
  await fn();
  observations.stages.push(name);
}
async function open(page, route) {
  const url = new URL(route, cfg.baseUrl);
  assert.equal(url.origin, cfg.baseUrl);
  await page.bringToFront();
  const response = await page.goto(url.href, { waitUntil: 'domcontentloaded' });
  assert.equal(response.status(), 200, route);
  await page.waitForSelector('main', { visible: true });
}
async function contains(page, selector, needle) {
  await page.waitForFunction((s, text) => [...document.querySelectorAll(s)].some(
    element => element.checkVisibility() && element.innerText.includes(text)), {}, selector, needle)
    .catch(() => { throw new Error(new URL(page.url()).pathname + ' · 표시 확인 ' + needle); });
}
async function click(page, selector, label) {
  await page.bringToFront();
  const handle = await page.waitForFunction((s, text) => [...document.querySelectorAll(s)].find(
    element => element.checkVisibility() && !element.disabled && (text == null || element.innerText.trim() === text)),
    {}, selector, label ?? null).catch(() => { throw new Error('선택할 항목 ' + (label ?? selector)); });
  const element = handle.asElement();
  await element.evaluate(async target => {
    target.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
  // An inline link can wrap or sit underneath the sticky header. Tap a visible part of the control.
  const hit = await page.waitForFunction(target => {
    for (const rect of target.getClientRects()) {
      const left = Math.max(1, rect.left), right = Math.min(innerWidth - 1, rect.right);
      const top = Math.max(1, rect.top), bottom = Math.min(innerHeight - 1, rect.bottom);
      if (right <= left || bottom <= top) continue;
      for (const y of [(top + bottom) / 2, top + 1, bottom - 1]) {
        for (const x of [(left + right) / 2, left + 1, right - 1]) {
          const point = { x: Math.round(x), y: Math.round(y) };
          if (target.contains(document.elementFromPoint(point.x, point.y))) return point;
        }
      }
    }
    return false;
  }, {}, element);
  const { x, y } = await hit.jsonValue();
  if (page.viewport().hasTouch) await page.touchscreen.tap(x, y);
  else await page.mouse.click(x, y);
}
async function fill(page, selector, value) {
  const field = await page.waitForSelector(selector, { visible: true });
  await field.evaluate((element, text) => {
    const proto = element instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(element, text);
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
  }, value);
}
async function posted(page, route, selector, label, statuses = [302, 303]) {
  const [response] = await Promise.all([
    page.waitForResponse(r => new URL(r.url()).pathname === route && r.request().method() === 'POST')
      .catch(() => { throw new Error('제출 응답 대기 ' + route); }),
    click(page, selector, label),
  ]);
  assert.ok(statuses.includes(response.status()), route + ' HTTP ' + response.status());
  if (!response.headers().location) return;
  const to = new URL(response.headers().location, cfg.baseUrl);
  assert.equal(to.origin, cfg.baseUrl);
  await page.waitForFunction((p, q) => location.pathname === p && location.search === q, {}, to.pathname, to.search);
  return to;
}
async function screen(page, name) {
  const box = await page.evaluate(() => [innerWidth, document.documentElement.scrollWidth,
    document.documentElement.clientWidth, navigator.maxTouchPoints]);
  assert.equal(box[0], page.viewport().width, name);
  assert.ok(box[1] <= box[2] + 1, name + ' 가로 넘침 ' + box);
  assert.equal(box[3] > 0, page.viewport().hasTouch, name);
  await page.screenshot({ path: path.join(output, device + '-' + name + '.png') });
}
async function login(browser, user, viewport = device) {
  const context = await browser.createBrowserContext();
  const page = await context.newPage();
  page.setDefaultTimeout(timeout);
  await page.setViewport(views[viewport]);
  page.on('pageerror', error => runtimeErrors.push(error.message));
  await open(page, '/login');
  await fill(page, 'input[name=companyCode]', cfg.companyCode);
  await fill(page, 'input[name=email]', user.email);
  await fill(page, 'input[name=password]', cfg.password);
  await posted(page, '/login', 'form[action="/login"] button', '로그인');
  await contains(page, 'main', '오늘 근무');
  return page;
}
function rowSelector(user, date) {
  return '.month-review-list li[aria-label="' + date + ' ' + user.employeeNo + ' ' + user.name + '"]';
}
async function inspect(page, label) {
  await open(page, closeRoute);
  await click(page, closePanel + ' button', '회사 전체 마감 점검');
  await contains(page, '.month-close-counts', '점검 사원');
  const counts = await page.$$eval('.month-close-counts dt', terms =>
    Object.fromEntries(terms.map(term => [term.innerText, Number.parseInt(term.nextElementSibling.innerText, 10)])));
  assert.equal(counts['점검 사원'], cfg.expected.employees.length);
  observations.inspections[label] = counts;
  return counts;
}
async function requestCorrection(admin, worker) {
  const reason = '실제 퇴근 시각과 휴게 확인 요청';
  await open(admin, closeRoute);
  const row = rowSelector(cfg.correctionEmployee, cfg.correctionDate);
  await click(admin, row + ' button', '보완 요청');
  const form = 'form[aria-label="퇴근 누락 보완 요청"]';
  await fill(admin, form + ' [name=reason]', reason);
  await posted(admin, '/admin/attendance/follow-ups', form + ' button', '요청 보내기', [200, 201]);
  await contains(admin, row, '직원 확인 대기');
  await open(worker, '/me/attendance/follow-ups');
  await contains(worker, '.checkout-follow-up-list li', reason);
  await click(worker, '.checkout-follow-up-list a', '정정 신청');
  await contains(worker, '.t-correction-source', '미기록');
  await contains(worker, '.t-correction-source', '09:00');
  const edit = '.t-correction-form';
  await fill(worker, edit + ' input[type=datetime-local]', cfg.correctionDate + 'T17:30:00');
  await fill(worker, edit + ' [name=reason]', '퇴근 17시 30분, 실제 휴게 12시부터 13시까지 확인');
  for (const name of ['presenceAsWork', 'breaksConfirmed']) {
    const field = edit + ' input[name="' + name + '"][type=checkbox]';
    if (!await worker.$eval(field, element => element.checked)) await click(worker, field);
    await worker.waitForFunction(s => document.querySelector(s).checked, {}, field);
  }
  if (!await worker.$('[aria-label="휴게 1 시작"]')) await click(worker, edit + ' button', '휴게 추가');
  await fill(worker, '[aria-label="휴게 1 시작"]', cfg.correctionDate + 'T12:00:00');
  await fill(worker, '[aria-label="휴게 1 종료"]', cfg.correctionDate + 'T13:00:00');
  const to = await posted(worker, '/me/requests/correction', edit + ' button', '정정 심사 요청');
  const id = to.pathname.split('/').pop();
  await worker.waitForSelector('.t-request-detail-status[data-status=PENDING]', { visible: true });
  assert.equal(await worker.$('form[action$="/approve"]'), null, '직원의 자기 승인 차단');
  const records = await worker.$$eval('.t-correction-comparison tbody tr', rows => Object.fromEntries(
    rows.map(row => [row.cells[0].innerText, Array.from(row.cells).slice(1).map(cell => cell.innerText)])));
  assert.deepEqual(records['원천 기록'], [cfg.correctionDate + ' 09:00:00', '미기록']);
  assert.deepEqual(records['제안 기록'], [cfg.correctionDate + ' 09:00:00', cfg.correctionDate + ' 17:30:00']);
  observations.correction = records;
  return id;
}
async function requestLeave(worker) {
  const route = '/me/requests/new?' + new URLSearchParams({ kind: 'LEAVE_DAY', mode: 'PAGE',
    date: cfg.leaveDate, year: cfg.month.slice(0, 4) });
  await open(worker, route);
  const choice = await worker.waitForFunction(name => [...(document.querySelector('#request-leave-type')?.options ?? [])]
    .find(option => option.text.includes(name))?.value, {}, cfg.leaveName);
  await worker.select('#request-leave-type', await choice.jsonValue());
  await fill(worker, '#annual-reason', '회사 유급 경조휴가 종일 사용');
  await click(worker, '.wl-compose-actions button', '미리보기');
  for (const text of [cfg.leaveName, '휴가 사용 1일', '예상 연차 차감 0일']) await contains(worker, '.t-request-preview', text);
  await contains(worker, '.wl-compose-context', cfg.admin.name);
  const to = await posted(worker, '/me/requests/leave', '.wl-compose-actions button', '신청 제출');
  const id = to.searchParams.get('createdId');
  assert.match(id, /^[\da-f-]{36}$/);
  return id;
}
async function approve(admin, worker, id, kind) {
  const target = '/requests/' + id;
  await open(admin, '/approvals');
  await click(admin, 'main a[href="' + target + '"]');
  await contains(admin, '.t-request-detail-summary', cfg.correctionEmployee.name);
  await contains(admin, '.t-request-detail-summary', kind);
  const action = target + '/approve';
  const form = 'form[action="' + action + '"]';
  await fill(admin, form + ' [name=reason]', kind + ' 사실과 신청 근거 확인');
  await posted(admin, action, form + ' button', '승인하기');
  await open(worker, target);
  await worker.waitForSelector('.t-request-detail-status[data-status=APPROVED]', { visible: true });
  await contains(worker, '.t-request-detail-page', cfg.admin.name);
}
function clockMinutes(text) {
  const match = /^(\d+):([0-5]\d):([0-5]\d)$/.exec(text);
  assert.ok(match, '확정된 화면 시간 필요 ' + JSON.stringify(text));
  return Number(match[1]) * 60 + Number(match[2]) + Number(match[3]) / 60;
}
function dayCount(text) {
  assert.match(text, /^\d+(\.\d+)?일$/);
  return Number(text.slice(0, -1));
}
async function readEmployee(page, employee) {
  await open(page, '/me/worktime?month=' + cfg.month);
  assert.equal(await page.$eval('input[name=month]', element => element.value), cfg.month);
  const summary = await page.$$eval('dl[aria-label="월 요약"] dt', terms =>
    Object.fromEntries(terms.map(term => [term.innerText, term.nextElementSibling.innerText])));
  const days = [];
  for (const expected of employee.days) {
    const row = daily + ' [row-id="' + expected.date + '"]';
    await contains(page, row + ' [col-id=workDate]', expected.date);
    const value = async column => {
      const cell = await page.waitForSelector(row + ' [col-id=' + column + ']');
      return cell.evaluate(el => el.innerText.trim());
    };
    const leave = await value('approvedLeaveDays');
    assert.match(leave, /^(?:-|\d+(?:\.\d+)?)$/);
    days.push({ date: expected.date, actualMinutes: clockMinutes(await value('actualWorkMillis')),
      paidLeaveMinutes: clockMinutes(await value('paidLeaveMillis')), leaveDays: leave === '-' ? 0 : Number(leave) });
  }
  const result = { employeeNo: employee.employeeNo, actualMinutes: clockMinutes(summary['실근로 합계']),
    paidLeaveMinutes: clockMinutes(summary['유급휴가 인정시간']), leaveDays: dayCount(summary['휴가']),
    pendingDays: dayCount(summary['확인 전']), days };
  assert.equal(result.pendingDays, 0);
  for (const field of ['actualMinutes', 'paidLeaveMinutes', 'leaveDays']) {
    assert.equal(result[field], days.reduce((sum, day) => sum + day[field], 0), employee.employeeNo + ' ' + field);
    for (const [index, expected] of employee.days.entries()) {
      assert.equal(days[index][field], expected[field], employee.employeeNo + ' ' + expected.date + ' ' + field);
    }
  }
  return result;
}
async function recalculate(page) {
  await open(page, closeRoute);
  const row = rowSelector(cfg.correctionEmployee, cfg.correctionDate);
  await contains(page, row, '재집계');
  await click(page, '.month-review-selection label');
  await click(page, '.month-review-selection button', '선택 재집계');
  await contains(page, '[aria-label="재집계 미리보기"]', cfg.correctionDate);
  await fill(page, '#month-end-calculation-reason', '승인된 정정·휴가와 대조 사원 근로 재집계');
  await click(page, '[aria-label="재집계 미리보기"] button', '재집계 실행');
  const receipt = '[aria-label="근무일별 재집계 결과"]';
  await contains(page, receipt, cfg.correctionDate);
  const summary = await page.$eval('[aria-label="재집계 결과"] > p', el => el.innerText);
  assert.match(summary, /해소 [1-9]\d*건 · 확인 필요 0건/);
  observations.recalculation = { summary, days: await page.$$eval(receipt + ' li', rows => rows.map(el => el.innerText)) };
  await screen(page, 'recalculation');
  await click(page, '[aria-label="재집계 결과"] button', '결과 확인');
  await contains(page, '.month-review', '월말 확인 대상이 없습니다');
}
async function download(page, scope, folder, tag) {
  const input = exportPanel + ' select[name=scope]';
  const id = scope?.id ?? 'COMPANY', key = scope?.code ?? 'company';
  await page.waitForSelector(input, { visible: true });
  assert.deepEqual(await page.select(input, id), [id]);
  const received = fs.mkdtempSync(path.join(folder, tag + '-' + key + '-download-'));
  const cdp = await page.createCDPSession();
  try {
    await cdp.send('Browser.setDownloadBehavior', { behavior: 'allow', browserContextId: page.browserContext().id, downloadPath: received });
    await click(page, exportPanel + ' button', 'Excel 다운로드');
    await contains(page, exportPanel, '다운로드를 시작했습니다');
    const deadline = Date.now() + timeout;
    let file;
    while (Date.now() < deadline && !file) {
      file = fs.readdirSync(received).find(name => name.endsWith('.xlsx'));
      if (!file) await delay(100);
    }
    assert.ok(file, key + ' 브라우저 다운로드 파일');
    assert.equal(file, 'worklog-close-' + cfg.month + '-v' + cfg.expected.version + '-' +
      (scope ? 'department-' + scope.id : 'company') + '.xlsx');
    const bytes = fs.readFileSync(path.join(received, file));
    assert.equal(bytes.subarray(0, 2).toString(), 'PK');
    fs.copyFileSync(path.join(received, file), path.join(folder, tag + '-' + key + '.xlsx'));
    return { file, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
  } finally { await cdp.detach(); }
}
async function downloadAll(page, folder, tag) {
  const results = {};
  for (const scope of [null, ...cfg.departments]) results[scope?.code ?? 'company'] = await download(page, scope, folder, tag);
  return results;
}
async function run(browser) {
  let admin = await login(browser, cfg.admin);
  let worker = await login(browser, cfg.correctionEmployee);
  let correction, leave;
  await check('담당자 보완 요청과 직원의 퇴근·실제 휴게 정정 신청', async () => {
    correction = await requestCorrection(admin, worker);
  });
  await check('같은 직원의 회사 유급휴가 신청', async () => {
    leave = await requestLeave(worker);
    assert.notEqual(leave, correction);
    await open(worker, '/requests/' + leave);
    await worker.waitForSelector('.t-request-detail-status[data-status=PENDING]', { visible: true });
    assert.equal(await worker.$('form[action$="/approve"]'), null);
    await screen(worker, 'pending-leave');
  });
  await check('다음 달 미처리 점검과 마감 차단', async () => {
    fs.writeFileSync(cfg.advanceFile, 'next month\n', { flag: 'wx' });
    await admin.browserContext().close();
    await worker.browserContext().close();
    admin = await login(browser, cfg.admin);
    worker = await login(browser, cfg.correctionEmployee);
    const counts = await inspect(admin, 'pending');
    assert.equal(counts['결재 대기 신청'], 2);
    assert.equal(counts['진행 중 보완 요청'], 1);
    assert.equal(counts['정정 결재 대기'], 1);
    assert.equal(await admin.$('#month-close-reason'), null, '미처리 상태 마감 차단');
    await screen(admin, 'blocked');
    await click(admin, 'a[aria-label="결재 대기 신청 2건 목록 보기"]');
    await contains(admin, '.month-review', '현재 2건');
    const entries = await admin.$$eval('.month-review-list li a', links => links.map(link => new URL(link.href).pathname));
    assert.deepEqual(entries.sort(), ['/requests/' + correction, '/requests/' + leave].sort());
  });
  await check('결재함의 정정·회사 휴가 승인과 재집계 전 효력', async () => {
    await approve(admin, worker, correction, '정정');
    const partial = await inspect(admin, 'correctionApproved');
    assert.equal(partial['결재 대기 신청'], 1);
    assert.equal(partial['정정 결재 대기'], 0);
    assert.equal(partial['진행 중 보완 요청'], 1);
    await approve(admin, worker, leave, cfg.leaveName);
    await contains(worker, '.t-request-detail-summary', '연차 차감 없음');
    const approved = await inspect(admin, 'approved');
    assert.equal(approved['결재 대기 신청'], 0);
    assert.equal(approved['진행 중 보완 요청'], 1);
    assert.ok(approved['확인할 근무일'] > 0);
    assert.equal(await admin.$('#month-close-reason'), null);
    await open(worker, '/me/attendance/follow-ups');
    await contains(worker, '.checkout-follow-up-state', '재집계 대기');
    await open(worker, '/me/worktime?month=' + cfg.month);
    const live = await worker.$$eval('dl[aria-label="월 요약"] dt', terms =>
      Object.fromEntries(terms.map(term => [term.innerText, term.nextElementSibling.innerText])));
    assert.equal(live['유급휴가 인정시간'], '8:00:00');
    assert.equal(live['휴가'], '1일');
  });
  await check('선택 재집계와 보완 해소', async () => {
    await recalculate(admin);
    const cleared = await inspect(admin, 'cleared');
    for (const [label, count] of Object.entries(cleared)) if (label !== '점검 사원') assert.equal(count, 0, label);
    await contains(admin, '.month-close-check', '마감할 수 있습니다');
    await open(worker, '/me/attendance/follow-ups');
    await contains(worker, 'main', '처리할 근태 보완 요청이 없습니다');
  });
  await check('직원의 재집계 결과 확인과 월 마감', async () => {
    const employee = cfg.expected.employees.find(person => person.employeeNo === cfg.correctionEmployee.employeeNo);
    observations.beforeCloseEmployee = await readEmployee(worker, employee);
    await inspect(admin, 'beforeClose');
    await fill(admin, '#month-close-reason', '퇴근 정정과 회사 휴가, 실근로 대조 후 월 마감');
    await click(admin, closePanel + ' button', '월 마감 확정');
    await contains(admin, '.month-close-status.closed', '마감 완료');
    await screen(admin, 'closed');
  });
  await check('마감 후 각 직원의 본인 월 요약과 일별 근로시간', async () => {
    const people = [cfg.correctionEmployee, cfg.comparisonEmployee, cfg.admin];
    const records = [];
    for (const employee of cfg.expected.employees) {
      const person = people.find(user => user.employeeNo === employee.employeeNo);
      assert.ok(person, employee.employeeNo + ' 본인 계정');
      const page = person === cfg.admin ? admin : person === cfg.correctionEmployee ? worker : await login(browser, person);
      records.push(await readEmployee(page, employee));
      await screen(page, 'employee-' + person.employeeNo);
    }
    assert.deepEqual(records.find(person => person.employeeNo === cfg.correctionEmployee.employeeNo),
      observations.beforeCloseEmployee, '신청자의 마감 전과 후 일별·월별 결과');
    writeJson(device + '-screen.json', records);
    const evidence = { month: cfg.month };
    for (const name of ['pc', 'mobile']) {
      const file = path.join(output, name + '-screen.json');
      if (fs.existsSync(file)) evidence[name] = JSON.parse(fs.readFileSync(file, 'utf8'));
    }
    if (evidence.pc && evidence.mobile) writeJson('employee-screen.json', evidence);
  });
  await check('두 화면 크기의 회사·부서 Excel 실제 다운로드', async () => {
    await open(admin, closeRoute);
    await contains(admin, '.month-close-status.closed', '마감 완료');
    observations.downloads.primary = await downloadAll(admin, output, device);
    const other = device === 'pc' ? 'mobile' : 'pc';
    const companion = await login(browser, cfg.admin, other);
    await open(companion, closeRoute);
    await contains(companion, '.month-close-status.closed', '마감 완료');
    const folder = path.join(output, device + '-same-close');
    fs.mkdirSync(folder, { recursive: true });
    observations.downloads.sameClose = await downloadAll(companion, folder, other);
    assert.deepEqual(observations.downloads.sameClose, observations.downloads.primary,
      '같은 회사·월·마감 버전의 PC와 모바일 실제 다운로드');
  });
  assert.deepEqual(runtimeErrors, [], '브라우저 런타임 오류');
  observations.chrome = await browser.version();
  writeJson(device + '-result.json', observations);
}

(async () => {
  const configured = process.env.WORKLOG_CHROME;
  const browser = await puppeteer.launch({ executablePath: configured,
    channel: configured ? undefined : 'chrome', headless: true });
  try { await run(browser); }
  catch (error) {
    console.error(device + ' / ' + stage + ' / ' + error.message);
    for (const [index, page] of (await browser.pages()).entries()) {
      if (!page.url().startsWith(cfg.baseUrl + '/') || new URL(page.url()).pathname === '/login') continue;
      const text = await page.$eval('main', el => el.innerText).catch(() => '');
      fs.writeFileSync(path.join(output, device + '-error-' + index + '.txt'), text);
    }
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error.stack); process.exitCode = 1; });
