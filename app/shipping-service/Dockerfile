#1 compiling
FROM golang:1.25-alpine3.24 AS go_builder

ENV CGO_ENABLED=0
WORKDIR /shipping_service
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -o shipping-service .

#2 copying & running
FROM alpine:3.24.1

COPY --from=go_builder /shipping_service/shipping-service /app/shipping-service

CMD ["/app/shipping-service"]
