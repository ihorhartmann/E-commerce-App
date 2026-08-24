package com.hartmann.e2e.support.docker

import org.testcontainers.containers.ComposeContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import java.net.HttpURLConnection
import java.time.Duration

/**
 * The one docker-compose stack shared by every E2E scenario. Lifecycle is driven by
 * E2eConfigExtension (see E2eScenario): start() is idempotent, so calling it once per
 * scenario class only really starts the stack the first time; stop() runs once, after the whole
 * suite finishes.
 */
object DockerComposeStack {
    private val STARTUP_TIMEOUT: Duration = Duration.ofMinutes(3)

    private fun requiredEnv(name: String): String =
        System.getenv(name) ?: error("$name is not set - export it before running tests.")

    private val inventoryPort = requiredEnv("INVENTORY_SERVICE_PORT").toInt()
    private val orderPort = requiredEnv("ORDER_SERVICE_PORT").toInt()
    private val paymentPort = requiredEnv("PAYMENT_SERVICE_PORT").toInt()
    private val shippingPort = requiredEnv("SHIPPING_SERVICE_PORT").toInt()

    val stripeSecretKey: String = requiredEnv("STRIPE_SECRET_KEY")
    val stripePublishableKey: String = requiredEnv("STRIPE_PUBLISHABLE_KEY")
    val stripeWebhookSecret: String = requiredEnv("STRIPE_WEBHOOK_SECRET")

    init {
        // consumed by the docker compose child process via inherited env, not by this Kotlin code -
        // just fail fast here with a clear message if any is missing
        requiredEnv("KAFKA_PORT")
        requiredEnv("KAFKA_HOST_PORT")
        requiredEnv("KAFKA_UI_PORT")
    }

    // No .withEnv(...): Testcontainers doesn't reliably pass these through to docker-compose's
    // ${VAR} substitution here, leaving dependent services with empty config and exiting(1).
    // The JVM test process already has these values in its own OS env (requiredEnv() reads them
    // from there), and the child `docker compose` process inherits that, so substitution works
    // without repeating anything explicitly.
    private val compose: ComposeContainer =
        ComposeContainer(File("../docker-compose.yaml"))
            .withExposedService(
                "inventory-service",
                inventoryPort,
                Wait.forHttp("/health").forStatusCode(HttpURLConnection.HTTP_OK).withStartupTimeout(STARTUP_TIMEOUT),
            )
            .withExposedService(
                "shipping-service",
                shippingPort,
                Wait.forHttp("/health").forStatusCode(HttpURLConnection.HTTP_OK).withStartupTimeout(STARTUP_TIMEOUT),
            )
            .withExposedService(
                "order-service",
                orderPort,
                Wait.forListeningPort().withStartupTimeout(STARTUP_TIMEOUT),
            )
            .withExposedService(
                "payment-service",
                paymentPort,
                Wait.forListeningPort().withStartupTimeout(STARTUP_TIMEOUT),
            )

    private val started: Boolean by lazy {
        compose.start()
        true
    }

    fun start() {
        check(started)
    }

    fun stop() = compose.stop()

    val orderServiceUrl: String by lazy { serviceUrl("order-service", orderPort) }

    val inventoryServiceUrl: String by lazy { serviceUrl("inventory-service", inventoryPort) }

    val paymentServiceUrl: String by lazy { serviceUrl("payment-service", paymentPort) }

    val shippingServiceUrl: String by lazy { serviceUrl("shipping-service", shippingPort) }

    private fun serviceUrl(serviceName: String, port: Int): String {
        val host = compose.getServiceHost(serviceName, port)
        val mappedPort = compose.getServicePort(serviceName, port)
        return "http://$host:$mappedPort"
    }
}
