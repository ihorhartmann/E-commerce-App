package com.hartmann.e2e.support.docker

import com.hartmann.e2e.support.auth.TestUser
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
    private const val POSTGRES_CONTAINER_PORT = 5432
    private const val KEYCLOAK_CONTAINER_PORT = 8080
    private const val REALM_PATH = "/realms/ecommerce"

    fun requiredEnv(name: String): String =
        System.getenv(name) ?: error("$name is not set - export it before running tests.")

    private val inventoryPort = requiredEnv("INVENTORY_SERVICE_PORT").toInt()
    private val orderPort = requiredEnv("ORDER_SERVICE_PORT").toInt()
    private val paymentPort = requiredEnv("PAYMENT_SERVICE_PORT").toInt()
    private val shippingPort = requiredEnv("SHIPPING_SERVICE_PORT").toInt()
    private val gatewayPort = requiredEnv("GATEWAY_PORT").toInt()

    val stripeSecretKey: String = requiredEnv("STRIPE_SECRET_KEY")
    val stripePublishableKey: String = requiredEnv("STRIPE_PUBLISHABLE_KEY")
    val stripeWebhookSecret: String = requiredEnv("STRIPE_WEBHOOK_SECRET")

    val postgresDb: String = requiredEnv("POSTGRES_DB")
    val postgresUser: String = requiredEnv("POSTGRES_USER")
    val postgresPassword: String = requiredEnv("POSTGRES_PASSWORD")

    init {
        // consumed by the docker compose child process via inherited env, not by this Kotlin code -
        // just fail fast here with a clear message if any is missing
        requiredEnv("KAFKA_PORT")
        requiredEnv("KAFKA_HOST_PORT")
        requiredEnv("KAFKA_UI_PORT")
        requiredEnv("POSTGRES_PORT")
        requiredEnv("KEYCLOAK_PORT")
        requiredEnv("KEYCLOAK_ADMIN_USER")
        requiredEnv("KEYCLOAK_ADMIN_PASSWORD")
        requiredEnv("GATEWAY_CLIENT_SECRET")
        requiredEnv("ORDER_SERVICE_CLIENT_SECRET")
        TestUser.entries.forEach { requiredEnv(it.passwordEnv) }
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
            .withExposedService(
                "keycloak",
                KEYCLOAK_CONTAINER_PORT,
                // the realm is imported once its discovery document answers; /health/ready is on the management port,
                // which is not published
                Wait.forHttp("$REALM_PATH/.well-known/openid-configuration")
                    .forStatusCode(HttpURLConnection.HTTP_OK)
                    .withStartupTimeout(STARTUP_TIMEOUT),
            )
            .withExposedService(
                "gateway-service",
                gatewayPort,
                Wait.forHttp("/health").forStatusCode(HttpURLConnection.HTTP_OK).withStartupTimeout(STARTUP_TIMEOUT),
            )
            .withExposedService(
                "postgres",
                POSTGRES_CONTAINER_PORT,
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

    val gatewayUrl: String by lazy { serviceUrl("gateway-service", gatewayPort) }

    // the address tokens are fetched through doesn't matter: Keycloak always writes its public URL (KC_HOSTNAME) into `iss`
    val keycloakTokenUrl: String by lazy {
        "${serviceUrl("keycloak", KEYCLOAK_CONTAINER_PORT)}$REALM_PATH/protocol/openid-connect/token"
    }

    val postgresJdbcUrl: String by lazy {
        val host = compose.getServiceHost("postgres", POSTGRES_CONTAINER_PORT)
        val port = compose.getServicePort("postgres", POSTGRES_CONTAINER_PORT)
        "jdbc:postgresql://$host:$port/$postgresDb"
    }

    private fun serviceUrl(serviceName: String, port: Int): String {
        val host = compose.getServiceHost(serviceName, port)
        val mappedPort = compose.getServicePort(serviceName, port)
        return "http://$host:$mappedPort"
    }
}
