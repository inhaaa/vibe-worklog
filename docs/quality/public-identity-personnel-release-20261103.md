# 사번·권한·인사정보 공개 소스 통합 검증

2026-11-03 Asia/Seoul. 기존 공개 `63782fe` 위에 개발 기능 `9d17160`을 통합했다. 개발 후속 `e3d9c76`의 탭 대비 보완 두 파일과 개발 검증 문서 변경도 반영했다. 이 문서는 공개 소스의 로컬 검증 결과이며 서버 배포 완료 보고서가 아니다.

## 통합과 보호 경계

- 사번 로그인·기존 이메일 호환, 직원 공통 메뉴와 선택한 실제 보유 권한 메뉴, 별도 테스트 사원 로그인, 내정보 7개 탭과 V17을 연결했다.
- 공개 `public-demo` 단독 프로필, 운영자 시작 거부, Secure Cookie·CSRF·세션 회수·현재 역할 검증·테넌트 조건과 RLS를 보존했다. 인증 대상은 고정 합성 고객사와 명시한 5개 account/employee UUID이며 실제 DB 사번·활성/재직·정확 역할·비밀번호 해시를 검증한다.
- 기존 공개 WorklogSheet의 셀 편집·안정적인 행 ID·저장 직전 편집 반영을 유지했다. 신규 사번만 선택 입력으로 바꾸고 기존 사번 읽기 전용을 보존했다. 사진 자산과 공개 bootstrap 전용 API/DB 경계, 공개 개인정보 안내도 유지했다.
- 공유 계정 보호는 사번과 무관하게 고정 5개 사원 UUID를 기준으로 한다. 합성 검증 사번은 99999/26001/26002/26003/25001이다. 기존 사원 변경·역할·재초대·일괄 변경 보호에 더해 `personal-details`/`personal-entries` POST를 HTTP와 서비스에서 함께 차단한다. 일반·명시적 로컬 데모에서는 기존 담당자 편집 규칙을 유지한다.
- 과거 PublicDemoPersonnelFill의 4명·옛 사번/이름·날짜·사진 manifest 계약을 완화하지 않았다. 과거 fixture는 별도 전용 컨테이너에서 명시적으로 재현하며 사번 전환 뒤에는 무변경 거부를 검증했다. 실제 기존 DB에서 bootstrap·sample-data·과거 보완 명령을 재실행하지 않았다.

## TDD와 직접 검증

- 공개 시트 RED: 신규 사번의 필수 입력 검증 때문에 새 테스트 1개 실패, 기존 28개 통과를 확인한 뒤 공통 시트의 사번 필수 속성만 해제했다.
- 신규 보호 RED: 새 인사 상세 HTTP/직접 서비스 테스트 2개가 실패했다. 전용 합성 DB에서 403 대신 303/404와 상세/감사 변경을 검출한 뒤 기존 PublicDemoIdentityGuard를 새 서비스에 주입하고 공개 HTTP 차단 목록에 두 경로를 추가했다. 운영 DB를 사용하지 않았다.

| 검증 | 실제 결과 |
| --- | --- |
| 공개 프로필·공유 UUID 단위 | 8개 통과 |
| 공개/로컬 guard·과거 인사 보완·인증/새 경로 PG 통합 | 44개 통과 |
| 프론트 전체 (`--maxWorkers=2`) | 23파일 369개 통과 |
| 최종 타입·Vite 빌드 | 통과; 기존 AG Grid 포함 청크 크기 경고 유지 |
| 전체 Java (`mvnw.cmd -B -Dfrontend.skip=true verify`) | 단위 159개 + PostgreSQL 통합 634개 = 793개 통과; 실패/오류/건너뜀 0 |

공개 보호 검증은 5개 공유 UUID의 단건·혼합 bulk에서 사원/계정/권한/토큰/인사/감사 무변경, 신규 두 경로의 대소문자·공백 인코딩·쿼리 변형, 직접 서비스 호출을 포함한다. 추가한 전체 회귀는 5개 테스트 사번의 실제 DB 해시 로그인·정확 principal·관리/결재 API의 역할별 허용/차단도 확인한다.

작업트리의 ignored `identity-public-security-red.log`, `identity-public-security-green.log`, `identity-public-frontend-red.log`, `identity-public-frontend-all.log`, `identity-public-frontend-build.log`, `identity-public-java-full.log`에 실행 근거를 보존한다. 실패 로그는 최초 실패 증거로 유지한다. 전체 Java는 14:34:17 KST에 `BUILD SUCCESS`로 종료했으며 로컬 실행 시간은 32분 44초다.

위 로그와 npm 준비 로그 7개를 원본 그대로 `target/identity-personnel-deploy-20261103/verification-logs/`에 복사하고 원본/복사본 SHA-256 일치를 확인했다. 원본 파일을 이동하거나 삭제하지 않았다. 미해결 병합 충돌은 0개이며 최종 `git diff HEAD --check`를 통과했다.

## 남은 범위

공개 서버 백업·독립 복원, 실제 V17/명시 사번 전환·계정 준비, 전후 자료/권한 보존, 공개 HTTP·PC/모바일·기존 서비스 확인과 배포 SHA/이미지 기록은 별도 배포 단계다. 새 장시간 부하·전체 취약점 재검사·실제 개인정보 처리·상용 운영 수용은 이번 통합 검증에 포함하지 않는다.
