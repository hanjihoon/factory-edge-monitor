package fem.server.domain;

/**
 * 관제 화면에 보여 줄 게이트웨이 상태.
 *
 * @param connected        지금 WebSocket이 붙어 있는가
 * @param lastSeenAt       마지막으로 무엇이든 받은 시각 (epoch ms, 없으면 0)
 * @param bufferedCount    게이트웨이 디스크에 쌓인 미전송 건수 (마지막 보고 기준)
 * @param oldestBufferedAgeMs 가장 오래된 미전송 레코드의 나이
 * @param droppedCount     버퍼 상한 초과로 버려진 누적 건수. 0보다 크면 유실 알람
 */
public record GatewayView(
        String gatewayId,
        boolean connected,
        long lastSeenAt,
        long bufferedCount,
        long oldestBufferedAgeMs,
        int connectedDevices,
        long droppedCount
) {
}
