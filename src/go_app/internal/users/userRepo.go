package users

import (
	"context"
	"database/sql"
)

func getUsers(ctx context.Context, db *sql.DB) ([]User, error) {

	rows, err := db.QueryContext(ctx, "SELECT id, name, email FROM users")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var users []User
	for rows.Next() {
		var u User
		if err := rows.Scan(&u.ID, &u.Name, &u.Email); err != nil {
			return nil, err
		}
		users = append(users, u)
	}

	return users, nil
}
