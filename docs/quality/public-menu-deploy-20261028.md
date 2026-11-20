# 최신 완료 메뉴 공개 배포 검증

기준일: 2026-10-28, Asia/Seoul. 사용자는 최신 완료 메뉴의 공개 보안·테스트 확인과 WorkLog 전용 배포를 승인했다. [후속 배포 절차](../development/worklog-public-updates.md)와 [실행 계획](../superpowers/plans/2026-10-28-public-menu-deploy.md)을 따른다.

## 통합 소스와 범위

- 개발 완료 커밋 `ecf6dfc`(지침 포함 `6906e8c`)를 공개 배포 브랜치에 통합한 런타임 소스는 `efd13eac54e570d9e0dc9e2d1b0845ba9ee5795c`다. 내 정보·대표/관리자 회사 조회·조직/사원 시트·조직장·권한·결재선과 완료 결재를 포함한다. 원본 개발 폴더의 작성 중인 코드 관리/V14는 포함하지 않았다.
- 새 사원 일괄 저장에 기존 `PublicDemoIdentityGuard`를 재사용했다. 공유 사원 비활성화·신규 행 혼합 배치·대문자 UUID의 HTTP 우회를 수정 전 RED로 재현하고 수정 후 관련38개를 통과했다. 로컬 데모 편집 동작도 유지했다.
- 공개 단독 프로필·운영자 비활성·가상 자료 안내·CSRF·Secure 쿠키·tenant/RLS/현재 권한 검사를 유지했다. 독립 코드 검토에서 추가 P1/P2 차단 결함을 찾지 못했다.

## 로컬 검증

| 항목 | 실제 결과 |
| --- | --- |
| Java 전체 | 단위113 + 전용 PostgreSQL 통합511 = **624개**, 실패/오류/skip0, Maven verify 성공 |
| 프론트 | **153개/12파일**, TypeScript·Vite 빌드 성공 |
| Python | **69개**, 실패0 |
| 기존 공개 앱 | 배포 전 기존36개 보안/로그인 검사 통과 |

프로세스별 프로젝트 JDK25를 사용했다. 기본 로컬 앱·DB와 개발 폴더의 미완성 변경은 수정하지 않았다. 로그·검증 도구·전송 자료는 ignored `target/menu-deploy-20261028/`에 보관한다.

## 서버 준비

기존 사이트4개의 기대 응답, 기존 컨테이너8개의 이름/ID/시작 시각/이미지와 Caddy 해시를 기록했다. 여유 디스크23GiB·가용 메모리 약6.7GiB를 확인했다. 정상 새 Webconsole root 세션을 사용하며 임시 Docker socket 관리 연결은 만들지 않았다.

첫 delta 후보에서 기존 파일의 해시 차이를 검출해 빌드 시작 전에 중단했다. 기존 소스를 복사하는 방식을 전체 Git 빌드 입력의 빈 폴더 추출로 바꾸고 추가 파일도 차단했다. 최종 아카이브372,626바이트·SHA256 `a3a600ac65d9eb182ec8d5b0a145b518bb16db19f1ca9920dfa6bf950192a215`이며 라이선스/고지 포함216파일의 집합·해시 일치를 확인했다. 이 준비 실패는 제품 테스트 실패나 성공 배포로 집계하지 않는다.

기존 WorkLog 전용 빌더(메모리1.5GiB·CPU0.75)의 빌드는 종료0이며 해당 빌더는 다시 중지됐다. 앱은 `sha256:9d0559e6adf86a9e11cf12dad5c8184d2705373027c5abc696430a707fc34afe`, migrator는 `sha256:fae7a119c916a2c39207220fb382383345763ee2f02a1025155ec889ad08d482`다. 둘 다 user10001:10001·revision label `efd13eac54e570d9e0dc9e2d1b0845ba9ee5795c`를 확인했다.

## 백업 복원과 V13 시험

WorkLog 앱만 중지한 구간에서 V10 custom dump와 manifest를 만들고 기존 앱을 재개했다. 같은 전용 PostgreSQL 컨테이너의 별도 `worklog_demo_restore_efd13ea`에 실제 복원했다. DB owner/ACL·역할·schema ACL, 기존34개 업무 테이블의 원래 열/행 수/SHA·RLS/테이블 및 열 권한·정책·제약·Flyway 이력을 비교했다. 원본 DB에 복원하지 않았다.

