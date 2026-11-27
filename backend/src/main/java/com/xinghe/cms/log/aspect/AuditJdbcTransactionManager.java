package com.xinghe.cms.log.aspect;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;

/** Runs failure audit writes after Spring returns the business transaction's JDBC connection. */
final class AuditJdbcTransactionManager extends JdbcTransactionManager {

    private static final ThreadLocal<Deque<List<Runnable>>> TRANSACTIONS = new ThreadLocal<>();

    private final boolean translateSqlExceptions;

    AuditJdbcTransactionManager(DataSource dataSource, boolean translateSqlExceptions) {
        super(dataSource);
        this.translateSqlExceptions = translateSqlExceptions;
    }

    static void afterConnectionReleased(Runnable writeAudit) {
        Deque<List<Runnable>> transactions = TRANSACTIONS.get();
        if (transactions == null) {
            // Non-JDBC transaction managers have no connection to return here.
            writeAudit.run();
            return;
        }
        transactions.getLast().add(writeAudit);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        Deque<List<Runnable>> transactions = TRANSACTIONS.get();
        if (transactions == null) {
            transactions = new ArrayDeque<>();
            TRANSACTIONS.set(transactions);
        }
        transactions.addLast(new ArrayList<>());
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        Deque<List<Runnable>> transactions = TRANSACTIONS.get();
        List<Runnable> finished = transactions.removeLast();
        boolean outermost = transactions.isEmpty();
        if (outermost) {
            TRANSACTIONS.remove();
        }
        super.doCleanupAfterCompletion(transaction);
        if (!outermost) {
            // A suspended outer transaction can still hold a connection.
            transactions.getLast().addAll(finished);
            return;
        }
        finished.forEach(Runnable::run);
    }

    @Override
    protected RuntimeException translateException(String task, SQLException exception) {
        return translateSqlExceptions ? super.translateException(task, exception)
                : new TransactionSystemException(task, exception);
    }
}
