# React 연도별 현황·모달 신청 실행 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. 파일 소유를 나누어 같은 세션에서 실행하고 명세·품질 검토를 거친다.

**Goal:** 본인 연도별 현황 조회→모달 미리보기→신청, 근거 있는 기초 귀속 이력을 구현하고 로컬 검증·문서·커밋·일반 Push한다.

**Architecture:** [선행 계약](../../design/annual-requests.md)의 조회 DTO와 V15를 기준으로 기존 BusinessCommands/OrgCommands·연차 원장·정정·결재·React 폼 transport를 재사용한다. 새 계산·의존성·자동 분류를 추가하지 않는다.

**Tech Stack:** 기존 Java25/Spring/MyBatis/PostgreSQL/Flyway·React/TypeScript/Vitest.

## 실행 단위

- [x] README/kickoff/PRD·확장8절·현행 코드를 조사하고 스키마/화면/API 계약을 선행 작성한다. 기준 commit ed96992는 직전 단계 Java618·React168을 통과한 버전이다.
- [ ] 귀속 담당: V15·`LeaveAttributionData/Mapper/Service/Controller`·`LeaveAttributionIT`를 추가한다. 미분류 기본·원장 불변·확정/해제 revision·동시성·멱등/권한/테넌트·불변 SQL 테스트 RED 후 구현·GREEN.
- [ ] 연간/신청 담당: `AnnualRequestData/Mapper/Service/Controller`와 기존 RequestController의 본인 진입/POST 문맥을 연결한다. 연도clip·필터 독립·분리작성·50/51·미리보기 무쓰기·fallback·기존 POST 호환·409/422 복원 테스트 RED 후 구현·GREEN. 기존 연차 날짜 계산을 preview와 재사용한다.
- [ ] React 담당: `AnnualRequestPage`와 접근성 dialog·타입별 폼·미리보기·귀속 관리/원장 연결을 테스트 RED 후 구현한다. 공통 메뉴/제목·일별 정정·재신청 연결을 확인하고 전체 Vitest/타입/Vite를 검증한다.
- [ ] root: allowlist·보안/advice·감사·schema/복원 기대값과 HTTP/기존 테스트를 연결한다. Maven은 root 큐로 직렬 실행한다. 독립 명세검토 후 품질/권한 검토와 전체 Java 회귀.
- [ ] root: 기본V14 backup·기존열/행hash·ACL/RLS·DB volume 기록→app/migrate build→V15→자료보존·health·실행JAR검증. 별도 합성 HTTP·PC/모바일·논리복원·정상 정리.
- [ ] 문서: README/PRD/local/관련 계약·`docs/quality/annual-requests.md`에 실제 결과와 미실시 항목을 구분한다. 링크/인코딩/범위/비밀값·git diff 점검.
- [ ] 승인된 범위 commit→main fast-forward→일반 Push→원격SHA/clean 확인. 공개 작업트리 동시 진행 여부와 실제 공개 반영을 별도로 기록한다.
