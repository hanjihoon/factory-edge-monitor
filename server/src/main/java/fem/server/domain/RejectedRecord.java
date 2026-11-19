package fem.server.domain;

/**
 * 저장할 수 없어 격리한 레코드 (dead letter). 버리지 않고 원문을 남겨 운영자가 원인을 볼 수 있게 한다.
 *
 * @param reason  격리한 이유 ({@link RecordCheck})
 * @param payload 받은 레코드의 원문 표현
 */
public record RejectedRecord(String reason, String payload) {
}
