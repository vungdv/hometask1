package product

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
)

type Product struct {
	Id    int    `json:"id"`
	Title string `json:"title"`
}

type ProductResponse struct {
	Products []Product `json:"products"`
	Total    int       `json:"total"`
	Skip     int       `json:"skip"`
	Limit    int       `json:"limit"`
}

func GetProductsHandle(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	// Parse query parameters
	skipStr := r.URL.Query().Get("skip")
	limitStr := r.URL.Query().Get("limit")

	// Set defaults if empty
	skip, err := strconv.Atoi(skipStr)
	if err != nil {
		skip = 0
	}
	limit, err := strconv.Atoi(limitStr)
	if err != nil {
		limit = 20
	}

	url := fmt.Sprintf("https://dummyjson.com/products?skip=%d&limit=%d", skip, limit)

	response, err := http.Get(url)
	if err != nil {
		log.Fatalf("Failed to make request to: %s with error: %e", url, err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		log.Fatalf("Unexpected status code: %d", response.StatusCode)
	}
	var result ProductResponse
	if err = json.NewDecoder(response.Body).Decode(&result); err != nil {
		log.Fatalf("Failed to deserialize product data: %v", err)
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}
