package com.hartmann.frameworks.config

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.listener.ConsumerRecordRecoverer
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import java.time.Duration

class RetryableDeadLetterPublishingRecoverer(
    private val delegate: DeadLetterPublishingRecoverer,
    private val interval: Duration,
) : ConsumerRecordRecoverer {

    override fun accept(record: ConsumerRecord<*, *>, exception: Exception) {
        while (true) {
            try {
                delegate.accept(record, exception)
                return
            } catch (e: RuntimeException) {
                if (Thread.currentThread().isInterrupted) {
                    throw e
                }

                LOG.error("failed to publish record to the dead-letter topic, retrying", e)
                try {
                    Thread.sleep(interval.toMillis())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(RetryableDeadLetterPublishingRecoverer::class.java)
    }
}
