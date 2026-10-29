package telemetry

import (
	"context"
	"testing"

	"github.com/uptrace/opentelemetry-go-extra/otelgorm"
	"go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/sdk/trace/tracetest"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

func TestSqlSpanName(t *testing.T) {
	t.Run("names a statement by GORM's operation and table", func(t *testing.T) {
		for _, test := range []struct{ operation, sql, table, want string }{
			{"INSERT", `INSERT INTO "products" ("name") VALUES ($1) RETURNING "id"`, "products", "INSERT products"},
			{"SELECT", `SELECT * FROM "stock_reservations" WHERE order_id = $1`, "stock_reservations", "SELECT stock_reservations"},
			{"UPDATE", `UPDATE "products" SET "stock"=$1 WHERE id = $2 AND version = $3`, "products", "UPDATE products"},
			{"DELETE", `DELETE FROM "stock_reservations" WHERE id = $1`, "stock_reservations", "DELETE stock_reservations"},
		} {
			if got := sqlSpanName(test.operation, test.sql, test.table); got != test.want {
				t.Errorf("sqlSpanName(%q) = %q, want %q", test.sql, got, test.want)
			}
		}
	})

	t.Run("names a raw statement by the operation and table its SQL names", func(t *testing.T) {
		want := "DELETE idempotent_http_requests"

		if got := sqlSpanName("", "DELETE FROM idempotent_http_requests WHERE created_at < $1", ""); got != want {
			t.Errorf("sqlSpanName = %q, want %q", got, want)
		}
	})

	t.Run("leaves out a schema the SQL names", func(t *testing.T) {
		want := "SELECT products"

		if got := sqlSpanName("", `SELECT * FROM "other"."products"`, ""); got != want {
			t.Errorf("sqlSpanName = %q, want %q", got, want)
		}
	})

	t.Run("is the operation alone when no table is found", func(t *testing.T) {
		if got := sqlSpanName("", "SELECT 1", ""); got != "SELECT" {
			t.Errorf("sqlSpanName = %q, want %q", got, "SELECT")
		}
	})

	t.Run("is query when there is no statement", func(t *testing.T) {
		if got := sqlSpanName("", "", ""); got != unknownStatement {
			t.Errorf("sqlSpanName = %q, want %q", got, unknownStatement)
		}
	})
}

type widget struct {
	ID   uint
	Name string
}

func TestNewSQLSpanTracerProvider(t *testing.T) {
	t.Run("names otelgorm's spans after the statement's operation and table", func(t *testing.T) {
		recorder := tracetest.NewSpanRecorder()
		provider := NewSQLSpanTracerProvider(trace.NewTracerProvider(trace.WithSpanProcessor(recorder)))
		// dry run: GORM builds every statement without a database to send it to
		db, err := gorm.Open(postgres.New(postgres.Config{DSN: "host=localhost"}), &gorm.Config{DryRun: true, DisableAutomaticPing: true})
		if err != nil {
			t.Fatalf("open: %v", err)
		}
		if err = db.Use(otelgorm.NewPlugin(otelgorm.WithTracerProvider(provider), otelgorm.WithoutMetrics(), otelgorm.WithDryRunTx())); err != nil {
			t.Fatalf("otelgorm: %v", err)
		}
		ctx := context.Background()

		db.WithContext(ctx).Create(&widget{Name: "a"})
		db.WithContext(ctx).Find(&[]widget{})
		db.WithContext(ctx).Model(&widget{ID: 1}).Update("name", "b")
		db.WithContext(ctx).Delete(&widget{ID: 1})
		db.WithContext(ctx).Exec("DELETE FROM widgets WHERE id = ?", 1)

		var got []string
		for _, span := range recorder.Ended() {
			got = append(got, span.Name())
		}
		want := []string{
			"INSERT widgets", "SELECT widgets", "UPDATE widgets", "DELETE widgets",
			// a raw statement, by its SQL
			"DELETE widgets",
		}
		if len(got) != len(want) {
			t.Fatalf("span names = %v, want %v", got, want)
		}
		for i := range want {
			if got[i] != want[i] {
				t.Errorf("span %d name = %q, want %q", i, got[i], want[i])
			}
		}
	})
}
