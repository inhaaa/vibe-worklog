# ADR-0005: 공통 업무 그리드에 AG Grid Community 사용

- 결정일: 2026-10-29 `Asia/Seoul`
- 상태: 사용자 승인, 순차 구현 중

## 결정과 근거

화면별 native table·입력 UI를 `WorklogSheet`로 통일한다. 업무용 목록·정렬·필터·열 조절·고정·셀 편집·일괄 저장이 필요하므로 UI가 없는 headless 라이브러리나 자체 그리드 엔진보다 AG Grid Community를 사용한다. draft·권한·저장·충돌·명령·이력은 기존 페이지와 서버에 둔다. [설계](../superpowers/specs/2026-10-29-common-sheet-design.md)와 [실행 계획](../superpowers/plans/2026-10-29-common-sheet.md)을 따른다.

2026-10-29 공식 문서와 npm registry에서 `ag-grid-community`·`ag-grid-react` 36.2.0의 MIT 라이선스와 React `^19.0.0` peer 범위를 확인했다. 공식 TypeScript 최소 요구는 36+에서5.8.3이며 저장소는 React19.3.1·TypeScript6.0.3이다. 두 패키지는 동일 버전으로 고정한다. 전역 패키지를 설치하지 않는다. 실제 테스트·브라우저·배포 범위는 후속 품질 보고서에 별도 기록한다.

## 보안·기능 경계

- 현재 CSP `script-src 'self'; style-src 'self'; img-src 'self'`를 보존한다. legacy Quartz CSS를 외부 `sheet.css`로 빌드하고 embedded 글꼴·SVG는 같은 출처의 해시 파일로 추출한다. CDN·data URL·unsafe-inline·unsafe-eval을 허용하지 않는다. 열 값·스타일 규칙은 함수로 작성하며 문자열 표현식을 실행하지 않는다.
- Community 기능만 사용한다. 내장 Excel export·범위 선택·Excel형 clipboard·Enterprise 모듈은 추가하지 않는다. 기존 서버의 검토용 Excel 경로를 유지한다.
- 서버가 반환한 현재 페이지의 행만 정렬·필터하며 그 범위를 안내한다. 필터는 draft나 선택을 지우지 않으며 저장은 현재 페이지 전체 변경 행을 전송한다.
- 현재 최대50행 폼의 입력·레이블·검증·초점 처리를 위해 행/열 가상화를 사용하지 않는다. 초기 및 갱신 렌더는 실제 AG Grid의 비동기 동작을 테스트에서 기다린다. 수량·시간은 표시 문자열과 숫자 정렬값을 구분하고 미확정 null을0으로 바꾸지 않는다.
- 작은 화면은 내부 스크롤을 사용한다. 달력·계층 조직도와 Spring 바인딩 요일 입력 행렬은 목적에 맞는 기존 구조를 유지한다.

## 공식 자료

- [MIT](https://github.com/ag-grid/ag-grid/blob/latest/LICENSE.txt), [무료/유료 기능](https://www.ag-grid.com/react-data-grid/community-vs-enterprise/)
- [호환 요구](https://www.ag-grid.com/react-data-grid/compatibility/), [React npm metadata](https://www.npmjs.com/package/ag-grid-react/v/36.2.0)
- [CSP](https://www.ag-grid.com/react-data-grid/security/), [legacy CSS](https://www.ag-grid.com/react-data-grid/theming-v32-customisation-css/)
- [실제 브라우저와 jsdom 검증 경계](https://www.ag-grid.com/react-data-grid/testing/)
