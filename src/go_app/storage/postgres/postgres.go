package postgres

import (
	"context"
	"database/sql"
	"go_app/server"
	"log"
	"time"

	"github.com/cenkalti/backoff/v4"
	"go.uber.org/zap"
)

// In golang and sql.DB, the connections is managed by sql.DB library so don't need
// to mange the life cycle of db instance in compare with c# and IoC.
func InitDB(context context.Context, cfg *server.Config) (*sql.DB, error) {
	db, err := sql.Open("postgres", cfg.POSTGRES_URL)
	if err != nil {
		log.Fatal(err)
		return nil, err
	}

	policy := backoff.NewExponentialBackOff()
	policy.MaxElapsedTime = 1 * time.Minute
	attempt := 1
	err = backoff.Retry(func() error {
		err := db.PingContext(context)
		if err != nil {
			//TODO: improve logger
			log.Println("waiting for database", zap.Int("attempt", attempt))
			attempt++
			return err
		}
		return nil
	}, policy)

	if err != nil {
		return nil, err
	}

	return db, nil
}
