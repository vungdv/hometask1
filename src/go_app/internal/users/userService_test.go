package users

import (
	"context"
	"errors"
	userv1 "go_app/internal/api/gen/user/v1"
	"testing"

	"connectrpc.com/connect"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
)

type MockUserRepository struct {
	mock.Mock
}

func (m *MockUserRepository) ListUser(ctx context.Context) ([]*userv1.User, error) {
	args := m.Called(ctx)
	return args.Get(0).([]*userv1.User), args.Error(1)
}
func (m *MockUserRepository) StartTimer() {
	// This method is not used in the tests, but required by the interface
	// so we can leave it empty or implement a mock if needed.
}
func TestListUsers(t *testing.T) {
	mockRepo := new(MockUserRepository)
	expected := []*userv1.User{{Id: 1, Name: "Alice", Email: "a@example.com"}}

	// define what the mock should return
	mockRepo.On("ListUser", mock.Anything).Return(expected, nil)

	handler := &userServiceServer{UserRepository: mockRepo}
	req := connect.NewRequest(&userv1.ListUsersRequest{})

	res, err := handler.ListUsers(context.Background(), req)
	require.NoError(t, err)
	require.Equal(t, expected[0].Name, res.Msg.Users[0].Name)

	mockRepo.AssertExpectations(t) // verify all expected calls were made
}

func TestListUsersError(t *testing.T) {
	mockRepo := new(MockUserRepository)
	mockRepo.
		On("ListUser", mock.Anything).
		Return([]*userv1.User(nil), errors.New("DB down"))

	handler := userServiceServer{UserRepository: mockRepo}

	req := connect.NewRequest(&userv1.ListUsersRequest{})
	res, err := handler.ListUsers(context.Background(), req)

	assert.Nil(t, res)
	assert.Error(t, err)
	assert.Equal(t, connect.CodeInternal, connect.CodeOf(err))

	mockRepo.AssertExpectations(t)
}
