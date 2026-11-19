package fem.server.domain;

/**
 * 관제 화면에 보여 줄 게이트웨이 상태.
 *
 * @param connected        지금 WebSocket이 붙어 있는가
 * @param lastSeenAt       마지막으로 무엇이든 받은 시각 (epoch ms, 없으면 0)
 * @param bufferedCount    게이트웨이 디스크에 쌓인 미전송 건수 (마지막 보고 기준)
 * @param oldestBufferedAgeMs 가장 오래된 미전송 레코드의 나이
 * @param droppedCount     버퍼 상한 초과로 버려진 누적 건수. 0보다 크면 유실 알람
 * @param statusFresh      적체 값이 "지금 연결에서 받은 상태 보고"인가. 끊긴 동안이나 재연결 직후(첫 보고 전)에는
 *                         false다. 이때 적체 값은 끊기기 전 보고라 실제(계속 쌓이는 중)와 다르므로 화면은 숫자 대신
 *                         "알 수 없음"/"확인 중"을 보여 준다
 * @param reconnectsLastMinute 최근 1분 동안 이 게이트웨이 ID로 새로 붙은 횟수. 같은 토큰을 쓰는 게이트웨이가 둘이면
 *                         서버는 나중에 붙은 쪽을 남기고 앞의 연결을 끊으므로, 둘이 초당 1회 넘게 서로 밀어낸다.
 *                         이 상태에서는 데이터가 두 게이트웨이 디스크로 나뉘어 쌓이고, 한쪽을 지우면 그 안의 미전송분이 유실된다 (실측)
 */
public record GatewayView(
        String gatewayId,
        boolean connected,
        long lastSeenAt,
        long bufferedCount,
        long oldestBufferedAgeMs,
        int connectedDevices,
        long droppedCount,
        boolean statusFresh,
        int reconnectsLastMinute
) {
    /** 1분에 이만큼 이상 다시 붙으면 같은 토큰을 쓰는 게이트웨이가 둘 이상이라고 본다 (서로 밀어내며 접속을 반복) */
    public static final int DUPLICATE_SUSPECT_RECONNECTS = 5;

    public boolean duplicateSuspected() {
        return reconnectsLastMinute >= DUPLICATE_SUSPECT_RECONNECTS;
    }
}
