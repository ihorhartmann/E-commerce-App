package telemetry

import (
	"context"
	"regexp"
	"strings"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

// unknownStatement names a statement whose operation can't be told.
const unknownStatement = "query"

// tableRegex is the table a raw statement works on: after `from` (select, delete), `into` (insert) or `update`.
var tableRegex = regexp.MustCompile(`(?i)\b(?:from|into|update)\s+([\w."]+)`)

// gormOperations maps otelgorm's span names to the SQL operation each runs; a raw statement's (gorm.Raw, gorm.Row) is
// whatever its SQL says.
var gormOperations = map[string]string{
	"gorm.Create": "INSERT",
	"gorm.Query":  "SELECT",
	"gorm.Update": "UPDATE",
	"gorm.Delete": "DELETE",
}

// The attributes otelgorm sets on a span right before ending it.
const (
	statementAttribute = attribute.Key("db.statement")
	tableAttribute     = attribute.Key("db.sql.table")
)

// sqlSpanName is `{OPERATION} {table}`, OpenTelemetry's database span name - `SELECT products` - the same the Spring
// services give theirs; a schema the SQL names is left out of it. operation and statementTable are GORM's for the
// statement; a raw one has neither, so both come from its SQL - with placeholders, not the values bound to them.
// Without a table, the operation alone.
func sqlSpanName(operation, sql, statementTable string) string {
	if operation == "" {
		fields := strings.Fields(sql)
		if len(fields) == 0 {
			return unknownStatement
		}
		operation = strings.ToUpper(fields[0])
	}

	table := statementTable
	if table == "" {
		if match := tableRegex.FindStringSubmatch(sql); match != nil {
			table = strings.ReplaceAll(match[1], `"`, "")
		}
	}
	table = table[strings.LastIndex(table, ".")+1:]
	if table == "" {
		return operation
	}
	return operation + " " + table
}

// sqlSpanTracerProvider hands otelgorm spans that rename themselves `{OPERATION} {table}` when they end, from the
// attributes otelgorm sets on them - otelgorm names its spans gorm.Create, gorm.Query, ... with no option to change
// that.
type sqlSpanTracerProvider struct {
	trace.TracerProvider
}

func NewSQLSpanTracerProvider(provider trace.TracerProvider) trace.TracerProvider {
	return sqlSpanTracerProvider{TracerProvider: provider}
}

func (p sqlSpanTracerProvider) Tracer(name string, options ...trace.TracerOption) trace.Tracer {
	return sqlSpanTracer{Tracer: p.TracerProvider.Tracer(name, options...)}
}

type sqlSpanTracer struct {
	trace.Tracer
}

func (t sqlSpanTracer) Start(ctx context.Context, spanName string, options ...trace.SpanStartOption) (context.Context, trace.Span) {
	ctx, span := t.Tracer.Start(ctx, spanName, options...)
	sqlSpan := &sqlSpan{Span: span, operation: gormOperations[spanName]}
	return trace.ContextWithSpan(ctx, sqlSpan), sqlSpan
}

type sqlSpan struct {
	trace.Span
	operation string
	statement string
	table     string
}

func (s *sqlSpan) SetAttributes(attributes ...attribute.KeyValue) {
	for _, keyValue := range attributes {
		switch keyValue.Key {
		case statementAttribute:
			s.statement = keyValue.Value.AsString()
		case tableAttribute:
			s.table = keyValue.Value.AsString()
		}
	}
	s.Span.SetAttributes(attributes...)
}

func (s *sqlSpan) End(options ...trace.SpanEndOption) {
	s.Span.SetName(sqlSpanName(s.operation, s.statement, s.table))
	s.Span.End(options...)
}
