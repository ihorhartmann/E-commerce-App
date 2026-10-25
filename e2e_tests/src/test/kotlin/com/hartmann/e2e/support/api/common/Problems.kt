package com.hartmann.e2e.support.api.common

import io.restassured.response.ValidatableResponse
import org.assertj.core.api.Assertions.assertThat

private const val PROBLEM_CONTENT_TYPE = "application/problem+json"
private const val TYPE_URN_PREFIX = "urn:ecommerce:error:"

/**
 * Asserts that [response] is the RFC 9457 problem details every service answers an error with: [status], and the
 * error's [code] (with its service prefix, e.g. `ORDER.ORDER_NOT_FOUND`) both as the `code` member and in the `type`
 * URN. The code, not the status alone, says which failure it was: one status can stand for several.
 */
fun assertProblem(response: ValidatableResponse, status: Int, code: String) {
    val extracted = response.extract()

    assertThat(extracted.statusCode()).isEqualTo(status)
    assertThat(extracted.contentType()).startsWith(PROBLEM_CONTENT_TYPE)
    assertThat(extracted.path<Int>("status")).isEqualTo(status)
    assertThat(extracted.path<String>("code")).isEqualTo(code)
    assertThat(extracted.path<String>("type")).isEqualTo(TYPE_URN_PREFIX + code)
}
