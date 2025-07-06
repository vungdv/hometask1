package users

import (
	"context"
	"database/sql"
	userv1 "go_app/internal/api/gen/user/v1"
	"go_app/internal/api/gen/user/v1/userv1connect"
	"net/http"

	"connectrpc.com/connect"
	_ "github.com/lib/pq" // PostgreSQL driver
	"go.opentelemetry.io/otel"
)

type userServiceServer struct {
	userv1connect.UnimplementedUserServiceHandler
	UserRepository
}

var tracer = otel.Tracer("app")

func (s userServiceServer) ListUsers(
	ctx context.Context,
	req *connect.Request[userv1.ListUsersRequest],
) (
	res *connect.Response[userv1.ListUsersResponse],
	error error,
) {
	ctx, span := tracer.Start(ctx, "UserService.ListUsers")
	defer span.End()

	users, err := s.ListUser(ctx)
	if err != nil {
		// TODO: logging
		return nil, connect.NewError(connect.CodeInternal, err)
	}

	return connect.NewResponse(&userv1.ListUsersResponse{Users: users}), nil
}

func GetUserServiceHandler(db *sql.DB) (path string, handler http.Handler) {

	userServer := userServiceServer{
		UserRepository: &PostgreUserRepository{
			db: db,
		},
	}
	userServer.StartTimer()
	return userv1connect.NewUserServiceHandler(&userServer)
}
