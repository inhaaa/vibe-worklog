# ADR-0006: Pretendard 글꼴과 CSS 디자인 층

- 결정일: 2026-11-10 `Asia/Seoul`
- 상태: 사용자 승인. 1단계 로컬 구현·검증 대상.

## 결정

1. UI 글꼴로 Pretendard 1.3.9를 쓴다. 동적 분할 woff2를 앱 빌드에 포함해 같은 출처에서 제공한다.
2. 기존 CSS 전체를 `@layer legacy`, AG Grid legacy CSS를 `@layer vendor`에 둔다. `frontend/src/design/`의 새 CSS는 층 없이 둔다.

## 근거

- 현재 `Noto Sans KR`은 글꼴 파일 없이 이름만 지정돼 있다. 그래서 Windows에서는 맑은 고딕으로 표시되고, 사용자가 낡아 보인다고 지적한 주요 원인이 된다.
- Pretendard는 한국어 UI용으로 숫자 폭 고정(`tnum`)과 가변 굵기를 지원한다. SIL OFL 1.1이라 상업 서비스에 포함해 배포할 수 있다.
- 2026-11-10 npm registry에서 `pretendard` 1.3.9의 `license: OFL-1.1`을 확인했다. jsDelivr 파일 목록에서는 `dist/web/variable/pretendardvariable-dynamic-subset.css`와 woff2 92개(합계 2,957,724 bytes)를 확인했다.
- 현재 CSP는 `default-src 'self'; style-src 'self'`이다. 그래서 CDN 글꼴과 `data:` URL은 쓸 수 없다. Vite가 woff2를 `/react/`로 내보내고, 기존 `sheetAssets` 플러그인이 `data:` 인라인을 파일로 바꾼다.
- 브라우저는 `unicode-range`가 맞는 조각만 내려받는다. 그래서 화면당 전송량은 전체보다 훨씬 작다. 다만 dist는 약 3MB 늘어난다.
- 기존 CSS에는 `#tna-app` 선택자 2,000곳과 `!important` 11곳이 섞여 있다. 새 규칙이 이를 이기려고 특이성 경쟁을 하지 않도록 cascade layers를 쓴다. 층 없는 규칙은 특이성과 관계없이 층 안의 일반 규칙보다 우선한다.
- 2026-11-10 이 저장소의 Vite 8.3.5로 `@import … layer(vendor)`를 빌드했다. 결과 `sheet.css`에서 AG Grid 규칙이 `@layer vendor{…}`로 펼쳐진 것을 확인했다.

## 경계

- 층 감싸기 자체는 화면을 바꾸지 않는다. 합성 캡처의 픽셀 비교로 이를 확인한다. 2026-11-10 6개 화면, 2개 테마, 2개 폭(24장) 모두 차이 0을 확인했다.
- legacy 층 안의 `!important`는 새 규칙보다 우선한다. 충돌하는 곳만 지운다.
- 로그인 전 화면도 legacy 토큰 별칭을 통해 글꼴과 색이 바뀐다. 배치는 바꾸지 않는다.
- `node:fs`를 쓰는 계약 테스트는 tsconfig에 node 타입이 없으므로 `.test.js`로 둔다(`viteProxy.test.js`와 같은 관례).

## 공식 자료

- [Pretendard 저장소와 라이선스](https://github.com/orioncactus/pretendard)
- [npm pretendard 1.3.9](https://www.npmjs.com/package/pretendard/v/1.3.9)
- [CSS Cascade Layers (MDN)](https://developer.mozilla.org/docs/Web/CSS/@layer)
- [`@import` layer (MDN)](https://developer.mozilla.org/docs/Web/CSS/@import)
