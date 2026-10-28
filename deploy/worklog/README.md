# WorkLog 공개 합성 데모

아래 자료 준비 명령은 **최초 배포용**이다. 이미 자료가 있는 공개 데모의 메뉴 갱신은 [후속 배포 절차](../../docs/development/worklog-public-updates.md)를 따른다. bootstrap·sample-data를 재실행하지 않는다. 실제 첫 배포 결과는 [검증 보고서](../../docs/quality/hostinger-public-demo.md)에 기록한다.

`worklog.inhaaa.com` 전용 Compose 프로젝트는 `worklog-demo`, DB는 `worklog_demo`다. 기존 로컬 Compose와 다른 서비스의 DB·볼륨·포트를 재사용하지 않는다. 앱만 `127.0.0.1:18081`로 게시하고 DB는 게시하지 않는다. 원래 저장소 루트의 Dockerfile과 no-XML PostgreSQL 빌드를 사용한다. 모든 방문자는 같은 가상 자료를 공유하며 실제 개인정보·고객 자료를 넣지 않는다.

이 문서는 배포 절차이며 배포·HTTPS·실제 격리 검증의 완료 기록이 아니다. 서버의 기존 서비스 자원과 포트, Caddy bind 주소를 먼저 확인한다. Compose는 CPU·메모리·PID·로그를 제한하며 DB 데이터는 `worklog-demo-postgres-data`에 보존한다. 제한 합계와 이미지 빌드에 필요한 여유 자원은 서버에서 별도 확인한다.

## 환경 준비

서버 배포 디렉터리에서 `.env.example`을 `.env`로 복사하고 권한을 `600`으로 제한한다. 역할마다 서로 다른 새 난수 비밀번호를 넣는다. 운영자 role은 기존 스키마 초기화에 필요하나 웹앱에 자격을 전달하지 않으며 운영자 기능은 꺼 둔다. `.env`·전체 `docker inspect`·보간된 `compose config` 출력에는 비밀값이 있으므로 로그·채팅·Git에 남기지 않는다.

`SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`는 실제 컨테이너에서 관측한 호스트 Caddy 연결의 Docker gateway 주소 하나에 맞는 정규식으로 지정한다. `.env`에서는 single quote를 사용해 backslash와 `$`를 보존한다. 예시를 실제 주소로 오인하지 않도록 기본값을 제공하지 않는다. `public-demo`를 `local`, `local-demo`와 섞지 않는다.

저장소 루트에서 아래 명령을 실행한다. `-p worklog-demo`·`--env-file`·`-f`를 항상 명시한다. 상위 환경의 COMPOSE_PROJECT_NAME보다 -p가 우선하므로 기존 프로젝트 선택을 방지한다. 공개 전에 `WORKLOG_IMAGE_TAG`를 배포 커밋으로 지정하고 실제 image ID도 기록한다.

```sh
docker compose -p worklog-demo --env-file deploy/worklog/.env -f deploy/worklog/compose.yaml --profile setup config --quiet
docker compose -p worklog-demo --env-file deploy/worklog/.env -f deploy/worklog/compose.yaml --profile setup build db migrate bootstrap app
docker compose -p worklog-demo --env-file deploy/worklog/.env -f deploy/worklog/compose.yaml up -d --wait db
```

## 명시적 자료 준비 후 앱 시작

DB healthy → migrate 성공(exit 0) → bootstrap 명시 실행 성공(exit 0) → app 시작 순서를 지킨다. 아래 각 명령의 성공을 확인한 후 다음 명령을 실행한다. bootstrap은 `setup` profile의 일회성 CLI이며 앱 시작·재시작으로 실행하지 않는다. DB와 같은 network namespace에서 `127.0.0.1:5432/worklog_demo`로 연결하며 `TNA_PUBLIC_DEMO_CONFIRM=worklog-demo`를 요구한다. 웹앱에는 app/auth 자격만, migrate/bootstrap에는 migration 자격만 전달한다.

