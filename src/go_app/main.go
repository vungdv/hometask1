package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"time"

	"go_app/hello" // Replace with the actual path of generated code

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.17.0"
	"google.golang.org/grpc"
	"google.golang.org/protobuf/proto"
)

func helloHandler(w http.ResponseWriter, r *http.Request) {
	defer r.Body.Close()
	body, _ := io.ReadAll(r.Body)

	var req hello.HelloRequest
	err := proto.Unmarshal(body, &req)
	if err != nil {
		http.Error(w, "Invalid Protobuf", http.StatusBadRequest)
		return
	}

	resp := &hello.HelloReply{
		Message: fmt.Sprintf("Hello, %s %s!", req.Name, time.DateTime),
	}
	respBytes, _ := proto.Marshal(resp)

	w.Header().Set("Content-Type", "application/x-protobuf")
	w.Write(respBytes)
}

func main() {
	ctx := context.Background()

	// Initialize OTLP exporter over gRPC (default endpoint: localhost:4317)
	exporter, err := otlptracegrpc.New(ctx,
		otlptracegrpc.WithInsecure(), // or use WithTLSCredentials
		otlptracegrpc.WithEndpoint("otel-collector:4317"),
		otlptracegrpc.WithDialOption(grpc.WithBlock()),
	)

	if err != nil {
		log.Fatalf("failed to create OTLP exporter: %v", err)
	}

	res, err := resource.New(ctx,
		resource.WithAttributes(
			semconv.ServiceName("go_app"),
		),
	)
	if err != nil {
		log.Fatalf("Failed to create resource: %v", err)
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	defer func() {
		_ = tp.Shutdown(ctx)
	}()

	otel.SetTracerProvider(tp)

	// Wrap handler with otelhttp middleware
	http.Handle("/hello", otelhttp.NewHandler(http.HandlerFunc(helloHandler), "HelloHandler"))

	fmt.Println("Go Protobuf server listening on :8080")
	log.Fatal(http.ListenAndServe(":8080", nil))
}
