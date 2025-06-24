package users

import (
	"database/sql"
	"encoding/json"
	"go_app/server"
	"io"
	"log"
	"net/http"

	_ "github.com/lib/pq" // PostgreSQL driver
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/codes"
)

func HandleGetUsers(cfg *server.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		processRequest(cfg, w, r)
	}
}
func processRequest(cfg *server.Config, w http.ResponseWriter, r *http.Request) {
	tr := otel.Tracer("app")

	ctx, span := tr.Start(r.Context(), "main-operation")

	defer span.End()

	db, err := sql.Open("postgres", cfg.POSTGRES_URL)
	if err != nil {
		log.Fatal(err)
		io.WriteString(w, err.Error())
		return
	}
	defer db.Close()

	_, child := tr.Start(ctx, "getUsers")

	users, err := getUsers(ctx, db)

	if err != nil {
		child.RecordError(err)
		child.SetStatus(codes.Error, err.Error())
	}
	child.End()

	if err != nil {
		io.WriteString(w, err.Error())
		log.Fatal(err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(users)
}