```sh
docker compose -p worklog-demo --env-file deploy/worklog/.env -f deploy/worklog/compose.yaml run --rm migrate
docker compose -p worklog-demo --env-file deploy/worklog/.env -f deploy/worklog/compose.yaml stop app

docker compose -p worklog-demo --env-file deploy/worklog/.env -f deploy/worklog/compose.yaml exec -T db psql -X -v ON_ERROR_STOP=1 -U postgres -d worklog_demo < deploy/worklog/preflight.sql

docker compose -p worklog-demo --env-file deploy/worklog/.env -f deploy/worklog/compose.yaml --profile setup run --rm --no-deps bootstrap

docker compose -p worklog-demo --env-file deploy/worklog/.env -f deploy/worklog/compose.yaml exec -T db psql -X -v ON_ERROR_STOP=1 -U tna_migration -d worklog_demo < deploy/worklog/sample-data.sql

docker compose -p worklog-demo --env-file deploy/worklog/.env -f deploy/worklog/compose.yaml exec -T db psql -X -v ON_ERROR_STOP=1 -U tna_migration -d worklog_demo < deploy/worklog/verify-sample-data.sql

docker compose -p worklog-demo --env-file deploy/worklog/.env -f deploy/worklog/compose.yaml up -d --no-deps app
docker compose -p worklog-demo --env-file deploy/worklog/.env -f deploy/worklog/compose.yaml ps
```

CLI는 정확한 DB명·loopback·확인 문자열과 예약 tenant 내부의 identity를 검사한다. FORCE RLS 때문에 다른 tenant의 전역 존재 여부는 CLI가 판정할 수 없다. 앱이 중지된 상태에서 DB owner의 preflight.sql로 전체 registry가 비었거나 예약 tenant 하나뿐인지 먼저 확인한다. sample-data.sql을 최초 적용한 뒤에는 사원이 늘어나므로 bootstrap을 다시 실행하지 않는다. 다른 DB 복사·실제 자료 입력·데모 계정의 비활성화나 권한 회수를 복구하는 초기화는 하지 않는다. 앱은 HTTPS Secure 쿠키를 사용하므로 HTTP loopback 확인만으로 브라우저 로그인을 판정하지 않는다.

## Caddy와 공개 확인

`Caddyfile.example`은 기존 설정에 추가할 HTTP 리다이렉트·HTTPS 프록시 두 블록이다. 두 `__CONFIRMED_BIND_ADDRESSES__`를 기존 Caddy에서 확인한 실제 bind 주소로 치환한 후보 파일을 만들고 기존 전체 설정의 백업·diff·`caddy validate` 후 reload한다. HTTP/HTTPS 모두 기존 주소에만 bind해 다른 포트 80 리스너와의 충돌을 피한다. 이 예시로 기존 Caddyfile 전체를 교체하지 않는다. DNS의 `worklog`만 이 VPS에 연결하고 다른 레코드는 보존한다.

Caddy는 `Forwarded`를 제거하고 `X-Forwarded-For`를 실제 연결 주소로 덮어쓰며 원래 Host와 HTTPS scheme을 전달한다. 직접 노출하는 Caddy를 기준으로 하며 CDN·다른 앞단 프록시를 추가할 때는 신뢰 경계를 다시 검증한다. 근거: [Caddy reverse_proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy), [Docker Compose 서비스 정의](https://docs.docker.com/reference/compose-file/services/).

공개 HTTPS에서 소개 → CSRF POST 로그인 → 보호 화면 → 로그아웃과 Secure·HttpOnly·SameSite 쿠키를 확인한다. 위조 XFF/Forwarded, 직접 backend 외부 접속 거부, DB 포트 비공개, 운영자 경로 차단과 기존 사이트·컨테이너 상태 보존도 실제 확인한다. 실패 시 새 WorkLog 앱과 호스트 블록만 중지·복원하고 전용 DB 볼륨을 보존한다. 볼륨 삭제·전체 Docker 정리는 실행하지 않는다.

파일 계약 검증은 저장소 루트에서 실행한다. DB나 네트워크에 접속하지 않는다.

```sh
python -B -m unittest scripts.test_public_demo_deploy -v
```
