package com.hartmann.e2e.support.config

import com.hartmann.e2e.support.docker.DockerComposeStack
import com.hartmann.e2e.support.stripe.StripeCliListener
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Applied to every scenario via E2eScenario's @ExtendWith. beforeAll fires once per scenario
 * class, but both actions are idempotent, so the real work only happens once for the whole run.
 *
 * Stop is registered as a CloseableResource in the ROOT store rather than called from an
 * afterAll: JUnit Jupiter has no "after the entire test plan" callback (afterAll is per-class,
 * same as beforeAll), and closing a root-store resource is the documented way to run something
 * exactly once after every scenario class has finished.
 */
class E2eConfigExtension : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        RestAssuredJsonConfig.apply()
        DockerComposeStack.start()
        StripeCliListener.start()

        context.root.getStore(ExtensionContext.Namespace.GLOBAL).getOrComputeIfAbsent(SHUTDOWN_KEY) {
            ExtensionContext.Store.CloseableResource {
                StripeCliListener.stop()
                DockerComposeStack.stop()
            }
        }
    }

    private companion object {
        const val SHUTDOWN_KEY = "dockerComposeStackShutdown"
    }
}
