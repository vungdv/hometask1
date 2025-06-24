package assets

import "embed"

const (
	PostgresMigrationDir = "migrations/postgres"
)

// EmbedMigrations within the go_app binary.
//
//go:embed migrations/*
var EmbedMigrations embed.FS
