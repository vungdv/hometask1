# Stage 1 - Build
FROM golang:1.24-alpine AS builder

# Set necessary environment variables
ENV CGO_ENABLED=0 GOOS=linux GOARCH=amd64

WORKDIR /app
# install and cache dependencies
RUN --mount=type=cache,target=/root/go/pkg/mod,id=go-pkg-cache \
    --mount=type=bind,source=go.sum,target=go.sum \
    --mount=type=bind,source=go.mod,target=go.mod \
    go mod download -x

# build with cache
RUN --mount=type=cache,target=/root/.cache/go-build \
    --mount=type=cache,target=/root/go/pkg/mod,id=go-pkg-cache \
    --mount=type=bind,target=. \
    CGO_ENABLED=0 go build -o /api ./cmd/api &&  go build -o /migrate ./cmd/migrate && chmod +x /migrate

# Stage 2 - Minimal runtime image
FROM alpine:3.22.0@sha256:8a1f59ffb675680d47db6337b49d22281a139e9d709335b492be023728e11715
WORKDIR /app
# Add CA certificates for HTTPS support if needed
# RUN apk --no-cache add ca-certificates

# Copy binary from builder
COPY --from=builder /migrate /migrate
COPY --from=builder /api /api

# Use non-root user if desired (optional)
RUN adduser -D appuser
USER appuser

# Run the app
ENTRYPOINT ["/api"]
