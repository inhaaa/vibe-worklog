# 공통 시트 정렬·한 줄 구현 계획

> **For agentic workers:** Use subagent-driven-development for independent caller cleanup and verification-before-completion for the result.

**Goal:** 모든 공통 시트의 헤더 중앙·값별 정렬·40px 한 줄 표시를 일관되게 적용한다.
**Architecture:** 기존 WorklogSheet/ColDef·cellClassRules/renderer와 scoped CSS를 재사용한다. 페이지별 큰 행/줄바꿈 예외만 제거한다.
**Tech Stack:** 기존 React19·TypeScript6·AG Grid Community36.2·Vitest5/Vite8.

- [ ] `WorklogSheet.test.tsx`에 숫자/문자열ID·3/4어절·문장·formatter·복합 링크·재표시 정렬과 wrap/autoHeight 거부 회귀를 추가한다. `npm --prefix frontend test -- src/components/WorklogSheet.test.tsx`로 RED를 확인한다.
- [ ] `WorklogSheet.tsx`에 표시 텍스트 정렬과 한 줄 열 강제를 추가한다. `worklog-sheet.css`는 헤더 중앙/nowrap/ellipsis와 기존 block 문맥만 한 줄로 제한한다. 값/링크/편집은 보존한다.
- [ ] 독립 작업으로 Admin/ApprovalRoute/Code/OrgSheet/Workflow 호출부의 큰 rowHeight·wrapText/autoHeight 예외를 제거한다. 전역 CSS나 업무 규칙은 바꾸지 않는다.
- [ ] 관련 테스트→전체 `npm --prefix frontend test`→`npm --prefix frontend run build`를 통과한다. 로컬5175에서 대표 편집/조회·PC/390px 헤더/행/말줄임과 링크·키보드를 확인한다.
- [ ] 실제 검증과 변경 범위를 기록하고 diff/status 확인 후 단위별 Commit/일반 Push한다. 공개 반영이 필요하면 기존 공개 보안을 유지한 전용 절차와 실제 가능한 접속 범위로 검증한다.
