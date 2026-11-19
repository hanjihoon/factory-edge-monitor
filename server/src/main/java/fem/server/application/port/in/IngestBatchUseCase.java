package fem.server.application.port.in;

import fem.common.wire.WireMessages.Batch;

/** 게이트웨이 묶음 수신 (입력 포트) */
public interface IngestBatchUseCase {

    /**
     * @param inserted   새로 저장된 건수
     * @param duplicates 중복이라 버린 건수
     * @param rejected   다른 공장 설비 등 잘못된 레코드라 버린 건수
     */
    record Result(int inserted, int duplicates, int rejected) {
    }

    /**
     * 묶음을 한 트랜잭션으로 저장한다. 정상 반환 = DB 커밋 완료 → 호출자가 ack를 보내도 된다.
     * 예외가 나면 아무것도 저장되지 않은 것이고 ack를 보내면 안 된다.
     *
     * @param factoryId 인증된 게이트웨이의 공장. 다른 공장 설비 레코드는 버린다
     */
    Result ingest(String gatewayId, String factoryId, Batch batch);
}
