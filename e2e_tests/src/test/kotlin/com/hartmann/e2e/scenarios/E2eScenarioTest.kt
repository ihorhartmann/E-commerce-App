package com.hartmann.e2e.scenarios

import com.hartmann.e2e.support.config.E2eConfigExtension
import org.junit.jupiter.api.extension.ExtendWith
import java.time.Duration

/** Base for every e2e scenario - wires up E2eEnvironmentExtension (docker-compose stack +
 *  RestAssured JSON config) without repeating @ExtendWith on each scenario class. */
@ExtendWith(E2eConfigExtension::class)
abstract class E2eScenarioTest {
    companion object {
        val AWAIT_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
