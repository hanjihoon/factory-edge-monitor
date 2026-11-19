package fem.server.domain;

/**
 * 명령 감사 로그 한 줄. "누가 언제 무엇을 했고 결과가 어땠나"를 나중에 추적하기 위한 추가 전용 기록.
 *
 * @param event REQUESTED, SENT, SEND_FAILED, RESULT, DUPLICATE_RESULT_IGNORED, RETRY, TIMED_OUT
 * @param actor 사람이 한 일이면 사용자 이름, 시스템이 한 일이면 "system", 게이트웨이 응답이면 게이트웨이 ID
 */
public record AuditEntry(String commandId, long at, String event, String actor, String detail) {
}
