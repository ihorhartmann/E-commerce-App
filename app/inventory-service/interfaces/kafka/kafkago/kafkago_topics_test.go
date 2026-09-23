package kafkago

import (
	"strconv"
	"testing"

	"inventoryservice/application/port/common/kafka"

	kafkago "github.com/segmentio/kafka-go"
)

func Test_declaredTopics(t *testing.T) {
	declaredTopicsMap := func(t *testing.T) map[string]kafkago.TopicConfig {
		t.Helper()

		topics := map[string]kafkago.TopicConfig{}
		for _, topicConfig := range declaredTopics() {
			topics[topicConfig.Topic] = topicConfig
		}

		return topics
	}

	t.Run("declares the topic the service consumes and its dead-letter topic", func(t *testing.T) {
		topics := declaredTopicsMap(t)

		for _, want := range []string{kafka.InventoryCommandsTopic, kafka.InventoryCommandsDLTTopic} {
			if _, ok := topics[want]; !ok {
				t.Errorf("topic %q is not declared", want)
			}
		}
		if got, want := len(topics), 2; got != want {
			t.Errorf("declared %d topics, want %d", got, want)
		}
	})

	t.Run("gives a dead-letter topic the same partition count as its source topic", func(t *testing.T) {
		topics := declaredTopicsMap(t)

		got := topics[kafka.InventoryCommandsDLTTopic].NumPartitions
		want := topics[kafka.InventoryCommandsTopic].NumPartitions
		if got != want {
			t.Errorf("DLT partitions = %d, want %d", got, want)
		}
	})

	t.Run("gives the dead-letter topic a 30 day retention", func(t *testing.T) {
		topics := declaredTopicsMap(t)

		got := topics[kafka.InventoryCommandsDLTTopic].ConfigEntries

		wantRetention := strconv.FormatInt(30*24*60*60*1000, 10)
		if len(got) != 1 || got[0].ConfigName != "retention.ms" || got[0].ConfigValue != wantRetention {
			t.Errorf("DLT config = %+v, want a single retention.ms=%s", got, wantRetention)
		}
	})

	t.Run("leaves the retention of the source topic at the broker default", func(t *testing.T) {
		topics := declaredTopicsMap(t)

		if got := topics[kafka.InventoryCommandsTopic].ConfigEntries; len(got) != 0 {
			t.Errorf("topic %q config = %+v, want none", kafka.InventoryCommandsTopic, got)
		}
	})
}
