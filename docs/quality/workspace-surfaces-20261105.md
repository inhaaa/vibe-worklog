# 정보 영역·두 테마 후속 검증

2026-11-05~06. [표시 계약](../design/workspace-surfaces.md)을 따른다. 차콜·블루 테마와 정보표·공통 모달 개선을 공개 `2da93d8`·V17에 먼저 반영했고, 11월6일 계산 근거·감사 상세의 추가 정보표까지 공개 `40816d1`로 반영했다. 초기 배포 이력과 재개 후 실제 검증 범위를 아래에서 구분한다.

## 구현

`tokens.css`, `WorkspacePolish.css`, `EmployeeWorkspace.css`, `PersonalDetails.css`, `RequestWorkspace.css`, `ApprovalWorkspace.css`, `RequestDetail.css`, `Modal.css`에서 페이지 배경·흰 작업 영역·색상 제목/라벨·정보표·선택 탭·신청 단계·요약·모달 제목/푸터를 구분했다. 기존 7탭·의미구조·미등록/0·이력·업무 폼·API·권한·테마 저장 계약은 유지했다.

## 로컬 결과

| 검증 | 실제 결과 |
| --- | --- |
| 기존 테스트 선행 | 개인정보/헤더42개, 신청/정정/결재71개 통과 후 CSS 구현 |
| 전체 React | 27파일318개 통과 (`--maxWorkers=2 --testTimeout=15000`), 후속 조직장 이력 신규 테스트와 관련8개 통과 |
| 타입·production build | 통과, CSS106.83kB/JS523.27kB. 기존 번들 크기 안내 유지 |
| 기존 브라우저 회귀 | 두 테마·4폭·5경로 및 모달/메뉴/초점56항목 통과 |
| 추가 화면 검사 | 두 테마×1440/390/320px×7탭42항목, 사원 헤더6, 작성 모달6, 상세/권한/조직장 모달18, 회귀/오류2 =74개 통과 |
| 정보 가독성 | 항목명14px/값16px, 배경 구분, 값/표 셀 범위·키보드·모달 닫기/초점 복귀 확인 |
| Hallmark | 56파일·3경로, 0 FAIL/0 REFLEX/0 WARN. Finish3건 기존 근거 유지 |
| 보존 | 링크182/182·필드119/119·handler94/94·heading18/18·figure12/12 (정적 테스트 마크업 포함) |
| 브라우저 부작용 | runtime 오류·업무 POST·외부 요청0 |

390px 신청 단계 마지막 라벨이15.69px 넘치는 회귀 검사를 먼저 실패 확인한 뒤 모바일3열/줄바꿈으로 수정하고 통과했다. 색 배경에서 입력 경계 대비2.81:1을 발견하여 토큰을 짙게 바꾼 뒤 렌더 검사를 통과했다.

독립 Hallmark 검토의 긴 항목명 분절과 모바일 조직장 이력표2건을 반영했다. 라벨은 단어 단위로 줄바꿈하고 조직장 이력은 항목명5개와 기존 표 머리글·값·상태를 보존하는 모바일 라벨표로 바꿨다. 신규 구조 테스트의 실패를 먼저 확인하고 관련8개 통과·최종 타입/빌드를 확인했다.

최초 전체 실행은 기존 조직50행 렌더 테스트가5초 제한을 넘겨317/318 통과했고 단독 실행도5.8초였다. 업무 코드·테스트 단언은 바꾸지 않고 이 PC 실행 명령의 제한만15초로 늘린 최종 전체 실행에서318개가 통과했다. 프로젝트 설정 파일은 변경하지 않았다.

증거: `target/hallmark-tools/artifacts/browser-check.json`, `surface-check.json`, `surface-*.png`, `target/hallmark-tools/.hallmark/compare.html`. 합성 loopback 자료이며 실제 업무 저장·Java/DB/부하 전수 검증은 이 로컬 결과에 포함하지 않는다.

## 접속과 배포 경계

서버 콘솔에서 Tailscale active·사설100.100.249.124·ssh.socket active22를 확인했다. UFW는 tailscale0·WireGuard·공개80/443만 허용하며 공개22는 막혀 있다. 로컬에는 Tailscale이 없다. 사용자는 설치 없이 Hostinger 콘솔로 배포하도록 선택했다. 공개 SSH 개방·방화벽·전역 도구·인증 설정을 바꾸지 않았다.

