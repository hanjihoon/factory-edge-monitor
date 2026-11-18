package fem.gateway.uplink;

/**
 * 서버와의 연결 하나. 실제 구현은 {@link WebSocketConnector}가 만들고, 테스트에서는 가짜를 쓴다.
 * 끊긴 뒤 새로 연결되면 새 객체가 된다 (객체 동일성으로 "같은 연결인가"를 판단한다).
 */
public interface Connection {

    /** 텍스트 프레임 하나를 보낸다. 보내기가 끝날 때까지 막힌다. 실패하면 예외. */
    void send(String text) throws Exception;

    /** 연결을 끊는다. 이후 연결기가 재연결을 시도한다. */
    void close(String reason);
}
