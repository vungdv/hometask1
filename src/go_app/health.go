package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"time"

	"github.com/redis/go-redis/v9"
)

// Redis configuration
const (
	redisAddr     = "redis:6379"
	redisPassword = ""
	redisDB       = 0
)

// HealthCheckResult struct to hold the health status
type HealthCheckResult struct {
	Status    string `json:"status"`
	Timestamp string `json:"timestamp"`
}

// checkRedisHealth function to check redis connection
func checkRedisHealth(ctx context.Context, client *redis.Client) error {
	_, err := client.Ping(ctx).Result()
	return err
}

// healthCheckHandler function to handle the health check request
func healthCheckHandler(client *redis.Client) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()

		err := checkRedisHealth(ctx, client)
		var result HealthCheckResult
		if err != nil {
			result = HealthCheckResult{
				Status:    "unhealthy",
				Timestamp: time.Now().Format(time.RFC3339),
			}
			w.WriteHeader(http.StatusInternalServerError)
		} else {
			result = HealthCheckResult{
				Status:    "healthy",
				Timestamp: time.Now().Format(time.RFC3339),
			}
			w.WriteHeader(http.StatusOK)
		}

		w.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(w).Encode(result); err != nil {
			log.Printf("Error encoding JSON: %v", err)
			http.Error(w, "Internal Server Error", http.StatusInternalServerError)
		}
	}
}

func getRedis() *redis.Client {
	ctx := context.Background()

	// Create a Redis client
	client := redis.NewClient(&redis.Options{
		Addr:     redisAddr,
		Password: redisPassword,
		DB:       redisDB,
	})

	// Test Redis connection
	if err := checkRedisHealth(ctx, client); err != nil {
		log.Fatalf("Failed to connect to Redis: %v", err)
	} else {
		fmt.Println("Successfully connected to Redis")
	}

	return client
}
