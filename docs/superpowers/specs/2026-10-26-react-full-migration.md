# WorkLog 전체 React 전환 계약

2026-10-26 사용자는 직원 목록만 전환하는 초안 대신 기존 화면 전체를 React로 바로 전환하도록 승인했다. 필요한 프로젝트 설정 변경과 전용 로컬 DB 검증도 앞선 확인에 이어 승인한 범위다. 기존 업무 확장의 신규 기능은 추가하지 않는다. 파일 삭제·외부 배포·Git Push는 이번 전환에 포함하지 않는다.

## 구조와 HTTP 인터페이스

- React + TypeScript + Vite가 소개·고객사 인증·복구·개인 업무·관리·신청/승인·결과/감사·별도 운영자 화면을 모두 렌더한다. Thymeleaf/htmx를 실행 의존성과 페이지 로딩에서 제거한다. 기존 템플릿 원문은 삭제하지 않고 전환 대조 이력으로 보존한다.
- 기존 Controller→Java 서비스→MyBatis→RLS와 URL·폼 인코딩·명령 ID·낙관 버전 계약을 유지한다. 같은 경로에서 표현 형식만 HTTP Accept로 선택하므로 업무 API와 권한 로직을 복제하지 않는다.
- `Accept: application/vnd.worklog.page+json`에 화면 응답은 `{page: string, data: object, csrf: {token,parameterName,headerName}}` JSON을 반환한다. 일반 브라우저 진입은 React root와 안전하게 인코딩된 같은 bootstrap JSON, `/react/app.js`, 기존 CSS를 포함하는 HTML이다. `Vary: Accept`와 `Cache-Control: no-store`를 적용한다.
- JSON data는 페이지별 허용된 기존 모델만 노출한다. BindingResult·비밀정보·전체 인증 principal은 제외한다. workspacePrincipal은 역할 목록만, workspaceEmployee는 화면용 이름·회사명만 제공한다. CSRF·사용자별 JSON은 캐시하지 않는다.
- 상태 코드·303 redirect·다운로드는 보존한다. React 탐색·폼 제출은 같은 출처 fetch와 기존 URLSearchParams 폼 계약을 사용한다. 파일 다운로드는 브라우저 다운로드로 처리한다. POST 성공 후 redirect 최종 경로로 이동하며 오류 응답에서도 입력 보존·비밀번호 미복원을 지킨다.
- API 표현에서 미인증401·권한/CSRF403·일시 장애503은 안전한 JSON 오류로 구분한다. 세션을 파기한503은 `data.reauthenticate=true`로 표시해 개인정보 화면을 지우며, 일반 업무503은 같은 명령·입력으로 재시도할 수 있게 유지한다. 고객사와 운영자 세션 경계, 기본 거부, CSRF, CSP, 로그인 제한과 조회 반환 전 권한 재검증을 유지한다.
- 저장 결과를 받기 전 앱 내부 탐색·재조회·이중 제출을 막아 이미 전송된 POST를 클라이언트 탐색으로 취소하지 않는다. POST의409/422는 원래 GET 주소를 유지하면서 오류·입력 모델만 바꾼다. 같은 문서의 fragment 이동은 데이터를 다시 조회하거나 폼을 초기화하지 않는다.
- 새 탐색은 이전 요청의 늦은 응답을 무시한다. 401/403·로그아웃에 개인정보 화면 상태를 지운다. 사용자 데이터·자격을 localStorage나 서비스워커에 보관하지 않는다. 업무 날짜는 서울 기준 날짜로 표시한다.
- Node는 개발/빌드 전용이다. 빌드된 정적 파일을 기존 JAR에 포함해 하나의 Spring 실행 프로세스로 제공한다. Docker 빌드도 프론트 빌드 단계를 포함한다.

## 범위별 책임

1. 서버 표현 계층: view allowlist·모델 envelope·bootstrap XSS 인코딩·인증 오류와 단위/웹 테스트.
2. 관리 JSX: 조직·직원·계정·역할/승인자·고정일정/배정·휴일·허용망의 조회와 폼.
3. 업무 JSX: 출퇴근/확인·휴가·정정·승인·원장·계산정책·집계·Excel·감사.
4. 공통/공개 JSX: 앱 탐색·폼·사이드바·소개·홈·개인 달력·로그인/초대/복구·독립 운영자.

## 수용과 검증

- 타입 검사, 프론트 단위/행동 테스트와 production 빌드, Java 기본/전체 전용 PostgreSQL 회귀를 실행한다.
- 기존 HTML 문자열 검사는 React shell·JSON 표현과 React 컴포넌트의 동일 업무 계약 검사로 전환한다. 기존 서비스·테넌트·권한·동시성 테스트는 유지한다.
- 패키징된 로컬 실행에서 고객사 로그인·데모 입장·메뉴·GET/POST·오류·다운로드·새로고침·좁은 화면·로그아웃을 확인한다. 운영자 활성화 경계도 검사한다.
- 문서·ADR·라이선스·개발/컨테이너 빌드 방법을 실제 결과로 갱신한다. 미실시 취약점 스캔·장시간 부하·운영 검증은 기존 기록과 구분한다.

공식 근거: [React 기존 프로젝트 연결](https://react.dev/learn/add-react-to-an-existing-project), [Vite 빌드](https://vite.dev/guide/build), [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html). 정확 버전·라이선스는 npm registry와 고정 설치 결과로 기록한다.