공개본은 기존 공유 계정 보호·AG Grid를 보존해 별도 통합했다. 개발본 조직장 이력의 모바일 라벨표 변경은 공개본의 기존 AG Grid를 대체하지 않았으며, 공개 AG Grid의 두 테마와 내부 스크롤을 별도로 확인했다.

## 공개 반영과 검증

첫 공개 반영 SHA는 `2da93d8f071d7a155a581a6cdcc5b7c2a0562b4e`, 이미지 ID는 `sha256:ec2402669d1776b546ee92018cd1da505c661c73ba870fff7dc1363e91b846c1`, JAR SHA-256은 `6e09a972a6296848f40f864cbd92316eb85c2d79f725797b4ea4e1f464103b27`이다. 기록 시각은 2026-11-05 18:10:12 서울이다.

| 검증 | 실제 결과 |
| --- | --- |
| 공개 통합 | 전체 React402개, 후속 관련35개, 타입·빌드, 공개 계약 Python7개 통과 |
| 공개 빌드 합성 UI | 기존56+두 테마·2폭·7탭28=84항목, AG Grid4항목 통과 |
| 실제 공개 HTTP | 자산4개 SHA·로그인/권한/보안/읽기28항목 통과 |
| 실제 공개 모달 GET | 17개 통과, 저장 계산 결과 부재로 계산 근거1개 건너뜀 |
| 실제 브라우저 | 두 테마×PC1440/모바일390×내 정보7탭28회, 신청 모달 두 단계·닫기와 테마 새로고침 유지 확인 |
| 앱·DB 보존 | V17,48표446행 전체 값/스키마/권한 보존, 마이그레이션·seed 재실행 없음 |
| 서버 보존 | 기존 컨테이너 상태·Caddy 동일, demo200·portfolio200·PriceSys401·WorkLog health200 유지 |
| 배포 산출물 | JAR538항목 중 UI자산4개만 변경, 나머지534개 동일. OS/JRE12계층과 revision 라벨 외 실행 설정 동일 |

전용 `/srv/worklog`, Compose `worklog-demo`, loopback 앱만 교체했다. 이미지 태그 외 `.env` 값·모드는 보존했다. 첫 검사 도구의 후보 경로 변수 이름 충돌은 앱 교체 전 중단됐으며 정확한 도구 해시를 확인한 세 줄 수정과 회귀 테스트15개 통과 후 재개했다. Git archive의 기존 CRLF 자산은 실제 배포 바이트를 기준으로 SHA를 고정했고, 소스의 LF와 줄바꿈 외 차이가 없음을 확인했다.

공개 계산 상세는 5사원×2026-07~11의25월 조회에도 저장된 결과 ID가 없어 실제 열람을 검증하지 못했다. 조회는 결과를 저장하지 않으며 이 검사를 위해 재계산하거나 업무 자료를 만들지 않았다. 로그인/로그아웃 외 업무 POST는 0이다.

## 상세 정보표 후속 보완

추가 검토에서 계산 근거의 일반 DL이 세로 텍스트로 남아 있어 `WorkflowPages.tsx`의 계산/감사 상세에 이름 있는 section과 `RecordDetails.css`를 적용했다. 업무 값·분기·링크·미확정/근거 누락 안내·API는 유지했다. 계산 근거는 기존 모달, 감사 상세는 기존 전체페이지이다.

개발 커밋 `2421f6c`의 신규2개 테스트 실패를 확인한 뒤 기존29개와 함께31개 통과했고 타입·빌드도 통과했다. 두 테마×PC1440/모바일390의 계산 모달4·감사 상세4=8조합은 라벨/값 배경·정렬·넘침·키보드 검사를 통과했다. 독립 Hallmark 화면 검토도 추가 문제 없이 통과했다. 증거는 `target/hallmark-tools/record-details-artifacts/verification.json`과 화면16장이다. 공개 저장 결과 부재는 이 합성 검증으로 대체하여 실제 검증 완료로 표현하지 않는다.

추가로 저장된 실행·36자 ID·원천/유효 출퇴근·근로/휴게 확인을 갖춘 합성 근거를 구성해 계산 모달 두 테마×2폭4조합을 검증했다. 긴 라벨과 사건표에도 문서/DL 가로 넘침·JS 오류·업무 POST가 없었고 Escape/초점 복원이 정상이다. `target/hallmark-tools/record-complete-artifacts/verification.json`과 구간별16장에 기록했다.

