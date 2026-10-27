# ADR-0003: 전체 React 프론트와 Spring 화면 데이터 계약

## 결정

2026-10-26 사용자가 Thymeleaf의 익숙함 문제를 제기하고 전체 화면을 React로 바로 전환하도록 승인했다. 기존 SSR 선택의 1인 운영 목적은 유지하면서 **React + TypeScript + Vite**, Spring Boot 단일 실행 프로세스를 채택한다. 기존 업무 확장의 신규 기능까지 구현하는 결정은 아니다.

직원 목록만 단계적으로 전환하는 이전 초안은 검토 이력으로 남긴다. 전체 화면의 전환 범위와 수용 조건은 [확정 계약](../superpowers/specs/2026-10-26-react-full-migration.md), 실행 순서는 [계획](../superpowers/plans/2026-10-26-react-full-migration.md)을 따른다.

## 인터페이스와 선택 근거

- React 컴포넌트가 실제 DOM·폼·메뉴·오류 표시를 소유한다. HTML 주입, iframe, Thymeleaf 문법 해석기로 기존 화면을 감싸지 않는다.
- 기존 URL·Controller·서비스·폼/명령/버전 계약을 재사용한다. `Accept: application/vnd.worklog.page+json`은 `{page,data,csrf}`를 받고 일반 GET은 React shell과 같은 bootstrap 데이터를 받는다. 별도 업무 API와 권한 분기를 복제하지 않는다.
- 페이지별 데이터 허용목록으로 서버 내부 모델·인증정보의 노출을 제한한다. bootstrap JSON은 HTML 종료 문자와 줄 구분자를 인코딩한다. CSRF·세션·서버 권한·테넌트 재검증·no-store는 유지한다.
- React의 라우팅·상태 관리는 현재 URL과 필요한 컴포넌트 상태로 구현한다. 라우터·상태/조회 라이브러리·별도 Node 서버를 추가하지 않는다. 향후 실제 라우팅 요구가 복잡해지면 해당 단위에서 다시 판단한다.
- Maven이 npm 잠금 설치·타입 검사·Vite 빌드를 실행하고 결과를 JAR에 넣는다. Docker는 고정 Node builder에서 프론트를 빌드하고 Java builder와 기존 JRE 런타임을 재사용한다.
- Thymeleaf는 실행 의존성에서 제거한다. htmx·기존 템플릿은 원문 대조 이력으로 소스에 보존하되 신규 JAR 자원에서는 제외한다. 실제 화면 사용에는 JavaScript가 필요하다.

## 공식 버전·호환성과 라이선스 확인

조회일: 2026-10-26, Asia/Seoul. npm registry의 고정 버전 metadata와 설치된 LICENSE를 함께 확인했다. 최신성이나 전체 배포물의 법률 감사를 뜻하지 않는다.

| 대상 | 고정 버전 | 확인 |
| --- | --- | --- |
| React / ReactDOM | 19.3.1 / 19.3.1 | MIT. [공식 버전 안내](https://react.dev/versions), registry와 패키지 LICENSE |
| Scheduler | 0.28.0 (ReactDOM 전이) | MIT, 설치 LICENSE가 React·ReactDOM과 동일 |
| Vite / React plugin | 8.3.5 / 6.1.2 | MIT, Node `^20.19.0 || >=22.12.0`. [공식 안내](https://vite.dev/guide/) |
| TypeScript | 6.0.3 | Apache-2.0. [공식 저장소](https://github.com/microsoft/TypeScript). 7.0.2의 Linux 선택적 네이티브 패키지 설치 실패를 실제 확인해, 이 전환에 필요한 기능을 지원하는 일반 JavaScript 컴파일러로 고정 |
| Vitest | 5.0.5 | MIT, Vite≥6.4·Node≥22.12. [공식 요구사항](https://vitest.dev/guide/) |
| jsdom / Testing Library React | 26.1.0 / 16.3.3 | MIT. jsdom의 Node≥18 계약과 호스트22.14 호환을 확인. 테스트 전용 |
| Node / npm | 호스트22.14.0 유지 / builder22.23.4·npm11.21.1 | 2026-10-27 보안 수정. builder `node:22.23.4-bookworm-slim` 공식 digest `sha256:de170b39439eb0e5a3444ad7d60b015614a381e10fecae2e30ec9e735122f38d`. npm은 해당 Docker 빌드 내부만 갱신(Artistic-2.0·Node≥22.9 호환). [실제 검증](../quality/react-soak-security.md) |
| Exec Maven Plugin | 3.6.4 | Apache-2.0, Maven≥3.6.3·JDK≥8. [공식 요구사항](https://www.mojohaus.org/exec-maven-plugin/plugin-info.html), [라이선스](https://www.mojohaus.org/exec-maven-plugin/licenses.html) |

직접/전이 프론트 잠금 메타데이터는 [CSV](../development/frontend-licenses.csv)에 플랫폼별 선택 패키지를 포함해 기록한다. 실제 설치 패키지 수와 구분한다. 런타임 React·ReactDOM·Scheduler의 MIT 전문은 [보존 파일](../../LICENSES/react-MIT.txt)과 프론트 빌드의 `/react/THIRD_PARTY_LICENSES.txt`에 제공한다.

## 검증 경계

프론트·Java·전용 PostgreSQL·Docker·실제 HTTP와 브라우저의 실행 결과는 [전환 검증 보고서](../quality/react-migration.md)를 따른다. 이번 표현 계층 전환을 과거 이미지의 취약점·33분 부하 결과로 검증했다고 주장하지 않는다. 최초 전환 때 남긴 SSR 기반 부하·multipart 도구는2026-10-27 현재 React JSON/boot 계약으로 갱신했다. 보안 패치 후 실제33분·이미지7종·489/95/62개 회귀와 남은 위험은 [후속 보고서](../quality/react-soak-security.md)를 따른다.
