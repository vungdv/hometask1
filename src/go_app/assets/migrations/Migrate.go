package migrations

import (
	"context"
	"database/sql"
	"go_app/assets"
	"go_app/server"
	"log"

	"github.com/pressly/goose/v3"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/trace"
)

var tracer = otel.Tracer("migrations")

func startTrace(ctx context.Context, name string) (context.Context, trace.Span) {
	return tracer.Start(ctx, "postgres."+name)
}

func Migrate(cfg *server.Config, ctx context.Context) {
	ctx, span := startTrace(ctx, "migrate")
	defer span.End()

	db, err := sql.Open("postgres", cfg.POSTGRES_URL)
	if err != nil {
		log.Fatal("Failed to connect to the database", err)
		return
	}

	defer db.Close()
	goose.SetBaseFS(assets.EmbedMigrations)
	if err := goose.Up(db, assets.PostgresMigrationDir); err != nil {
		log.Fatalf("migration failed: %v", err)
	} else {
		log.Println("Migrations completed")
	}
}
