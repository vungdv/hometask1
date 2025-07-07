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
clean:
	docker rm -f $$(docker ps -aq) 2>/dev/null || true
	docker volume rm $$(docker volume ls -q) 2>/dev/null || true
	docker image prune

K6_LOOP ?= false

k6:
ifeq ($(K6_LOOP),true)
	docker run --rm \
		--name k6 \
		--network hometask1_default \
		-e APP_BaseUrl=http://app:8080 \
		-e GO_APP_BaseUrl=http://go_app:8080 \
		-v $(CURDIR)/tests/k6:/k6 \
		--entrypoint sh \
		grafana/k6 \
		-c 'while true; do sh /k6/run-all.sh; echo sleeping...; sleep 5; done'
else
	docker run --rm \
		--name k6 \
		--network hometask1_default \
		-e APP_BaseUrl=http://app:8080 \
		-e GO_APP_BaseUrl=http://go_app:8080 \
		-v $(CURDIR)/tests/k6:/k6 \
		--entrypoint sh \
		grafana/k6 \
		/k6/run-all.sh
endif

