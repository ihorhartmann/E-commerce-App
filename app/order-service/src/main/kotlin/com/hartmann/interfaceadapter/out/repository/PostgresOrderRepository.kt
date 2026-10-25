package com.hartmann.interfaceadapter.out.repository

import com.hartmann.entities.model.Order
import com.hartmann.interfaceadapter.out.repository.entity.OrderEntity
import com.hartmann.interfaceadapter.out.repository.entity.fromOrderEntity
import com.hartmann.interfaceadapter.out.repository.entity.toOrderEntity
import com.hartmann.interfaceadapter.out.repository.exception.DatabaseFailureException
import com.hartmann.usecase.port.out.repository.OrderRepository
import com.hartmann.usecase.port.out.repository.dto.OrderPage
import com.hartmann.usecase.port.out.repository.exception.ConcurrentUpdateException
import com.hartmann.usecase.port.out.repository.exception.OrderNotFoundException
import jakarta.persistence.EntityManager
import jakarta.persistence.OptimisticLockException
import jakarta.persistence.PersistenceException
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Every method reports a database error as DatabaseFailureException, so it carries a code of its own rather than
 * reaching the error handlers as an anonymous JPA exception.
 */
@Repository
class PostgresOrderRepository(private val entityManager: EntityManager) : OrderRepository {
    // The sequence behind orders.id: an order takes its id from here before it is inserted. Read-write on purpose:
    // nextval is rejected inside a read-only transaction.
    @Transactional
    override fun nextId(): Long =
        translatingFailures {
            (entityManager.createNativeQuery("SELECT nextval(pg_get_serial_sequence('orders.orders', 'id'))").singleResult as Number)
                .toLong()
        }

    @Transactional
    override fun create(order: Order): Order =
        translatingFailures {
            val entity = toOrderEntity(order)
            entityManager.persist(entity)
            entityManager.flush()
            fromOrderEntity(entity)
        }

    // Flushed right away, so a stale version fails here - as the port's ConcurrentUpdateException -
    // rather than later at commit, as a JPA exception the use cases don't know about.
    @Transactional
    override fun save(order: Order): Order =
        try {
            val entity = entityManager.merge(toOrderEntity(order))
            entityManager.flush()
            fromOrderEntity(entity)
        } catch (e: OptimisticLockException) {
            throw ConcurrentUpdateException(order.id, order.version, e)
        } catch (e: PersistenceException) {
            throw DatabaseFailureException(e)
        }

    override fun getOrder(id: Long): Order {
        val entity = translatingFailures { entityManager.find(OrderEntity::class.java, id) }
            ?: throw OrderNotFoundException(id)
        return fromOrderEntity(entity)
    }

    // The id breaks ties between orders created at the same instant, so a page never shifts between requests. The
    // count is a statement of its own, but runs in the same transaction as the page.
    @Transactional(readOnly = true)
    override fun findCustomerOrders(customerId: UUID, page: Int, size: Int): OrderPage =
        translatingFailures {
            val entities = entityManager
                .createQuery(
                    "SELECT o FROM OrderEntity o WHERE o.customerId = :customerId ORDER BY o.createdAt DESC, o.id DESC",
                    OrderEntity::class.java,
                )
                .setParameter("customerId", customerId)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .resultList
            val totalElements = entityManager
                .createQuery("SELECT COUNT(o) FROM OrderEntity o WHERE o.customerId = :customerId", Long::class.javaObjectType)
                .setParameter("customerId", customerId)
                .singleResult

            OrderPage(entities.map(::fromOrderEntity), totalElements)
        }

    private fun <T> translatingFailures(block: () -> T): T =
        try {
            block()
        } catch (e: PersistenceException) {
            throw DatabaseFailureException(e)
        }
}
