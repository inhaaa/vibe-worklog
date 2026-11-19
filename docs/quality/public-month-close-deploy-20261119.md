# WorkLog 공개 데모 월 마감 배포 검증

2026년 11월 19일 운영 백업의 독립 복원과 V30 전환 리허설을 마쳤다. 운영 앱 교체 직전에 Hostinger 웹 터미널 연결이 만료됐다. 이후 공개 화면 자산에서 기존 앱 `9a0e35a`가 서비스 중임을 다시 확인했다. 실제 교체와 공개 PC·모바일 점검은 대기 중이다.

| 확인 항목 | 결과 |
| --- | --- |
| 배포 대상 | `worklog.inhaaa.com` 전용 Compose `worklog-demo`, DB `worklog_demo` |
| 소스 | `368f6a029ca96b6cb0e8a1796ba1c043a2a697f5`, Git 트리 `d73c78371b81b54c076a5f28d2d8adf0d2de933f` |
| 운영 기준 | 앱 `9a0e35a`, Flyway V18, 업무 테이블 49개 |
| 외부 사전 점검 | HTTPS 147건 통과. WorkLog·기존 데모·포트폴리오 200, PriceSys 401 |
| 빌드 | 검증본의 880개 파일을 제한된 서버 빌더의 앱·마이그레이션 이미지 각각과 대조했다. 실행 클래스, 라이브러리, 화면 번들과 자산을 포함한다. |
| 복원 리허설 | `run-20261119052617-368f6a0`에서 전용 백업 복원, Flyway V18→V30, 49개 기존 테이블 자료와 새 테이블·권한·테넌트 정책의 대조에 성공했다. 복원본은 외부 연결이 차단돼 있다. |

정본 JAR는 [월 마감 기능 검증](attendance-month-close-20261119.md)에 사용한 Windows 빌드다. Linux 빌드는 V25 SQL, 기본 스타일시트, 고지·라이선스 2개, Maven POM의 줄바꿈이 LF다. 이 다섯 파일은 CRLF로 복원한 바이트를 대조하고 나머지 875개는 원래 바이트를 대조한다. 두 서버 이미지의 파일별 SHA-256을 정렬한 목록의 지문은 `8a84fdf1f887e12f3ba668c7bec148e7e7434b7706c52b8da2996866b1808baa`다. [정본 파일별 해시](evidence/2026-11-19-month-close-jar.json)

## 운영 전환 재개

인증된 서버 터미널에서 해당 릴리스의 `ready.json`, `migration-started.json`, `complete.json` 존재 여부와 현재 앱·스키마를 확인한다. 두 후보 이미지의 JAR에 [검사기](../../deploy/worklog/verify_v30_artifact.py)의 `check app.jar migrate.jar --linux-lf --manifest certified.json`을 실행한다. 확인한 실제 JAR 지문과 리허설 지문이 일치하면 [배포 드라이버](../../deploy/worklog/release_v30.py)의 `apply run-20261119052617-368f6a0`로 이어간다. 앱 정지 후의 새 백업을 두 번째 독립 DB에 복원해 대조한 다음 운영 마이그레이션을 시작한다. 전환 후 `verify`, 공개 HTTPS와 담당자 PC·모바일 확인 결과를 기록한다.
