package migrations

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/pressly/goose/v3"
)

func init() {
	goose.AddMigrationContext(Up20250624084309, Down20250624084309)
}

func Up20250624084309(ctx context.Context, tx *sql.Tx) error {
	// This code is executed when the migration is applied.
	fmt.Println("Seeding initial users...")

	users := []struct {
		name  string
		email string
	}{
		{"Alice", "alice@example.com"},
		{"Bob", "bob@example.com"},
	}

	for _, u := range users {
		_, err := tx.Exec("INSERT INTO users (name, email) VALUES ($1, $2)", u.name, u.email)
		if err != nil {
			return err
		}
	}

	return nil
}

func Down20250624084309(ctx context.Context, tx *sql.Tx) error {
	// This code is executed when the migration is rolled back.
	_, err := tx.Exec(`DELETE FROM users WHERE email IN ('alice@example.com', 'bob@example.com', 'charlie@example.com')`)
	return err
}
