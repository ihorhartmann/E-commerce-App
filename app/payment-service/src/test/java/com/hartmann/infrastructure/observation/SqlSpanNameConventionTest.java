package com.hartmann.infrastructure.observation;

import net.ttddyy.observation.tracing.QueryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SqlSpanNameConventionTest {

    @Test
    void sqlSpanName_namesASelectByItsOperationAndTheTableItReads() {
        assertEquals("SELECT outbox_messages",
                SqlSpanNameConvention.sqlSpanName("select \"outbox_messages\".\"id\" from \"payment\".\"outbox_messages\" where sent_at is null limit ?"));
    }

    @Test
    void sqlSpanName_namesAnInsertByTheTableItWritesTheQuotesJooqAddsDropped() {
        assertEquals("INSERT payments",
                SqlSpanNameConvention.sqlSpanName("insert into \"payment\".\"payments\" (\"order_id\", \"amount\") values (?, ?) returning *"));
    }

    @Test
    void sqlSpanName_namesAnUpdateByTheTableItChanges() {
        assertEquals("UPDATE payments", SqlSpanNameConvention.sqlSpanName("update payment.payments set status = ? where id = ?"));
    }

    @Test
    void sqlSpanName_namesADeleteByTheTableItDeletesFrom() {
        assertEquals("DELETE idempotent_http_requests",
                SqlSpanNameConvention.sqlSpanName("delete from payment.idempotent_http_requests where created_at < ?"));
    }

    @Test
    void sqlSpanName_isTheOperationAloneWhenNoTableIsFound() {
        assertEquals("SELECT", SqlSpanNameConvention.sqlSpanName("select 1"));
    }

    @Test
    void sqlSpanName_isQueryWhenThereIsNoStatement() {
        assertEquals(SqlSpanNameConvention.UNKNOWN_STATEMENT, SqlSpanNameConvention.sqlSpanName(null));
    }

    @Test
    void getContextualName_namesAStatementsSpanAfterItsFirstStatement() {
        var context = new QueryContext();
        context.setQueries(List.of("update payment.payments set status = ? where id = ?"));

        assertEquals("UPDATE payments", new SqlSpanNameConvention().getContextualName(context));
    }
}
