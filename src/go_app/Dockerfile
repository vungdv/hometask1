# Stage 1 - Build
FROM golang:1.23-alpine AS builder

# Set necessary environment variables
ENV CGO_ENABLED=0 GOOS=linux GOARCH=amd64

WORKDIR /app

# Cache dependencies
COPY go.mod go.sum ./
RUN go mod download

# Copy source
COPY . .

# Build the binary
RUN go build -o app

# Stage 2 - Minimal runtime image
FROM alpine:latest

# Add CA certificates for HTTPS support if needed
# RUN apk --no-cache add ca-certificates

# Copy binary from builder
COPY --from=builder /app/app /app/app

# Use non-root user if desired (optional)
# RUN adduser -D appuser
# USER appuser

# Run the app
ENTRYPOINT ["/app/app"]
