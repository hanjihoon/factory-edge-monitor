package fem.server.application;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 커밋·롤백 횟수만 세는 가짜 트랜잭션 매니저. DB 없이 "트랜잭션 경계"를 확인할 때 쓴다. */
public class FakeTransactionManager implements PlatformTransactionManager {

    public int commits;
    public int rollbacks;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
        commits++;
    }

    @Override
    public void rollback(TransactionStatus status) {
        rollbacks++;
    }
}
