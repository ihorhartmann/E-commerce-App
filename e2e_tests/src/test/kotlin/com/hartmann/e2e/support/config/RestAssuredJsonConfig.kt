package com.hartmann.e2e.support.config

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.restassured.RestAssured
import io.restassured.config.ObjectMapperConfig
import io.restassured.config.RestAssuredConfig

object RestAssuredJsonConfig {
    private val applied: Boolean by lazy {
        RestAssured.config = RestAssuredConfig.config().objectMapperConfig(
            ObjectMapperConfig.objectMapperConfig().jackson2ObjectMapperFactory { _, _ ->
                jacksonObjectMapper().registerModule(JavaTimeModule())
            },
        )
        true
    }

    fun apply() {
        check(applied)
    }
}