## 추가 공개 후보와 11월5일 중단 이력

공개 전용 브랜치에 `40816d1af0f341de6a30a9d2cd65583f6243cef6`으로 통합하고 일반 Push를 마쳤다. 공개의 기존 AG Grid·공유 계정 보호를 보존한 관련34개 테스트와 타입·빌드가 통과했다. 변경은 `WorkflowPages.tsx`, `RecordDetails.css`, `RecordDetails.test.tsx` 3파일이며 Java·DB·패키지·배포 설정 변경은 없다.

후속 패킷은 `/srv/worklog/.local/workspace-surfaces-modal-20261105`만 대상으로 준비했다. 기존 공개 `2da93d8`의 소스556파일·이미지·JAR를 고정하고, 새 소스558파일·정확3파일 변경·전체 UI자산4개 해시·실제 변경 자산2개를 검증했다. 보존 가드17개와 전송 viewer3개가 통과했고 Windows symlink 권한 부족으로 viewer의 symlink 테스트1개만 건너뛰었다. 전송 완료/부분 전송/잘못된 길이/손상된 접두부 재진입 검사4개도 통과했다. 패킷과 검증 근거는 `target/workspace-surfaces-modal-deploy-20261105/`에 보존했다.

11월5일 서버 전송은 1~3번 조각의 총60,000바이트 수신까지 확인했다. 4번 조각의 수신 여부는 확인하지 못했다. 이후 CUA 연결 복원과 자동 권한 검토가 반복해서 응답 시간 초과되어 추가 실행을 중단했다. 이는 실제 안전성 거부 판정이 아니다. 당시 후속 receiver·이미지 빌드·앱 교체·최종28항목 검사는 실행하지 않았으며 공개 버전은 `2da93d8`이었다. 재개용 `console-complete-transfer.txt`는 60,000바이트이면 검증된 마지막 조각만 추가하고, 이미77,220바이트이면 전체 해시만 확인한다. 다른 길이나 손상은 거부하며 기존 파일을 덮어쓰거나 삭제하지 않는다.

중단 후 18:36:32~34 서울에 WorkLog health200, demo health200, portfolio200, PriceSys401의 기대 응답을 다시 확인했다. `current-sites-readonly.json`에 시각과 결과를 남겼다. CUA 연결 실패 때문에 마지막 실제 브라우저 로그아웃·viewport 원복·관리 셸 exit는 확인하지 못했다. 마지막 관측은 실제 브라우저의 test1 로그인과 콘솔5의 root 로그인 상태였으며 당시 종료 확인이 남았다. 별도 HTTP 검사 세션의 로그아웃 성공과 구분한다.

당시 남긴 재개 순서는 `target/workspace-surfaces-modal-deploy-20261105/console-complete-transfer.txt`(60,000/77,220바이트만 허용) → receiver → before → build → artifact → layer-proof → app → 공개28항목·모달·브라우저 검증 → final → summary였다. 11월5일에는 이 경로를 기록한 뒤 서버 명령을 추가 실행하지 않았다.

## 11월6일 공개 후속 반영

사용자의 재개 승인으로 설치 없이 Hostinger 콘솔에 다시 연결했다. 개발·공개 HEAD와 origin, 기존 패킷·ops·payload 해시, `40816d1` 소스3파일 및 자산4개의 바이트 일치를 확인했다. 로컬 소스 변경·재빌드 없이 준비된 후보를 사용했으며, 서버에서 before·build·artifact·layer-proof·app·final 단계의 PASS를 확인했다. 실제 공개 소스는 `40816d1af0f341de6a30a9d2cd65583f6243cef6`, 이미지 ID는 `sha256:b0affb6fcb6f2410e90db4557af71c8fe102afb92b6ab88f377008de99a1653e`, JAR SHA-256은 `868094a10012bf7565ed6456c6a9a1fca28d724fc7d0f6291cfa6c6c35937df6`이다. 최종 기록은 2026-11-06 11:01:36 서울(`02:01:36.581248+00:00`)이다.

