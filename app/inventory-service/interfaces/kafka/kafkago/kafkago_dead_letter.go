package kafkago

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"strings"
	"time"

	"inventoryservice/application/port/common/kafka"
	"inventoryservice/domain/apperror"

	kafkago "github.com/segmentio/kafka-go"
)

const deadLetterRetryInterval = 2 * time.Second

type deadLetterProducer interface {
	Send(ctx context.Context, message kafkago.Message) error
	Close() error
}

type kafkaGoDeadLetterProducer struct {
	deadLetterProducer
	writer *kafkago.Writer
}

func newKafkaGoDeadLetterProducer(brokers []string, topic string) deadLetterProducer {
	writer := &kafkago.Writer{
		Addr:         kafkago.TCP(brokers...),
		Topic:        topic,
		Balancer:     newOriginalPartitionBalancer(),
		RequiredAcks: kafkago.RequireAll,
		BatchSize:    1,
	}

	return &kafkaGoDeadLetterProducer{writer: writer}
}

func (p *kafkaGoDeadLetterProducer) Send(ctx context.Context, message kafkago.Message) error {
	return p.writer.WriteMessages(ctx, message)
}

func (p *kafkaGoDeadLetterProducer) Close() error {
	return p.writer.Close()
}

type deadLetterRetryableProducer struct {
	deadLetterProducer
	producer deadLetterProducer
	interval time.Duration
}

func newDeadLetterRetryableProducer(inner deadLetterProducer, interval time.Duration) deadLetterProducer {
	return &deadLetterRetryableProducer{producer: inner, interval: interval}
}

func (p *deadLetterRetryableProducer) Send(ctx context.Context, message kafkago.Message) error {
	for {
		err := p.producer.Send(ctx, message)
		if err == nil {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}

		slog.Error("failed to publish kafka message to the dead-letter topic, retrying", "key", string(message.Key), "error", err)
		if err = sleep(ctx, p.interval); err != nil {
			return err
		}
	}
}

func (p *deadLetterRetryableProducer) Close() error {
	return p.producer.Close()
}

// newDeadLetterMessage builds the record published to the dead-letter topic for source: the same key,
// payload and headers, so a redrive reuses the same messageId and correlationId, plus headers saying
// where source came from and why it failed. Failure headers from an earlier dead-lettering of the same
// message are dropped rather than duplicated.
func newDeadLetterMessage(source kafkago.Message, groupID string, cause error) kafkago.Message {
	headers := make([]kafkago.Header, 0, len(source.Headers)+6)
	for _, header := range source.Headers {
		if !strings.HasPrefix(header.Key, kafka.DLTHeaderPrefix) {
			headers = append(headers, header)
		}
	}

	headers = append(headers,
		kafkago.Header{Key: kafka.DLTOriginalTopicHeader, Value: []byte(source.Topic)},
		kafkago.Header{Key: kafka.DLTOriginalPartitionHeader, Value: binary.BigEndian.AppendUint32(nil, uint32(source.Partition))},
		kafkago.Header{Key: kafka.DLTOriginalOffsetHeader, Value: binary.BigEndian.AppendUint64(nil, uint64(source.Offset))},
		kafkago.Header{Key: kafka.DLTOriginalConsumerGroupHeader, Value: []byte(groupID)},
		kafkago.Header{Key: kafka.DLTExceptionFQCNHeader, Value: []byte(failureType(cause))},
		kafkago.Header{Key: kafka.DLTExceptionMessageHeader, Value: []byte(cause.Error())},
	)

	return kafkago.Message{Key: source.Key, Value: source.Value, Headers: headers}
}

// failureType fills the header the Java services put the exception class name in: the code of err's
// outermost sentinel - what identifies the failure here - or, for an error without one, the type of
// the innermost error in its chain.
func failureType(err error) string {
	var appErr *apperror.Error
	if errors.As(err, &appErr) {
		return appErr.Code()
	}

	return rootCauseType(err)
}

// rootCauseType names the type of the innermost error in err's chain, the closest Go has to the
// exception class name the Java services put in the same header.
func rootCauseType(err error) string {
	for {
		next := errors.Unwrap(err)
		if next == nil {
			return fmt.Sprintf("%T", err)
		}
		err = next
	}
}

// newOriginalPartitionBalancer routes a dead-lettered message to the same partition number it came
// from (kafka-go's Writer doesn't let a message pick its partition), falling back to hashing the key
// when that partition doesn't exist in the dead-letter topic.
func newOriginalPartitionBalancer() kafkago.Balancer {
	fallback := &kafkago.Hash{}

	return kafkago.BalancerFunc(func(message kafkago.Message, partitions ...int) int {
		for _, header := range message.Headers {
			if header.Key != kafka.DLTOriginalPartitionHeader || len(header.Value) != 4 {
				continue
			}

			if partition := int(binary.BigEndian.Uint32(header.Value)); slices.Contains(partitions, partition) {
				return partition
			}
		}

		return fallback.Balance(message, partitions...)
	})
}
