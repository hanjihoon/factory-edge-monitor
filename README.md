# factory-edge-monitor

폐쇄망 공장 2곳(설비 1,000대)의 엣지 게이트웨이와 중앙 통합 관제.
설계 문서: [docs/design.md](docs/design.md)

## 구성
| 모듈 | 역할 |
|---|---|
| `common` | 공통 데이터 모델, 게이트웨이 ↔ 서버 메시지 계약 |
| `emulator` | 설비 1,000대 (MQTT 600, Modbus TCP 400) + 장애 주입 |
| `gateway` | 공장당 1대. 수집 → 정규화 → 디스크 버퍼 → 서버 전송 |
| `server` | 중앙 서버 (Spring Boot 4). 저장, 관제 UI, 원격 명령 |

## 실행
```bash
docker compose up --build
```
- 관제 UI: http://localhost:8080
- 회선 장애 주입 API (toxiproxy): http://localhost:8474

## 로컬 빌드
JDK 25, Gradle 9 이상
```bash
./gradlew build
```
