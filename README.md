# factory-edge-monitor

폐쇄망 공장 2곳(설비 1,000대)의 엣지 게이트웨이와 중앙 통합 관제.
설계 문서: [docs/design.md](docs/design.md)

## 구성
| 모듈 | 역할 |
|---|---|
| `common` | 공통 데이터 모델, 게이트웨이 ↔ 서버 메시지 계약, MQTT·Modbus 인코딩 규칙 |
| `emulator` | 설비 1,000대 (MQTT 600, Modbus TCP 400) + 장애 주입 |
| `gateway` | 공장당 1대. 수집 → 정규화 → SQLite 디스크 버퍼 → 서버 전송, 원격 명령 중계 |
| `server` | 중앙 서버 (Spring Boot 4, 헥사고날). 저장, 원격 명령, REST·WebSocket |
| `web` | 관제 UI (Vite + React + TS), nginx로 제공 |

## 실행
```bash
docker compose up --build
```
| 주소 | 용도 |
|---|---|
| http://localhost:8080 | 관제 UI (nginx → server 프록시) |
| http://localhost:8081 | 서버 직접 접근 (측정·디버깅) |
| http://localhost:8474 | 회선 장애 주입 API (toxiproxy) |

계정 (시연용 고정 계정)
| 계정 | 비밀번호 | 권한 |
|---|---|---|
| operator | operator123 | 조회 + 정지·재개 |
| viewer | viewer123 | 조회만 |

## 장애 주입
```bash
# 공장 A 회선 끊기 / 복구
curl -X POST localhost:8474/proxies/gw-a-uplink -d '{"enabled":false}'
curl -X POST localhost:8474/proxies/gw-a-uplink -d '{"enabled":true}'
# 공장 B 회선에 지연 1초
curl -X POST localhost:8474/proxies/gw-b-uplink/toxics -d '{"type":"latency","attributes":{"latency":1000}}'
```
게이트웨이 재시작(디스크 버퍼 보존 확인): `docker compose restart gateway-a`

## 측정
```bash
scripts/measure.sh steady 120   # 정상 상태 2분: 처리량, 지연, 중복, 유실
scripts/measure.sh outage 600   # 두 회선 10분 차단 → 복구 시간, 유실 0건 확인
```

## 로컬 빌드·테스트
JDK 25 (Gradle wrapper 포함)
```bash
./gradlew build          # 전체 빌드 + Java 테스트
cd web && npm ci && npm test   # UI 단위 테스트 (vitest)
```