| 검증 | 실제 결과 |
| --- | --- |
| 공개 HTTP | 후보 자산4개 SHA·로그인/권한/보안/읽기28항목 PASS, exit0 |
| 공개 모달 GET | 17개 PASS, 저장 계산 결과가 없어 계산 근거1개 SKIP, exit0 |
| 실제 공개 PC | 내 정보7탭×차콜/블루, 신청 모달 두 테마·Escape 닫기/초점 복귀, `REQUEST_APPROVE` 감사 상세 두 테마 확인 |
| 동일 공개 자산 합성 UI | SHA가 일치하는 공개 자산으로 계산 모달·감사 상세8조합과 완전 계산 근거4조합, 총12조합 확인 |
| 배포 자료 보존 | 앱 교체 전후48표446행 전체 값·스키마·권한 동일, V17 유지 |
| 감사 조회 후 자료 보존 | 기존446행 전부 동일, 감사 목록/상세 조회 로그 각1행만 추가: 전체448행·감사11→13행, 스키마·권한 동일 |
| 배포 산출물 | JAR538항목 중 React CSS/JS2개만 변경, 나머지536개 동일. OS/JRE12계층과 revision 외 실행 설정 동일 |
| 기존 서비스 보존 | 기존42개 컨테이너 상태·Caddy 동일, demo `/store/health`200·portfolio200·PriceSys401·WorkLog health200 유지 |
| 전용 설정 경계 | `/srv/worklog`, Compose `worklog-demo`의 앱만 교체, `.env`는 이미지 태그 외 값·모드 보존 |

감사 목록과 상세 GET은 기존 정책에 따라 `AUDIT_VIEW`·`AUDIT_DETAIL_VIEW`를 각각 기록한다. 실제 화면은 목록1회·상세1회만 조회하고 테마는 클라이언트에서 전환했다. 별도 읽기 전용 증거 명령은 두 행의 행위·대상·사유·주체를 확인한 뒤 이를 제외한 감사 전체 해시가 앱 교체 직후와 같음을 확인했다. 나머지47개 표와 모든 스키마·권한도 기존 완전 일치 검사로 비교했다. 감사 증거 기록은 2026-11-06 11:01:01 서울(`02:01:01.847474+00:00`)이며 원본 `audit-ui-data-proof.json`, `data-after-ui-audit.json`과 서버 최종 요약을 전용 배포 디렉터리에 보존했다. 최종 요약의446행은 앱 교체 중 정지 상태의 전후 비교이며, UI 조회 후 현재448행 검증과 구분한다. 검증을 위한 재계산·업무 자료 생성·초기화·마이그레이션·seed 재실행은 하지 않았다.

공개 모달 GET 검사에서 계산 결과 ID가 없어 실제 계산 상세를 열지 못했다. 같은 공개 자산과 합성 자료로 계산 근거의 값·표·긴 ID·모바일 배치·Escape/초점 복귀를 확인한 결과와 구분한다. 또한 이번 실제 공개 탭에는 IAB viewport override가 적용되지 않아 실제 모바일 검증으로 세지 않는다. 1440/390px 두 테마의 합성12조합은 `target/hallmark-tools/public-record-details-artifacts-20261106/verification.json`, `public-record-complete-artifacts-20261106/verification.json`에, 자산 일치는 `public-record-evidence-20261106/asset-verification.json`에 남겼다.

공개 HTTP 검사의 첫 sandbox 실행은 health 단계에서 실패해 완료한 검사는0개였으며, 승인된 외부 연결 실행에서는28항목이 통과했다. 실제 실패 기록과 성공 로그를 모두 보존했다. 최종 로그는 `target/workspace-surfaces-modal-deploy-20261105/public-smoke-final-20261106-network.log`와 `public-modal-get-final-20261106.log`다. 두 검사 모두 감사 GET을 제외했고 로그인/로그아웃 외 업무 POST는0, 검사 세션 로그아웃을 확인했다. 감사 자료 보존 증거 명령의 로컬 경계 테스트9개도 통과했다.

마지막으로 실제 공개 브라우저 로그아웃(`/demo?logout`), VPS 관리 셸 exit 후 Ubuntu login prompt, viewport 원복을 확인했다. 전송·중복 모바일 탭을 닫고 이번 작업의 전송 viewer18097과 합성 미리보기18094~18096을 종료했다. 기존 preview18089와 배포 패킷·검증 증거 파일은 보존했다.
