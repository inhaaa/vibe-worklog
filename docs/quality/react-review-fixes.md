# React 재점검 3건 수정

기준일: 2026-10-27, Asia/Seoul. 사용자가 승인한 [React 전환](react-migration.md)의 재점검 3건을 수정하고 전체 회귀·최종 로컬 실행을 검증했다. 범위와 실패 처리 계약은 [실행 계획](../superpowers/plans/2026-10-27-react-review-fixes.md)을 따른다.

> 후속 장시간/이미지 검사도 수행했다. Java489·프론트95·Python62·실제33분15,471요청 오류0과 보안 수정·잔여 항목은 [최종 보고서](react-soak-security.md)에 별도로 기록한다. 아래 수치는3건 수정 당시 이력이다.

## 변경과 재현

| 문제 | 수정·회귀 검증 |
| --- | --- |
| 뒤로/앞으로 조회 실패 시 주소와 편집 화면 불일치 | 표시 중인 GET 주소로 복구하고 입력·명령을 보존한다. 지연된 history 조회를 다른 탐색/POST가 대체하거나 현재 화면으로 돌아오는 경합도 같은 계약으로 검증한다. 최초 회귀 RED 4건과 추가 경합 RED 8건을 실제 실패로 확인했다. |
| 개발 서버의 직접 Excel 다운로드가 HTML로 바뀜 | query를 제외한 `/admin/reports/exports/{id}/download`는 Vite HTML 우회에서 제외한다. 기존 코드는 신규 테스트 중 3건 실패했다. 실제 임시 loopback Vite·합성 백엔드에서 query 유/무의 Content-Type·Content-Disposition·바이너리 본문과 일반 HTML·JSON·POST·CSS·미허용 경로를 확인한다. |
| 운영자 조회 대기 중 권한 회수 후 자료 반환 | 회사·감사 조회 반환 직전에 활성·인증 개정·세션 수명을 다시 검증한다. 기존 쓰기 트랜잭션·전용 운영자 연결은 유지한다. 단위 RED 3건과 실제 PostgreSQL에서 잠금 대기 중 회수 커밋 후 기대403/실제200을 확인했다. |

운영자 문제는 기존 서비스에 남은 조회 경계이며 React 전환으로 새로 생긴 문제로 단정하지 않는다. 새 라이브러리·스키마 변경은 없다.

## 검증 결과

| 범위 | 실제 결과 |
| --- | --- |
| Java 전체 | **487개(단위94·전용 PostgreSQL 통합393), 실패·오류·skip0**. `target/react-fixes-java-verify.log`, BUILD SUCCESS. |
| 운영자 focused | **32개 통과**. 신규 단위7개·실제 권한 회수 동시성1개 및 기존 서비스·웹·고객사/운영자 세션 분리 포함. `target/react-review-operator-green.log`. |
| Python 도구 | **47개 통과**. `target/react-fixes-python.log`. |
| 프론트 최종 회귀 | **95개 통과**. App31개, 실제 Vite HTTP 프록시4개와 기존 페이지·인증·전송 테스트 포함. `target/react-fixes-frontend-test.log`. |
| 타입·production 빌드 | TypeScript 검사·Vite 빌드 성공. bundle343,486bytes·gzip 약96.59kB. `target/react-fixes-frontend-build.log`. |
| Maven·Docker | 최종 JAR 패키징과 Linux app/migrate 이미지 빌드 성공. 별도 패키징의 테스트 생략은 위 전체 verify와 구분한다. `target/react-fixes-package.log`, `target/react-fixes-docker-build.log`. |
| 로컬 실행·DB 보존 | `--no-deps`로 app/migrate만 갱신, migrate 종료0·Flyway10개 검증·V10 no-op. 기존 DB 컨테이너 ID `5756b9da60aa`와 기존 데이터 유지. `target/react-fixes-runtime.log`. |
| 패키지·실행 일치 | 로컬 dist·로컬 JAR·실행 JAR·HTTP JS의 SHA256 일치, 로컬/실행 `OperatorService.class` 동일. React MIT 포함·이전 Thymeleaf/templates/htmx 실행 자원 부재. `target/react-fixes-package-check.json`. |
| 실제 HTTP | 갱신한18080 앱에서 기본 보안 **24개 통과**. `target/react-fixes-http.log`. |
| 합성 브라우저 장애 회귀 | 실제 production JS와 임시 loopback 합성 서버에서503·연결 종료 뒤 GET 주소/입력 보존,12초 지연 GET 중 뒤로→앞으로 이동 뒤 늦은 응답 무시를 확인했다. 명령 ID 보존은 DOM 값이 마스킹되는 브라우저 판정 대신 App 회귀 테스트에서 실제 값으로 검증했다. |
| 최종 앱 브라우저 | 실제 데모 로그인·직원 목록/상세·뒤로가기·직접 새로고침·로그아웃 뒤 개인정보 차단 확인. 가로 넘침/console error0. |

최종 JS SHA256은 `9a67c37980112e31fd7f7029826f24c429834a0efe788225266f74fe59268a4f`다. 합성503 화면 `target/react-verification/review-popstate-503.jpg`와 최종 실행 화면 `target/react-verification/review-fixed-runtime.jpg`는 ignored target의 로컬 증거로만 보관하고 Git에는 게시하지 않는다. 임시 합성 서버·검증 탭은 종료했고 최종 로컬 앱의 직원 화면은 열어 두었다. 변경 문서7개의 로컬 링크189개와 git status/diff·새 테스트/보고서 내용 검증도 통과했다.

## 변경 파일

- 탐색: [App.tsx](../../frontend/src/App.tsx), [App.test.tsx](../../frontend/src/App.test.tsx).
- 개발 프록시: [vite.config.ts](../../frontend/vite.config.ts), [viteProxy.test.js](../../frontend/src/viteProxy.test.js).
- 운영자: [OperatorService.java](../../src/main/java/kr/tna/console/OperatorService.java), [OperatorServiceTest.java](../../src/test/java/kr/tna/OperatorServiceTest.java), [OperatorIT.java](../../src/test/java/kr/tna/OperatorIT.java).
- 현재 상태·계약·실행 결과는 README·PRD·개발 안내·운영자 온보딩·React 전환 보고서와 이번 실행 계획에 반영했다. 이전 React 전환의 사용자 수정과 미커밋 변경을 보존했다.

기본 제한 실행의 Node/JDK 파일 접근 거부는 실행 권한을 조정해 재실행했다. 제품 테스트의 실패와 구분하며 전역 설정·설치는 변경하지 않았다. 전체 Java 검증은 기본 DB를 사용하지 않은 새 Testcontainers PostgreSQL에서 수행했다.

새 이미지 전체 SBOM/취약점 검사·장시간 부하·실메일·실고객·외부 배포는 이번 검증 범위에 포함하지 않는다. 이전 보고서의 미실시 검증·잔여 보안 항목은 유지한다.

커밋·Git Push·파일 삭제·호스트 전역 설정 변경은 수행하지 않았다.
