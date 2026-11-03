# 신청 작업공간 디자인·실제 신청 흐름 검증

2026-11-03, Asia/Seoul. [설계](../superpowers/specs/2026-11-03-request-workspace-design.md)와 [계획](../superpowers/plans/2026-11-03-request-workspace.md)의 구현·로컬 검증 결과다. 실제 공개 배포 결과는 별도로 기록한다.

## 변경

- `AnnualRequestPage.tsx`/`RequestWorkspace.css`: 제목 옆 신청 버튼, 현재 사용 가능·사용/예정/대기 카드와 기초/귀속 보조 정보, 실제 createdId의 접수 완료·상세 연결을 적용했다.
- 같은 파일의 작성 모달은 기존 유형 → 내용 → 확인을 유지하면서 신청 요약·SVG·인디고 카드·반응형을 적용했다. 연차 내용 단계의 중복 날짜 조회는 숨기고 정정의 근거 날짜 조회는 유지한다. 새 유형 조회 전 이전 결재 담당을 확정 표시하지 않으며, 미리보기 후 요약도 새 서버 잔여를 사용한다.
- `WorkflowPages.tsx`의 `RequestDetailPage`와 `RequestDetail.css`: 한글 유형/상태·기간/사유/결재 카드·저장된 처리 이력·기존 허용 동작을 정리했다. 정정에 가상의 DAY 수량을 붙이지 않는다.
- API/DTO·계산/원장·권한·버전/명령·409/422/503·중복 잠금·CSRF·일반 PAGE·미저장/Escape/초점 계약은 유지했다. 새 의존성·백엔드·DB/설정 변경은 없다. 공개본은 별도 공통 시트 구현을 보존해 상세 함수/필요 import만 이식했다.

## 실제 실행 결과

| 범위 | 결과 |
| --- | --- |
| Annual TDD | 새 UI4건·미리보기 잔여 동기화1건·연차/정정 날짜 조회1건의 기대 RED를 확인한 뒤 GREEN |
| 상세 TDD | 카드/한글/실제 이력/권한·전송/미지 코드 관련7건 RED 후 GREEN; 공개본 jsdom 방식으로 별도 적용 |
| 개발 프론트 전체 | 19파일251개 통과, 타입·production 빌드 통과 |
| 공개 통합 프론트 전체 | 22파일336개 통과, 타입·production 빌드 통과 |
| JAR 준비 | 프로젝트 전용 JDK25.0.5로 양쪽 Maven package 성공. 테스트 생략을 명시했으며 새 Java 전체 실행으로 계산하지 않음 |
| 독립 검토 | 실제 잔여 동기화 P2 1건을 재현·수정·전체 회귀 확인. 상세 액션/참조 보존 추가 P1/P2 발견0 |

테스트/빌드 로그는 ignored `target/request-workspace-*`에 보존한다. npm wrapper가 옵션을 삼킨 초기 실행과 구분해 최종 전체는 직접 Vitest `--maxWorkers=2`로 실행했다. 공개 Vite의 기존 큰 chunk 경고는 남으며 임계값을 완화하지 않았다.

## 실제 브라우저·DB 확인

새 owned 합성 DB·loopback 앱18097·일반 default 프로필에서 신청자 EMPLOYEE만, 독립 APPROVER·평일 일정/달력·20DAY 묶음과 기존 AI 가상 사진을 서비스로 구성했다. 공개 DB나 실제 개인정보를 쓰지 않았다.

1. CUA로 로그인·메뉴 → 신청 → 유형 선택 → 11-04 하루/묶음/사유 입력 → 실제 서버 미리보기 → 최종 POST → 접수 완료 → 생성 신청 상세를 수행했다.
2. 미리보기 전후 READ ONLY로 신청0·결재 이벤트0·원장1·감사8·원천0·signed 잔여20DAY가 동일함을 확인했다.
3. 최종 제출은 정확1건의 본인 PENDING LEAVE_DAY·11-04·1DAY·독립 승인자와 명령/사유/감사를 확인했다. 결재 이벤트1·감사9(+1), 원장1·원천0·잔여20DAY 그대로이며 대기에서 차감하지 않는다.
4. PC1440·모바일390의 확인/상세, 모바일 유형/정정 근거, 일반 PAGE 경로의 가로 넘침0을 확인했다. 정정은 제출하지 않았다. 모바일 동작 버튼44px·콘솔 오류/경고0, 실제 제공 JS/CSS와 최종 빌드/패키지 SHA 일치를 확인했다.
5. 미저장 확인의 단위 테스트는 통과했다. 실제 CUA에서는 native confirm이 도구에 노출되지 않아 미저장 닫기의 승인/취소를 수동 검증 완료로 계산하지 않는다. 다른 새 탭의 최종 빌드에서 실제 접수 흐름을 마쳤다.

화면과 증거는 ignored `target/request-workspace-ui-20261103/`의 `02-type-desktop-final.jpg`, `03-content-desktop-final.jpg`, `04-review-desktop.jpg`, `05-received-desktop.jpg`, `06-detail-desktop.jpg`·모바일 화면, `preview-no-write-proof.json`, `actual-submit-proof.json`, `browser-proof.json`에 보존한다.

처음 QA helper는 소속 배정 후 사원 version을0으로 고정해 충돌했다. 실제 version 조회로 고치고 실패 증거를 attempt-1에 보존했다. 이후 Docker Desktop 종료로 생긴 기동 실패도 attempt-2에 보존한 뒤 기존 Desktop만 다시 시작했다. 제품/권한·TLS/엔진/전역 설정을 완화하지 않았다. 두 실패를 제품 테스트 통과로 계산하지 않는다.

새 장시간 부하·전체 취약점 스캔·Java 전체 재실행·실제 고객/개인정보/메일·상용 수용은 이번 검증에 포함하지 않는다. 공개 공유 계정의 결재 담당 미배정 차단은 그대로 유지하며, 접수 완료 예시는 독립 승인자가 있는 전용 합성 환경의 실제 신청이다.
