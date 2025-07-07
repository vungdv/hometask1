package users

import (
	"context"
	"database/sql"
	"fmt"
	userv1 "go_app/internal/api/gen/user/v1"
	"log"
	"math/rand"
	"sync/atomic"
	"time"
)

type UserRepository interface {
	ListUser(ctx context.Context) ([]*userv1.User, error)
	StartTimer() // Method to start the timer for error rate
}

type PostgreUserRepository struct {
	db *sql.DB
}

func (repo *PostgreUserRepository) ListUser(ctx context.Context) ([]*userv1.User, error) {
	// mock for chaos (random error) which can be used to test circuit breaker and retry policies
	if shouldReturnError() {
		return nil, fmt.Errorf("random error occurred with code")
	}
	rows, err := repo.db.QueryContext(ctx, `
		SELECT id, name, email 
		FROM users
	`)

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
func (repo *PostgreUserRepository) StartTimer() {
	// Start ticker to reset error count every minute
	go func() {
		for {
			// Randomize errors per minute between 1 and 20
			atomic.StoreInt64(&errorRatePerMinute, int64(rand.Intn(20)+1))
			atomic.StoreInt64(&errorCountThisMinute, 0)
			log.Printf("New error rate: %d errors/min", errorRatePerMinute)
			time.Sleep(time.Minute)
		}
	}()
}

var (
	errorRatePerMinute   int64 // errors per minute
	errorCountThisMinute int64
)

func shouldReturnError() bool {
	currErrors := atomic.LoadInt64(&errorCountThisMinute)
	maxErrors := atomic.LoadInt64(&errorRatePerMinute)

	// If we already served enough errors, return success
	if currErrors >= maxErrors {
		return false
	}

	// Randomly decide to return error with some probability
	// Higher chance when fewer errors have occurred
	chance := float64(maxErrors-currErrors) / 10.0
	if rand.Float64() < chance {
		atomic.AddInt64(&errorCountThisMinute, 1)
		return true
	}
	return false
}
