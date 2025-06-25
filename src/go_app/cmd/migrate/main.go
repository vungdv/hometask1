package main

import (
	"context"
	"errors"

	"go_app/assets/migrations/postgres"
	"go_app/server"
	"go_app/telemetry"
	"log"
	"os"
	"os/signal"
)

func main() {
	// Handle SIGINT (CTRL+C) gracefully.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	// Set up OpenTelemetry.
	otelShutdown, err := telemetry.SetupOTelSDK(ctx)

	// Handle shutdown properly so nothing leaks.
	defer func() {
		err = errors.Join(err, otelShutdown(context.Background()))
	}()

	config, err := server.LoadConfig()
	if err != nil {
		log.Fatal(err)
	}

	//Run migration
	postgres.Migrate(config, ctx)

}
