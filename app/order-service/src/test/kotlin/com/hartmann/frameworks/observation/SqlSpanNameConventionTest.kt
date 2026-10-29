package com.hartmann.frameworks.observation

import net.ttddyy.observation.tracing.QueryContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class SqlSpanNameConventionTest {

    @Test
    fun `sqlSpanName - names a select by its operation and the table it reads`() {
        assertEquals(
            "SELECT outbox_messages",
            sqlSpanName("SELECT * FROM orders.outbox_messages WHERE sent_at IS NULL ORDER BY created_at LIMIT ?"),
        )
    }

    @Test
    fun `sqlSpanName - names an insert by the table it writes, the quotes Hibernate adds dropped`() {
        assertEquals("INSERT orders", sqlSpanName("""insert into "orders"."orders" (customer_id,status) values (?,?)"""))
    }

    @Test
    fun `sqlSpanName - names an update by the table it changes`() {
        assertEquals("UPDATE orders", sqlSpanName("update orders.orders set status=?,version=? where id=? and version=?"))
    }

    @Test
    fun `sqlSpanName - names a delete by the table it deletes from`() {
        assertEquals("DELETE idempotent_http_requests", sqlSpanName("delete from orders.idempotent_http_requests where created_at<?"))
    }

    @Test
    fun `sqlSpanName - is the operation alone when no table is found`() {
        assertEquals("SELECT", sqlSpanName("select nextval('orders.orders_seq')"))
    }

    @Test
    fun `sqlSpanName - is query when there is no statement`() {
        assertEquals("query", sqlSpanName(null))
    }

    @Test
    fun `getContextualName - names a statement's span after its first statement`() {
        val context = QueryContext().apply { queries = listOf("update orders.orders set status=? where id=?") }

        assertEquals("UPDATE orders", SqlSpanNameConvention().getContextualName(context))
    }
}
