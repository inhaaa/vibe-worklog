# 데모 인사정보·헤더·메뉴 표시 구현 계획

> 실행: 요청된 독립 범위를 sub-agent로 나누고 TDD를 따른다. UI와 단발 자료 등록을 통합 검증한 뒤 기존 승인 범위의 공개 데모에 반영한다.

**목표:** 가상 사진/인사정보가 실제로 채워진 탭 없는 헤더와 업무별로 정돈된 메뉴를 구현·검증·공개한다.

**구조:** 기존 V16/API/EmployeeHeaderData·권한을 재사용한다. 헤더는 표시만, 메뉴는 기존 경로·역할만, 가상 자료는 명시적1회 CLI만 담당한다. 새 의존성·스키마·설정/권한 확대는 없다. [설계](../specs/2026-11-02-demo-personnel-header-design.md)를 따른다.

- [x] 현재 파일/계약을 읽고 변경 범위·고정4인/사진 manifest 인터페이스를 먼저 정한다.
- [x] 메뉴 담당: `WorkspaceNavigation.test.tsx`의 활성 조상 RED → 동일 TSX/CSS의 SVG·선택 강조 구현 → 선택 회귀/타입 확인. 다른 권한/경로/모바일 상단 규칙은 보존한다.
- [x] 사진 담당: 가상 성인4장 개별 생성·시각 검수·용량/디코딩/SHA 확인, `demo/personnel-20261102/photos/*.jpg`와 `prompts.md` 보존.
- [x] 헤더 담당: `App.test.tsx`의 필드 전체 노출/헤더 nav 부재 RED → `EmployeeHeader.tsx`의 탭/children 제거·App 연결 → `EmployeeWorkspace.css`의 단일 카드/반응형 보완 → 관련 회귀.
- [x] 자료 담당: `PublicDemoPersonnelFillTest/IT` RED → `console/PublicDemoPersonnelFill`와 `TnaApplication` 명시 분기/고정 manifest 구현 → 합성 DB의 원자성·정확 no-op·충돌/권한·다른 tenant/기존 행 보존 GREEN. Maven은 같은 target에서 병렬 실행하지 않는다.
- [x] 프론트 전체·타입/빌드, 프로젝트 JDK25의 Java 전체/도구 검증·최신 JAR/자산 대조를 수행한다. 독립 코드 검토의 발견 사항만 관련 회귀와 함께 수정한다.
- [x] 실제 CLI/사진을 별도 합성 환경에서 관측하고 PC/390px 헤더 무탭·필드/사진·업무 메뉴·키보드/가로 넘침을 확인한다.
- [x] 필요한 변경만 커밋/일반 Push하고 전용 공개 이미지 준비·백업/독립 복원·현재 자료 사전 대조 → 원자 등록 → 예상 추가 행/원문 보존 → 앱 반영을 수행한다.
- [x] 공개 로그인/CSRF/공유 보호·사진/헤더·PC/390px·V16/RLS·기존 서비스/Caddy를 확인한다. README/계약/품질 보고서에 실제 완료/미실시를 기록하고 git status/diff로 최종 범위를 확인한다.
