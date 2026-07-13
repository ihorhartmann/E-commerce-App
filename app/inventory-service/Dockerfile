#1 compiling
FROM golang:1.25-alpine3.24 AS go_builder

ENV CGO_ENABLED=0
WORKDIR /inventory_service
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -o inventory-service .

#2 copying & running
FROM alpine:3.24.1

COPY --from=go_builder /inventory_service/inventory-service /app/inventory-service

CMD ["/app/inventory-service"]
