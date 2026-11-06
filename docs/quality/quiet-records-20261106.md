# 간결한 표·정보 화면 검증

2026-11-06 Asia/Seoul. [표시 계약](../design/quiet-records.md)과 [실행 계획](../superpowers/plans/2026-11-06-quiet-records.md)을 따른다.

## 구현과 개발 검증

표·내 정보·직원 요약·신청·계산 근거·감사 상세·모달을 중립 머리글과 항목명, 흰 값 영역, 얇은 가로선으로 통일했다. 내 정보는 밑줄 탭과 무배경 그룹 제목을 사용한다. 차콜/블루의 선택·주요 버튼 색, 44px 조작 영역, 정보값16px, 기존 내용과 동작을 보존한다. CSS9개만 제품 변경이며 API·Java·SQL·의존성·TSX 변경은 없다.

| 확인 | 실제 결과 |
| --- | --- |
| 기존 계약 선행 확인 | 내 정보9개·상세 관련95개 통과 후 CSS 수정 |
| 전체 React | 28파일321개 통과 (`vitest run --maxWorkers=2 --testTimeout=15000`) |
| 타입·빌드 | `tsc --noEmit`, Vite build 통과. 기존500kB 번들 안내 유지 |
| 두 테마·내 정보7탭·주요 모달 | 합성 화면74항목 통과, 1440/390/320px |
| 계산·감사 상세 | 합성18조합 통과. 계산 완료/미확정·원천 근거·긴 ID 포함 |
| 모바일 신청 단계 | 320/390px × 두 테마4조합 단어 경계 줄바꿈 확인 |
| 텍스트 대비 | 24조합1020표본, 최소5.20:1, 실패0 |
| Hallmark | 정적57파일·render1URL, FAIL0·WARN0·ANSWERED6 |
| 독립 시각 검토 | ship. 모바일 단계 문구1건 발견·수정·재확인 |

처음 PowerShell의 npm 인자 전달로 worker/timeout 옵션이 전달되지 않은 실행은 4건 시간 초과했다. Node로 Vitest를 직접 실행해 321개 전체 통과했다. 관련 테스트의 초점1건 시간 초과도 단독 및 최종 전체 실행에서 통과했다. 테스트 코드·제품 제한은 변경하지 않았다.

마지막 CSS 보완은 계산 미확정 코드의 줄바꿈과 모바일 단계의 `word-break: keep-all`이다. 보완 후 타입·빌드 및 해당22조합을 확인했다. 전체321개/74항목/대비 검사는 보완 전 결과이며 이를 보완 후 재실행으로 표현하지 않는다. 개발 JS SHA256은 `89d5d5e56828ea461660fa65c26a45974415f9e1ed230b9ffb82f2b76103f6a8`로 전후 동일하다.

합성 미리보기는 Git 제외 `target/hallmark-tools/simple-tables-20261106`에 전후20쌍과 검증 JSON을 보관한다. 실제 DB·공개 서버를 사용하지 않았으며 JS 오류0·업무 POST0이다. 공개 보안/AG Grid 통합과 실제 배포 결과는 후속 확인 후 아래에 별도로 기록한다. 기존 untracked 공통 시트 설계 문서는 보존한다.

## 공개 통합과 배포 준비

개발 `a859ccc`를 공개 보안·AG Grid가 있는 별도 작업트리에 `91d6c5606531d74860617dbc34e6d59dbc2a13c8`로 통합했고 두 브랜치에 일반 Push했다. 공개 전체31파일404개·타입·빌드가 통과했다. 공개 자산을 고정한 합성 화면84항목과 AG Grid6조합도 통과했으며 JS 오류·업무 POST·외부 요청은0이다. 개발 검사와 실제 공개 서버 검사는 구분한다.

공개 React CSS SHA256은 `1e92357295aaa0758d113cb60e5d9ec056ede76d81599288d70ef461dc52add1`이다. JS `2c584d641df2549c4cf7a764e07aaf18ec693cd647e3168acf6ca46b8bff7289`, sheet CSS `e5dfb48365fa1ab98e26f6494dbe0da515cdeda8dd4ee9634b8e2f130b22e0d8`, 기존 app CSS `c5f7966cdbde8f1711e09a4fbc5e3a3c045230c0d2ac7312ea40a6738828ec1c`는 직전 공개 자산과 동일하다. 공개 합성 검증은 `target/hallmark-tools/simple-tables-20261106/public-surface/browser-check.json`, `public-grid/grid-theme-check.json`에 보관한다.

설치 없이 Hostinger 웹 콘솔을 새로 연결했으나 이전 관리 셸이 종료되어 root 비밀번호 입력이 필요했다. 사용자에게 콘솔 내 직접 로그인을 요청한 상태이며, 이번 변경의 전송·빌드·앱 교체·실제 공개 HTTP 검사는 아직 실행하지 않았다. 서버·DB·다른 서비스를 변경하지 않았으며 마지막 확인된 공개 소스는 `40816d1`이다. 로그인 대기를 배포 완료로 표현하지 않는다.

재개 패킷은 Git 제외 `target/quiet-records-deploy-20261106`에 고정했다. 직전 실제 소스558파일 대비561파일·정확20파일 변경(제품 CSS9개+문서/기록11개)·신규 문서3개와 전체 파일 모드를 검증했다. 가드18개 통과, 전송 viewer3개 통과·Windows symlink 권한에 따른1개 SKIP, 전송 내용22파일과 콘솔 Python 구문을 확인했다. 패킷86,736바이트 SHA256은 `05be388ced893f6d01f9aa493aa001de2ff22b891ba22863c5e368c5ee0eb8cf`이며 소스 SHA는 이후 문서 커밋과 구분한다.

서버 대상은 `/srv/worklog/.local/quiet-records-20261106`이다. 재개 순서는 전송01~06 → receiver → before → build → artifact → layer-proof → app → 공개 HTTP28항목/모달 GET/실제 두 테마 → final → summary다. 앱 교체 전에 JAR538항목 중 React CSS1개만 변경, 이전 OS/JRE 계층·런타임 설정 보존을 강제한다. WorkLog 앱만 교체하고 V17·48표 전체 값/스키마/권한·Caddy·다른 서비스와 `.env`의 이미지 태그 외 값을 전후 비교한다. 이전 공개 UI 조회가 남긴 감사 로그도 현재 기준으로 포함하며 자료를 초기화하지 않는다.

Java·DB 통합/부하/취약점 검사는 CSS 변경에서 재실행하지 않았다. 공개 계산 상세에 저장된 결과가 없었던 이전 제한도 유지하며 검증용 계산·업무 자료를 만들지 않는다.
