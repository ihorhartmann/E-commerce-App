package com.hartmann.adapter.in.scheduled;

import com.hartmann.application.port.out.outbox.OutboxMessageRepository;
import com.hartmann.application.port.out.outbox.dto.OutboxMessage;
import com.hartmann.application.port.out.tracing.TraceContextPropagator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class OutboxMessageRelayTest {

    @Mock
    private OutboxMessageRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private TraceContextPropagator traceContextPropagator;

    private OutboxMessageRelay relay;

    @BeforeEach
    void setUp() {
        lenient().when(traceContextPropagator.withParent(any(), any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
        relay = new OutboxMessageRelay(repository, kafkaTemplate, traceContextPropagator);
    }

    private OutboxMessage message(String traceparent) {
        return OutboxMessage.createExisting(UUID.randomUUID(), "corr-1", "PaymentCompleted", "42", "{}", traceparent);
    }

    @Test
    @SuppressWarnings("unchecked")
    void relay_publishesEveryMessageAsAChildOfItsOwnSpanAndMarksTheBatchSent() {
        var firstTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        var first = message(firstTraceparent);
        var second = message(null);
        when(repository.fetchUnsent(anyInt())).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        relay.relay();

        InOrder order = inOrder(traceContextPropagator);
        order.verify(traceContextPropagator).withParent(eq(firstTraceparent), any());
        order.verify(traceContextPropagator).withParent(isNull(), any());
        verify(traceContextPropagator, times(2)).withParent(any(), any());
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        verify(repository, times(1)).markSentBatch(eq(List.of(first.messageId(), second.messageId())), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void relay_doesNotMarkTheBatchSentWhenAMessageFailsToPublish() {
        when(repository.fetchUnsent(anyInt())).thenReturn(List.of(message(null)));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.relay();

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        verify(repository, never()).markSentBatch(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void relay_doesNothingWhenThereIsNoUnsentMessage() {
        when(repository.fetchUnsent(anyInt())).thenReturn(List.of());

        relay.relay();

        verify(traceContextPropagator, never()).withParent(any(), any());
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(repository, never()).markSentBatch(any(), any());
    }
}
