package com.hartmann.e2e.support.stripe

import com.hartmann.e2e.support.docker.DockerComposeStack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs `stripe listen` for the whole suite so payment-service receives Stripe's real webhooks
 * (payment confirmation, decline, refund). Lifecycle mirrors DockerComposeStack: start() is
 * idempotent, stop() runs once after the whole suite.
 */
object StripeCliListener {
    private const val READY_TIMEOUT_SECONDS = 30L
    private val SIGNING_SECRET_LINE = Regex("Your webhook signing secret is (\\S+)")

    private lateinit var process: Process

    private val started: Boolean by lazy {
        process = ProcessBuilder(
            "stripe", "listen",
            "--forward-to", "${DockerComposeStack.paymentServiceUrl}/api/v1/webhooks/stripe",
            "--api-key", DockerComposeStack.stripeSecretKey,
        ).redirectErrorStream(true).start()

        awaitReady()
        true
    }

    fun start() {
        check(started)
    }

    fun stop() {
        if (::process.isInitialized) {
            process.destroy()
        }
    }

    private fun awaitReady() {
        val ready = CountDownLatch(1)
        var signingSecret: String? = null
        val reader = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                SIGNING_SECRET_LINE.find(line)?.let { signingSecret = it.groupValues[1] }
                if (signingSecret != null) ready.countDown()
            }
        }
        reader.isDaemon = true
        reader.start()

        check(ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "stripe listen did not become ready within ${READY_TIMEOUT_SECONDS}s"
        }
        check(signingSecret == DockerComposeStack.stripeWebhookSecret) {
            "stripe listen's webhook signing secret ($signingSecret) does not match " +
                "STRIPE_WEBHOOK_SECRET (${DockerComposeStack.stripeWebhookSecret})"
        }
    }
}
