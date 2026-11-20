# CLAUDE.md — 작업 인계 문서

## 이 저장소는
(주)비전스페이스 채용 과제. "폐쇄망 공장 엣지 게이트웨이와 설비 통합 관제".
원문: `docs/assignment/` (과제 안내 PDF, 설계문서 양식). 회사 문서라 git에 올리지 않는다.

- 제출 기한: 2026-11-22(일) 23:59 (연장 신청 기준)
- 작업 시간 예산: 약 10시간. 다 끝내는 것보다 범위를 잘 정하는 것이 목표
- AI 사용 허용. 단 대면 면접에서 코드와 설계를 직접 설명해야 한다 → 본인이 이해할 수 있는 코드로 쓸 것
- 평가 배점: 설계 문서 30, 구현·동작 30, 장애·정합성 20, UI 10, 측정·한계 10

## 작업 방식 (사용자 선호)
- 한국어로 대화
- (2026-11-18 변경) 단계마다 확인받지 않고 기능 단위로 연속 구현한다. 기능마다 단위 테스트 → `./gradlew build` 통과 → Claude가 직접 커밋
- 커밋 메시지: Conventional Commits. `type(scope): 한국어 요약` + 본문에 무엇을/왜. scope는 모듈명(common, emulator, gateway, server, web, infra, docs)
- 이해하기 어려운 코드에는 상세한 한국어 주석
- 오류나 빈틈은 덮지 말고 바로 지적
- 각 단계를 마치면 `docs/design.md`의 해당 TODO를 함께 채운다

## 확정된 설계 결정 (바꾸려면 사용자와 먼저 상의)
1. 언어: Java 25. 서버만 Spring Boot 4 (헥사고날). 에뮬레이터·게이트웨이는 Spring 없이 순수 Java
2. 게이트웨이 → 서버: 게이트웨이가 먼저 여는 WebSocket 하나 (`/ws/gateway`). 원격 명령도 같은 연결로 내려간다
3. 게이트웨이 디스크 버퍼: SQLite outbox. 서버가 DB 커밋 후 보낸 누적 ack(`toOutboxId`)를 받아야 삭제
4. 유실 0건 = 최소 한 번 전달 + 서버 측 중복 제거. 중복·순서 기준은 `(deviceId, seq)` 하나
5. Modbus: 라인 컨트롤러 4대(포트 5020~5023) × unit 1~100. 레지스터 맵은 `docs/design.md` 6장
6. MQTT 토픽: `factory/A/device/{deviceId}/telemetry` (QoS 1), 명령 `.../command`, 응답 `.../command/ack`
7. 저장소: TimescaleDB. 회선 장애: toxiproxy (관리 API 8474, 프록시 gw-a-uplink / gw-b-uplink)
8. 폐쇄망은 docker 네트워크로 재현. 공장망은 internal, 게이트웨이는 포트를 열지 않는다
9. JSON: Jackson 3 (`tools.jackson`). common 모듈은 의존성 없음
10. 메시지 계약: `common/src/main/java/fem/common/wire/WireMessages.java`
11. UI: `web/` 폴더에 Vite + React + TypeScript. 라이브러리 최소화 (상태 관리·UI 키트 없음, 기본 WebSocket)
12. UI 배포: nginx 컨테이너. React 정적 파일 제공 + `/api`, `/ws`는 server로 프록시 (같은 주소로 CORS 회피)
13. 사용자 인증: 고정 계정 2개 (operator: 명령 가능, viewer: 조회만), 로그인 시 토큰 발급

## 진행 계획
| 단계 | 내용 | 예상 | 상태 |
|---|---|---|---|
| 1 | 저장소 뼈대, compose, 공통 계약, 설계 문서 초안 | 0.5h | 완료 (로컬 빌드 확인됨) |
| 2 | 에뮬레이터: MQTT 600 + Modbus 400, 중복·순서 뒤바뀜·지연 주입, 명령 수신 | 1.5h | 완료 (MQTT는 브로커 연동 미검증: 로컬에 docker 없음) |
| 3 | 게이트웨이: 수집, 정규화, SQLite outbox, WebSocket 전송·재연결·재전송, 상태 보고 | 2h | 완료 (WebSocket·MQTT 실연동은 서버 구현 후/compose에서 확인) |
| 4 | 서버 수신: 게이트웨이 토큰 인증, 중복 제거 저장, ack, 설비 현재 상태(seq가 클 때만 갱신) | 1h | 완료 (SQL은 TimescaleDB 실연동 미검증. WebSocket은 실제 게이트웨이 코드로 종단 테스트) |
| 5 | 원격 명령: 정지·재개, commandId 멱등, 시간 초과·재시도, 감사 로그 | 1h | 완료 (REST 엔드포인트는 6단계에서 인증과 함께) |
| 6 | 최소 UI (정적 HTML+JS) + UI용 WebSocket, 사용자 인증·권한(운영자/조회자) | 1h | 완료 (로컬은 Smart App Control이 rollup 네이티브를 막아 vite build·vitest 불가 → Docker 빌드에서 실행, 로컬은 tsc만) |
| 7 | 측정: 처리량, 종단 지연 p50·p95, 10분 단절 후 유실 0건, 복구 시간 | 1h | 완료 (2026-11-19). 유실·중복 0, 복구 128초 → 개선 후 79초, 실시간 복귀 20초 → 병렬 저장 후 66초·10초. 화면 지연 p50 0.26초, p95 대부분 2초 안 |
| 8 | 설계 문서 마무리, API·WebSocket 명세 | 1.5h | 완료 (13장 AI 활용은 사용자 작성). 다이어그램 docs/architecture.svg, 자체 점검 docs/review.md |
| 9 | 시연 영상 (정상 동작 + 장애 복구) | 0.5h | |
| 10 | 고도화 (2026-11-19): 서버 병렬 저장, 속성 기반 테스트(jqwik), 가동률 KPI(연속 집계), 지연 예산, 브로커 persistence 수정, 확장 설계 | 4h | 진행 중 |

범위에서 뺀 것: 선택 구현 중 NestJS, 3D, 게이트웨이 원격 업데이트 (가동률 KPI는 10단계에서 구현). 설계 문서 12장에 이유와 함께 적는다.

## 메모
- 로컬 JDK: Temurin 25.0.5 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`, 시스템 JAVA_HOME). JDK 17도 함께 설치돼 있음
- Gradle 9.1.0 wrapper 생성 완료. Spring Boot 4.0.0 + Boot 4 스타터 이름(`starter-webmvc`, `starter-flyway`)은 `./gradlew build`로 검증됨 (2026-11-18, 경고 없음)


