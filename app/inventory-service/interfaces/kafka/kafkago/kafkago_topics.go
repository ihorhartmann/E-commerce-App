package kafkago

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"time"

	"inventoryservice/application/port/common/kafka"

	kafkago "github.com/segmentio/kafka-go"
)

const (
	// A topic and its DLT get the same partition count so a dead-lettered message lands on the same
	// partition number it came from (see newOriginalPartitionBalancer).
	topicPartitions        = 1
	topicReplicationFactor = 1

	dltRetention        = 30 * 24 * time.Hour
	retentionConfigName = "retention.ms"

	declareTopicsTimeout       = 60 * time.Second
	declareTopicsRetryInterval = 2 * time.Second
	brokerCallTimeout          = 10 * time.Second
)

func declaredTopics() []kafkago.TopicConfig {
	return []kafkago.TopicConfig{
		topicConfig(kafka.InventoryCommandsTopic),
		deadLetterTopic(kafka.InventoryCommandsDLTTopic),
	}
}

func topicConfig(name string) kafkago.TopicConfig {
	return kafkago.TopicConfig{
		Topic:             name,
		NumPartitions:     topicPartitions,
		ReplicationFactor: topicReplicationFactor,
	}
}

func deadLetterTopic(name string) kafkago.TopicConfig {
	config := topicConfig(name)
	config.ConfigEntries = []kafkago.ConfigEntry{
		{ConfigName: retentionConfigName, ConfigValue: strconv.FormatInt(dltRetention.Milliseconds(), 10)},
	}

	return config
}

// CreateTopics creates every topic in declaredTopics that doesn't exist yet and leaves existing ones
// alone, so no topic depends on the broker auto-creating it. It keeps retrying while the broker is
// unreachable, up to declareTopicsTimeout, and then fails: the service must not start without them.
func CreateTopics(ctx context.Context, brokers []string) error {
	ctx, cancel := context.WithTimeout(ctx, declareTopicsTimeout)
	defer cancel()

	client := &kafkago.Client{Addr: kafkago.TCP(brokers...)}
	topics := declaredTopics()

	for {
		err := createTopics(ctx, client, topics)
		if err == nil {
			return nil
		}

		slog.Warn("failed to declare kafka topics, retrying", "error", err)
		if sleepErr := sleep(ctx, declareTopicsRetryInterval); sleepErr != nil {
			return fmt.Errorf("failed to declare kafka topics: %w", err)
		}
	}
}

func createTopics(ctx context.Context, client *kafkago.Client, topics []kafkago.TopicConfig) error {
	callCtx, cancel := context.WithTimeout(ctx, brokerCallTimeout)
	defer cancel()

	resp, err := client.CreateTopics(callCtx, &kafkago.CreateTopicsRequest{Addr: client.Addr, Topics: topics})
	if err != nil {
		return err
	}

	var errs []error
	for name, err := range resp.Errors {
		if err != nil && !errors.Is(err, kafkago.TopicAlreadyExists) {
			errs = append(errs, fmt.Errorf("topic %q: %w", name, err))
		}
	}

	return errors.Join(errs...)
}
