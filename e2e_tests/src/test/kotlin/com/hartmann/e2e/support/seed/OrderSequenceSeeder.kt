package com.hartmann.e2e.support.seed

import com.hartmann.e2e.support.docker.DockerComposeStack
import java.sql.DriverManager
import java.time.Instant

/**
 * Restarts order-service's orders.id identity sequence off the current epoch second before every
 * suite run. payment-service scopes its Stripe idempotency key to "order-" + orderId (see
 * StripePaymentGateway) - Stripe remembers idempotency keys for ~24h on its own side, independent
 * of our Postgres. Every fresh e2e run gets an empty Postgres where ids would otherwise restart
 * at 1, so repeated runs within that window would collide with a PaymentIntent an earlier run
 * already confirmed. Seeding the sequence off wall-clock time guarantees each run's id range was
 * never used by an earlier run.
 */
object OrderSequenceSeeder {
    private val seeded: Boolean by lazy {
        restart()
        true
    }

    fun start() {
        check(seeded)
    }

    private fun restart() {
        DriverManager.getConnection(
            DockerComposeStack.postgresJdbcUrl,
            DockerComposeStack.postgresUser,
            DockerComposeStack.postgresPassword,
        ).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "SELECT setval(pg_get_serial_sequence('orders.orders', 'id'), ${Instant.now().epochSecond}, false)",
                )
            }
        }
    }
}
