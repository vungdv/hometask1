package users

import (
	"context"
	"database/sql"
	userv1 "go_app/internal/api/gen/user/v1"
	"log"
)

type UserRepository interface {
	ListUser(ctx context.Context) ([]*userv1.User, error)
}

type PostgreUserRepository struct {
	db *sql.DB
}

func (repo *PostgreUserRepository) ListUser(ctx context.Context) ([]*userv1.User, error) {

	rows, err := repo.db.QueryContext(ctx, "SELECT id, name, email FROM users")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var users []*userv1.User
	for rows.Next() {
		var u userv1.User
		if err := rows.Scan(&u.Id, &u.Name, &u.Email); err != nil {
			log.Fatal(err)
			return nil, err
		}
		users = append(users, &u)
	}

	return users, nil
}
