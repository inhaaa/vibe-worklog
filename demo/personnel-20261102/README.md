# 공개 데모 가상 인사정보 1회 등록

기준일은 서울 `2026-11-02`다. 기존 공개 가상 사원 4명의 **새 V16 인사정보와 사진만** 채운다. 실제 인물·기업 자료를 사용하지 않았으며 사진 생성 근거는 [prompts.md](prompts.md), 입력·원본/정규화 사진 SHA256은 [manifest.json](manifest.json)에 고정한다.

| 사번 | 합성 생년월일 | 직급 | 직책 | 직무 | 표시 상태 |
| --- | --- | --- | --- | --- | --- |
| LOCAL-DEMO | 1988-07-14 | 책임 | 운영팀장 | 인사 운영 | WORKING |
| DEMO-001 | 1992-03-11 | 선임 | 팀원 | 서비스 기획 | WORKING |
| DEMO-002 | 1986-09-22 | 수석 | 개발팀장 | 소프트웨어 개발 | ON_LEAVE |
| DEMO-003 | 1996-12-05 | 사원 | 팀원 | 품질 관리 | SUSPENDED |

인사 상태는 표시용이며 계정 활성·역할·결재·일정·연차·근태 계산 효력을 바꾸지 않는다. 기존 이름·사번·조직·입퇴사일·employee.version·account.auth_version과 모든 기존 행은 수정하지 않는다. bootstrap과 역사적 sample-data SQL은 실행하지 않는다.

## 고정 계약

- 진입은 `public-demo-personnel-fill` 명시 CLI뿐이다. Spring 웹을 시작하지 않고 자동 실행·공개 seed API·프로필 변경·권한 추가를 만들지 않는다. 일반 `EmployeePersonnelService`와 `PublicDemoIdentityGuard`의 공유 담당자 변경 금지는 유지한다.
- `public-demo` 단독 환경과 기존 `TNA_APP_DB_PASSWORD`를 사용한다. DB는 `worklog_demo`, 역할은 비관리 `tna_app`만 허용한다. 주소는 기존 전용 private network의 `db:5432` 또는 명시적인 `127.0.0.1`/`[::1]`+포트만 허용한다. JDBC 옵션·다른 DB/호스트·관리 역할은 거부한다. 서버 비밀값은 인수·출력·문서에 넣지 않는다.
- tenant `dddddddd-dddd-4ddd-8ddd-dddddddddddd`와 manifest의 사원 4 UUID·기존 이름/사번·2개 가상 조직·현재 소속·공유 담당자 계정/현재 활성 역할·초기 합성 등록 감사를 잠금 안에서 대조한다. 계정의 현재 `auth_version`으로 기존 관리자 검증을 수행하며 값을 변경하지 않는다.
- manifest의 기준일, CLI 예상일, 실행 시 서울 오늘은 모두 `2026-11-02`여야 한다. 다른 날짜에는 이 명령을 재사용하지 않는다. 모든 인사 `expectedVersion`은 `0`이다. 처음 실행할 때 인사 행과 상태 이력이 모두 없어야 하며 일부라도 있으면 전부 거부한다.
- 원본 파일의 source SHA256을 대조하고 `EmployeePhoto.decode`로 PNG/JPEG 크기·픽셀을 검사·재인코딩한다. 그 결과 SHA256도 manifest의 normalized SHA256과 같아야 한다. 저장된 bytea SHA256을 같은 값으로 다시 확인한다. PNG/JPEG 원본과 저장 결과는 각각 최대128KiB·2048px·400만 픽셀 제한을 따른다. 고정 상대경로 4개만 읽으며 manifest·사진·photos 디렉터리의 심볼릭 링크를 허용하지 않는다.
- 기존 `TenantTransactions`와 tenant 독점 advisory lock, `OrgCommands.executeBatch`의 현재 관리자·명령 해시·감사를 재사용한다. 정상 공개 API가 공유 담당자를 의도적으로 거부하므로, 고정 자료의 최초 INSERT만 전용 CLI에 둔다. 업무 서비스의 공개 보호를 우회하도록 변경하지 않는다.
- 한 트랜잭션에서 `employee_personnel` 4건(version1), 추가 전용 `employee_employment_status` 4건(revision1·서울 당일), 행감사4+단위감사1만 INSERT한다. UPDATE/DELETE·새 schema·권한 변경은 없다. 감사에는 생년월일·사진 원문을 넣지 않고 등록 유무·버전·상태·적용일만 기록한다. action은 `PUBLIC_DEMO_PERSONNEL_FILL_ROW` 4건과 `PUBLIC_DEMO_PERSONNEL_FILL` 1건이며 일반 사용자 변경 이력과 구분한다.
- 고정 명령 `dddddddd-dddd-4ddd-8ddd-dddddddd0911`을 사용한다. 같은 날 재실행은 manifest 해시·인사/사진 bytea 해시·상태/버전·행감사/단위감사가 모두 정확히 일치할 때만 무변경(no-op)이다. 부분 등록·기존 인사정보·추가 상태 이력·사진 변경·명령 충돌은 복구/덮어쓰기 없이 전부 거부한다. 중간 실패는 등록과 감사 전체를 롤백한다.

## 실행 경계

실행 전에 검증된 소스 아카이브 SHA256과 이미지 revision/ID를 확인하고 해당 아카이브의 이 디렉터리를 읽기 전용으로 전달한다. manifest는 코드의 고정 메타데이터와 사진 두 SHA를 대조하지만 신뢰할 아카이브 자체를 대신 인증하지 않는다. 기존 전용 DB의 백업·전체 기존 행/권한 스냅샷을 보존하고 배포 전후 동일성을 대조한다. 다른 서비스나 전역 환경은 변경하지 않는다.

기존 `/srv/worklog/source`가 검증한 후보 소스·이미지를 가리키고 기존 Compose 환경이 보존된 상태에서, 등록 담당자가 아래 단발 명령을 실행한다. 이 문서는 실제 공개 DB 등록 완료를 뜻하지 않는다.

```sh
docker compose -p worklog-demo \
  --env-file /srv/worklog/source/deploy/worklog/.env \
  -f /srv/worklog/source/deploy/worklog/compose.yaml \
  run --no-deps --name worklog-demo-personnel-fill-20261102 \
  -v /srv/worklog/source/demo/personnel-20261102:/demo-personnel:ro \
  app public-demo-personnel-fill \
  worklog-demo-personnel-20261102 /demo-personnel 2026-11-02
```

명령은 기존 app 서비스의 전용 네트워크·앱 DB 역할을 사용한다. 웹 포트를 게시하거나 새 DB 포트를 열 필요가 없다. 종료 컨테이너는 증거로 보존하며 무변경 재실행 검증에는 별도 고정 이름을 사용한다. `등록했습니다` 또는 `정확히 일치합니다. 변경하지 않았습니다`의 종료0과 별도 자료 검증을 함께 확인한다. 실패 시 기존 인사자료를 지우거나 초기화해 재시도하지 않는다.

등록 후 공개 로그인·공통 사원 헤더/인사 화면의 4명 직급/직책/직무/상태·인증된 사진4개의 Content-Type/no-store/nosniff·공유 담당자 POST403·기존 자료/권한·다른 서비스 보존을 별도 확인한다. 로컬 테스트는 전용 합성 컨테이너만 사용하며 실제 공개 등록·화면 확인은 배포 결과에 따로 기록한다.