비교 도구는 복원 표현과 PG18 제약 메타데이터의 두 차이를 먼저 실패로 검출했다. dump/restore의 varchar 배열→text 배열 캐스트가 각 원소의 text 캐스트로 재작성된 5개 복합 제약·3개 정책은 정확한 부분식만 정규화했다. 값·순서·인용·공백·NULL/AND/OR·ANY/ALL·역할 변경은 같은 것으로 취급하지 않는다. 이 배열 변환의 근거는 [PostgreSQL18 배열 생성자 설명](https://www.postgresql.org/docs/18/sql-expressions.html#SQL-SYNTAX-ARRAY-CONSTRUCTORS)이다. 이어 PG18이 catalog에 별도 저장하는 두 legacy NOT NULL 제약의 삭제도 V13의 두 열 nullable 변경과 함께 필수 차이로 검사했다. 나머지 제약 삭제는 허용하지 않았다. 보완 도구의 RED→GREEN·selfcheck8개와 실제 exact 비교를 통과했다.

복원 DB의 V11~V13 migration은 종료0이며 기존34→신규5 포함39개 업무 테이블의 업그레이드 보존 비교도 통과했다. 신규 테이블은0행이었다. public Flyway 테이블은 업무 테이블 수에서 제외한다. 원시 manifest·덤프·진단 차이 파일은 서버의 접근 제한 evidence 경로에 보존했다.

## 정식 반영과 공개 검증

2026-10-28 **16:52 KST**, WorkLog 앱만 중지하고 최종 V10 백업·manifest를 만든 뒤 별도 migrator로 V11~V13을 적용했다. 종료0·V13과 기존34개 업무 테이블의 모든 기존 열/행 수/SHA·RLS/권한·정책 보존을 확인했다. 두 legacy 열의 nullable 변경·NOT NULL 제약 삭제·UPDATE 권한 회수와 새 역할 CHECK/테이블/제약은 의도한 차이로 검사했다. 리허설과 정식 DB의 전체 열·제약·정책·권한·Flyway 버전/checksum 비교도 통과했다.

이전 이미지·소스·서버 환경 파일을 접근 제한 경로에 보존했다. 환경 파일은 배포 태그만 `efd13ea`로 바뀌었고 나머지 바이트와0600 권한을 유지했다. 최종 dump205,173바이트는0600이며 TOC 검사를 통과했다. 실제 독립 복원 시험 대상은 앞서 만든 rehearsal.dump이며, 최종 dump를 정식 DB에 복원하지 않았다.

| 항목 | 실제 결과 |
| --- | --- |
| 앱 교체 | `worklog-demo`의 app만 `--no-deps --no-build --pull never`로 교체, health UP |
| 실행 일치 | 앱 ID `901a72331093d4f33b3747d51026c748f57ae5a728957d70a5aebe2e707ee532`, 시작16:52:09 KST, 위 앱 이미지 ID와 일치 |
| 격리 | app user10001:10001·readonly·loopback18081, DB userpostgres·readonly·게시 포트 없음 |
| DB 보존 | DB 컨테이너/이미지/시작 시각과 `worklog-demo-postgres-data` 보존, DB 재시작 없음 |
| 기존 서비스 | 사전 기록8개 컨테이너의 이름/ID/시작 시각/이미지 일치. PriceSys ID `a4d01bd0fed1`·demo `aa1f4a79497f`를16:58 KST에도 재확인 |
| 기존 HTTP | PriceSys401(인증 요구), demo health200, portfolio200, WorkLog health200 |
| Caddy/DNS | 변경 없음. Caddy SHA256 `da4f8a24e779169fe8522eba7d99883612149422fad6acc8e9232aa840b0e059` 유지 |
| 공개 보안/메뉴 | 기본36+추가27=**63항목** 통과. 새8개 메뉴의 익명 차단/로그인 page 계약, 공유 UUID bulk403, 새 쓰기 CSRF403, APPROVER 자동 부여 없음 포함 |
| 네트워크 경계 | **4항목** 통과: HTTP301 경로/쿼리, 위조 scheme의 HSTS/health, 위조 host의 고정 origin 리다이렉트, 외부 TCP18081 연결 거부/timeout |
| 실제 화면 | 사원 시트·결재선·내 정보 각각1280px/390px 총**6회 관측**, 본문 가로 넘침0·콘솔 오류0. 기본 viewport 복원 |
| 관리 연결 | 정상 Webconsole로 실행. 기존 임시 Docker 관리 컨테이너는 mount0·network none·exited를 재확인. 새 관리 연결 생성 없음 |

공개 검사 수는 검증 항목 수이며 HTTP 요청 수와 같다고 주장하지 않는다. 새 bulk 보호 검사는 가드가 빠져도 필수 사번 오류로 쓰기가 차단되는 입력을 사용했다. 초기 음수 버전 probe는 일반 폼 검사가 먼저422를 반환한다는 검토에 따라 수정했고 정상 앱의403을 실제 확인했다. Python3.13의 추가 네트워크 검사기는 인증서 형식 검사(`Missing Authority Key Identifier`)에서 멈췄다. 검증을 끄거나 신뢰 저장소를 바꾸지 않고 Windows .NET 기본 TLS 검증으로4개를 다시 실행해 통과했다.

Compose가 보존한 일회성 migrate/bootstrap 컨테이너를 orphan으로 안내했으나 정리 옵션을 실행하지 않았다. 새 정식/리허설 migrator는 둘 다 종료0이며 전용 빌더는 빌드 종료0 후 중지 상태다. 다른 프로젝트·볼륨·백업을 삭제하지 않았다.

## 범위와 남은 검증

모든 방문자가 기존 가상 자료를 공유한다. bootstrap/sample 재실행·DB 초기화·기존 계정 역할 변경·허용 IP 자동 개방은 하지 않았다. 실제 메일·운영자 공개·실고객 자료는 계속 제외한다. 공개 서버에서 실제 출퇴근·신청/승인·Excel을 쓰는 전체 시나리오는 실행하지 않았으며 로컬624개 검증과 구분한다.

위조 XFF 후 앱의 최종 client IP를 직접 관측하지는 않았다. 신규5개 테이블의 모든 제약을 별도 기대값으로 열거한 독립 검사가 아니라, 고정 소스·제품 통합 테스트와 리허설/정식 DB 정의의 전체 비교를 함께 사용했다. 복원은 같은 전용 클러스터와 기존 전역 역할을 전제로 하며 서버 유실·전역 역할 재생성·RPO/RTO 검증은 아니다. 새 장시간 부하·전체 이미지/의존성 취약점 재검사·상시 자동 배포/초기화/백업 스케줄은 추가하지 않았다. 기존 보안 보고서의 잔여 항목을 이번 배포로 해결했다고 확대하지 않는다.

실행 도구·검증 JSON·PC/모바일 화면과 최종 서버 관측은 ignored `target/menu-deploy-20261028/`에, 서버 덤프·원시 manifest·이전 소스/환경은 root만 접근하는 `/srv/worklog/.local/menu-v13.gszU4E`에 보존했다. 실제 비밀값·덤프·세션 URL을 Git에 넣지 않는다.
