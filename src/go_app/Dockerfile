# Stage 1 - Build
FROM golang:1.24-alpine AS builder

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
FROM alpine:3.22.0@sha256:8a1f59ffb675680d47db6337b49d22281a139e9d709335b492be023728e11715

# Add CA certificates for HTTPS support if needed
# RUN apk --no-cache add ca-certificates

# Copy binary from builder
COPY --from=builder /app/app /app/app

# Use non-root user if desired (optional)
RUN adduser -D appuser
USER appuser

# Run the app
ENTRYPOINT ["/app/app"]
