package outbox

import (
	"context"
	"time"

	"shippingservice/adapter/out/outbox/row"
	"shippingservice/adapter/out/repository/transaction"
	"shippingservice/application/port/out/outbox"
	"shippingservice/application/port/out/outbox/dto"

	"gorm.io/gorm"
)

type postgresOutboxMessageRepository struct {
	outbox.MessageRepository
	db *gorm.DB
}

func NewPostgresOutboxMessageRepository(db *gorm.DB) outbox.MessageRepository {
	return &postgresOutboxMessageRepository{db: db}
}

func (r *postgresOutboxMessageRepository) Insert(ctx context.Context, message dto.OutboxMessage) error {
	tx, err := transaction.From(ctx)
	if err != nil {
		return err
	}

	return tx.WithContext(ctx).Create(row.ToOutboxMessageRow(message)).Error
}

func (r *postgresOutboxMessageRepository) FetchUnsent(ctx context.Context, limit int) ([]*dto.OutboxMessage, error) {
	var rows []row.OutboxMessage
	if err := r.db.WithContext(ctx).
		Where("sent_at IS NULL").
		Order("created_at").
		Limit(limit).
		Find(&rows).Error; err != nil {
		return nil, err
	}

	messages := make([]*dto.OutboxMessage, 0, len(rows))
	for i := range rows {
		message := row.FromOutboxMessageRow(&rows[i])
		messages = append(messages, &message)
	}

	return messages, nil
}

func (r *postgresOutboxMessageRepository) MarkSentBatch(ctx context.Context, messageIDs []string, sentAt time.Time) error {
	if len(messageIDs) == 0 {
		return nil
	}

	result := r.db.WithContext(ctx).Model(&row.OutboxMessage{}).Where("id IN ?", messageIDs).Update("sent_at", sentAt)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected != int64(len(messageIDs)) {
		return outbox.ErrOutboxMessageNotFound
	}

	return nil
}
