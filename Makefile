.PHONY: pull build up down

pull: 
	docker compose pull
build:
	docker compose build
up:
	docker compose up -d
down: 
	docker compose down --remove-orphans
restart-otel: 
	docker compose restart otel-collector
docker-clean:
	docker rm -f $$(docker ps -aq) 2>/dev/null || true
	docker volume rm $$(docker volume ls -q) 2>/dev/null || true
