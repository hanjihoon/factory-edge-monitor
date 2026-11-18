package fem.common;

/**
 * 공통 데이터 모델. MQTT든 Modbus든 게이트웨이가 이 형태로 정규화해서 서버로 보낸다.
 *
 * <p>중복 제거와 순서 보정의 기준은 {@code (deviceId, seq)} 하나다.
 * <ul>
 *   <li>MQTT 설비: 설비가 payload에 seq를 직접 넣어 보낸다.</li>
 *   <li>Modbus 설비: 레지스터 0~1번에 32비트 샘플 카운터가 있다고 가정한다.
 *       (실제 PLC도 하트비트 카운터를 두는 경우가 많다.)
 *       게이트웨이가 같은 카운터 값을 두 번 읽어도 seq가 같으므로 서버에서 자연스럽게 중복으로 걸러진다.</li>
 * </ul>
 * 이렇게 하면 프로토콜이 달라도 서버는 한 가지 규칙으로 중복·순서를 처리할 수 있다.
 *
 * @param deviceId    설비 ID. 공장 접두어 포함 (예: A-0001, B-0400)
 * @param factoryId   공장 ID (A, B)
 * @param protocol    원본 프로토콜
 * @param seq         설비 단위 단조 증가 순번. 1초마다 1씩 증가
 * @param deviceTs    측정 시각(epoch ms). MQTT는 설비가 넣은 값, Modbus는 게이트웨이 폴링 시각
 * @param gatewayTs   게이트웨이가 수신한 시각(epoch ms). 단절 중 적체 시간 계산에 쓴다
 * @param state       설비 운전 상태
 * @param temperature 온도(°C)
 * @param vibration   진동(mm/s)
 * @param rpm         회전수
 * @param alarmCode   알람 코드. 0이면 알람 없음
 */
public record TelemetryRecord(
        String deviceId,
        String factoryId,
        Protocol protocol,
        long seq,
        long deviceTs,
        long gatewayTs,
        DeviceState state,
        double temperature,
        double vibration,
        int rpm,
        int alarmCode
) {
}
