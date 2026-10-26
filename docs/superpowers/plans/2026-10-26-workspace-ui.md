# A안 실제 화면 적용 계획

> 사용자 승인: 소개·홈·일정·직원 목록 시안과 로그인 입력 없는 데모 진입을 실제 화면에 적용하고, 검증 후 커밋·일반 Push한다. subagent-driven-development·TDD·완료 전 검증을 적용한다.

**Goal:** 승인된 밝은 워크스페이스를 기존 Thymeleaf 업무 화면과 실제 자료에 연결한다.

**Architecture:** 기존 서비스·인증 provider·CSRF·세션·RLS를 재사용한다. 로컬 데모의 소개 버튼이 기존 POST `/login`으로 공개 합성 자격을 전달하며 일반 프로필의 로그인은 유지한다. SSR 조회를 조합하고 스키마·계산 정책·실고객 자료를 변경하지 않는다.

**Tech Stack:** 현재 Spring Boot·Thymeleaf·htmx·Java·PostgreSQL, 자체 CSS와 작은 외부 JS. 새 프레임워크·외부 UI CDN을 추가하지 않는다.

## 먼저 고정한 인터페이스

- `DemoController`: 기존 `/`·`/login` GET을 TodayController에서 이동한다. 두 프로필이 활성화되면 `/`와 `/login`에서 `/demo`로 안내한다. GET `/demo`는 소개, 버튼은 CSRF를 포함한 기존 POST `/login`, 성공은 `/me/today`다. 일반 프로필의 `/`·`/login` 계약은 유지한다.
- SecurityConfiguration: `local && local-demo`에서만 GET `/`·`/demo`를 공개한다. 새 정적 `/app.js`는 공개 GET, `/me/schedule`은 인증된 본인 조회다. provider·세션 회수·정확한 데모 역할·속도 제한·최소 권한을 유지한다.
- `fragments/workspace.html`: `head(title)`, `sidebar(active)`, `topbar(title)` 세 fragment. `body.workspace-body > #tna-app > .t-app-shell > aside + header + main.t-main`을 사용한다. 새 화면의 공통 클래스는 시안의 `t-*`이며 기존 폼 계약을 보존한다.
- 공통 모델은 `workspacePrincipal`·`workspaceEmployee`·`workspaceDemo`를 사용한다. 인증된 TnaPrincipal의 본인 정보만 기존 읽기 경계로 조회하며 운영자 인증과 구분한다.
- GET `/me/schedule?month=YYYY-MM&date=YYYY-MM-DD`: 월 달력과 선택 날짜의 기존 일정·휴일·효력·미확인 상태를 SSR로 표현한다. 없는 자료를 정상 0시간이나 승인 완료로 만들지 않는다.
- 직원 목록은 기존 권한·검색·페이지·사원별 상세/변경 기능을 보존하며 필터·선택 상세는 현재 조회 범위 안에서 구현한다. 통계는 조회 범위나 실제 전체 집계를 정확히 표시한다.
- 실제 저장 자료를 렌더링한다. 시안의 인명·8명·잔여8일·32시간을 실제 값으로 하드코딩하지 않고 초기 미배정/미등록을 표시한다.

## 작업 단위

- [x] 데모 소개·정상 인증 흐름: 테스트 RED → 구현 → 관련 보안 테스트 GREEN.
- [x] 홈·개인 달력 읽기 모델: 테스트 RED → 기존 읽기 서비스 조합 → 날짜/테넌트/미확정 검증.
- [x] 직원 목록·선택 상세: 테스트 RED → 실제 조회와 화면 연결 → 검색/권한/페이지/상태 검증.
- [x] 공통 shell·CSS·메뉴·기존 관리 페이지 연결: 역할별 메뉴, CSP 준수, 키보드·모바일·폼 동작 확인.
- [x] 전체 Java/Python·HTTP·실제 브라우저 회귀, 새 app/migrate 이미지 빌드와 로컬 적용. 기존 DB·볼륨·권한·RLS 보존 확인.
- [x] README·PRD·설계/개발 안내·실제 검증 보고 갱신, 독립 검토와 최종 검증. 커밋·일반 Push의 실제 해시·원격 일치·작업 트리 상태는 최종 완료 보고로 확인한다.

외부 서버·배포·전역 패키지·실제 메일·실고객 자료는 범위 밖이다. 새 이미지에 관한 보안 상태를 이전 이미지의 스캔 결과만으로 확정하지 않으며, 기존 P1을 완료로 바꾸지 않는다.
