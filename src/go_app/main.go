package main

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"time"

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/baggage"
	"go.opentelemetry.io/otel/trace"
)

func main() {
	if err := run(); err != nil {
		log.Fatalln(err)
	}
}

func run() (err error) {
	// Handle SIGINT (CTRL+C) gracefully.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	// Set up OpenTelemetry.
	otelShutdown, err := setupOTelSDK(ctx)
	if err != nil {
		return
	}
	// Handle shutdown properly so nothing leaks.
	defer func() {
		err = errors.Join(err, otelShutdown(context.Background()))
	}()

	// Start HTTP server.
	srv := &http.Server{
		Addr:         ":8080",
		BaseContext:  func(_ net.Listener) context.Context { return ctx },
		ReadTimeout:  time.Second,
		WriteTimeout: 10 * time.Second,
		Handler:      newHTTPHandler(),
	}
	srvErr := make(chan error, 1)
	go func() {
		srvErr <- srv.ListenAndServe()
	}()

	// Wait for interruption.
	select {
	case err = <-srvErr:
		// Error when starting HTTP server.
		return
	case <-ctx.Done():
		// Wait for first CTRL+C.
		// Stop receiving signal notifications as soon as possible.
		stop()
	}

	// When Shutdown is called, ListenAndServe immediately returns ErrServerClosed.
	err = srv.Shutdown(context.Background())
	return
}

func newHTTPHandler() http.Handler {
	mux := http.NewServeMux()

	// handleFunc is a replacement for mux.HandleFunc
	// which enriches the handler's HTTP instrumentation with the pattern as the http.route.
	handleFunc := func(pattern string, handlerFunc func(http.ResponseWriter, *http.Request)) {
		// Configure the "http.route" for the HTTP instrumentation.
		handler := otelhttp.WithRouteTag(pattern, withTenantTag(http.HandlerFunc(handlerFunc)))
		mux.Handle(pattern, handler)
	}

	// Register handlers.
	handleFunc("/rolldice/", rolldice)
	handleFunc("/rolldice/{player}", rolldice)
	handleFunc("/hello", helloHandler)

	// Add HTTP instrumentation for the whole server.
	handler := otelhttp.NewHandler(mux, "/")
	return handler
}

// Middleware: extract "tenant-id" from baggage and tag it
func withTenantTag(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		span := trace.SpanFromContext(ctx)

		// Extract "tenant-id" from baggage
		const tenantKey = "tenant_id"
		const userKey = "user_id"

		bag := baggage.FromContext(ctx)
		member := bag.Member(tenantKey)
		if member.Value() != "" && span != nil {
			span.SetAttributes(attribute.String(tenantKey, member.Value()))
		} else {
			span.SetAttributes(attribute.String(tenantKey, "empty"))
		}

		userid := bag.Member(userKey)
		if userid.Value() != "" && span != nil {
			span.SetAttributes(attribute.String(userKey, userid.Value()))
		} else {
			span.SetAttributes(attribute.String(userKey, "empty"))
		}
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}
