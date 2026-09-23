package kafkago

import (
	"strconv"
	"testing"

	"shippingservice/adapter/common/kafka"

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

	t.Run("declares the topics the service consumes, produces to, and the dead-letter topic of the consumed one", func(t *testing.T) {
		topics := declaredTopicsMap(t)

		for _, want := range []string{kafka.ShippingCommandsTopic, kafka.ShippingEventsTopic, kafka.ShippingCommandsDLTTopic} {
			if _, ok := topics[want]; !ok {
				t.Errorf("topic %q is not declared", want)
			}
		}
		if got, want := len(topics), 3; got != want {
			t.Errorf("declared %d topics, want %d", got, want)
		}
	})

	t.Run("gives a dead-letter topic the same partition count as its source topic", func(t *testing.T) {
		topics := declaredTopicsMap(t)

		got := topics[kafka.ShippingCommandsDLTTopic].NumPartitions
		want := topics[kafka.ShippingCommandsTopic].NumPartitions
		if got != want {
			t.Errorf("DLT partitions = %d, want %d", got, want)
		}
	})

	t.Run("gives the dead-letter topic a 30 day retention", func(t *testing.T) {
		topics := declaredTopicsMap(t)

		got := topics[kafka.ShippingCommandsDLTTopic].ConfigEntries

		wantRetention := strconv.FormatInt(30*24*60*60*1000, 10)
		if len(got) != 1 || got[0].ConfigName != "retention.ms" || got[0].ConfigValue != wantRetention {
			t.Errorf("DLT config = %+v, want a single retention.ms=%s", got, wantRetention)
		}
	})

	t.Run("leaves the retention of the source topics at the broker default", func(t *testing.T) {
		topics := declaredTopicsMap(t)

		for _, name := range []string{kafka.ShippingCommandsTopic, kafka.ShippingEventsTopic} {
			if got := topics[name].ConfigEntries; len(got) != 0 {
				t.Errorf("topic %q config = %+v, want none", name, got)
			}
		}
	})
}
