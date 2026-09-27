package com.hartmann.interfaceadapter.`in`.kafka.exception

/** A message header is present but its value can't be used, e.g. a messageId that isn't a UUID. */
class InvalidMessageHeaderException(message: String, cause: Throwable) : RuntimeException(message, cause)
