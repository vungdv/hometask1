.PHONY: pull build up down validate-docs setup-hooks render-bpmn test test-perf test-concurrency e2e-fulfilment e2e-fulfilment gcx gcx-exec status playwright-ui playwright-close run run-assistant run-fulfilment verify-fulfilment-image clean polaris-sql kafka-topics kafka-tail kafka-cluster kafka-offsets kafka-groups kafka-leaders schema-register schema-check schema-subjects

run:
	mvn spring-boot:run -pl apps/polaris

run-assistant:
	mvn spring-boot:run -pl apps/polaris-assistant

run-fulfilment:
	mvn spring-boot:run -pl apps/polaris-fulfilment-emulator

verify-fulfilment-image:
	./scripts/verify-fulfilment-image.sh

pull: 
	docker compose pull
build:
	docker compose build
up:
	docker compose up -d --build
down: 
	docker compose down
clean:
	docker compose down --volumes --remove-orphans
status:
	docker compose ps
restart-%:
	docker compose restart $*
test:
	mvn clean test
test-perf:
	docker run --network host --rm -i -v $$(pwd)/tests/perf:/scripts -w /scripts grafana/k6 run api-test.js
test-concurrency:
	docker run --network host --rm -i -v $$(pwd)/tests/perf:/scripts -w /scripts -e SKU=NG-CHARGER-02 grafana/k6 run order-concurrency-test.js
e2e-fulfilment:
	./tests/e2e/run-fulfilment.sh
gcx: 
	docker exec -it gcx-cli sh
gcx-exec:
	docker exec gcx-cli gcx $(CMD)
agy-ls:
	ls -t ~/.gemini/antigravity-cli/brain | head -n 10
validate-docs:
	node scripts/validate-mermaid.mjs --all
setup-hooks:
	node scripts/validate-mermaid.mjs --install-git-hook
render-bpmn:
	node scripts/render-bpmn-diagram.mjs --all
render-bpmn-%:
	node scripts/render-bpmn-diagram.mjs docs/business/bpmn/$*.bpmn
polaris-sql:
	docker compose exec polaris-db psql -U polaris -d polaris
KAFKA_BIN := /opt/kafka/bin
KAFKA_BOOTSTRAP := kafka-1:9092,kafka-2:9092,kafka-3:9092
# Admin commands run inside a node; any running node works (NODE=2 if kafka-1 is stopped).
NODE ?= 1
KAFKA_EXEC = docker compose exec kafka-$(NODE)
kafka-topics:
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-topics.sh --bootstrap-server $(KAFKA_BOOTSTRAP) --describe --exclude-internal
kafka-tail:
	@test -n "$(TOPIC)" || { echo "usage: make kafka-tail TOPIC=<topic>"; exit 1; }
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-console-consumer.sh --bootstrap-server $(KAFKA_BOOTSTRAP) --topic $(TOPIC) --from-beginning \
		--property print.timestamp=true --property print.partition=true --property print.offset=true \
		--property print.headers=true --property print.key=true
kafka-cluster:
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-metadata-quorum.sh --bootstrap-server $(KAFKA_BOOTSTRAP) describe --status
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-metadata-quorum.sh --bootstrap-server $(KAFKA_BOOTSTRAP) describe --replication
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-topics.sh --bootstrap-server $(KAFKA_BOOTSTRAP) --describe --under-replicated-partitions
kafka-offsets:
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-get-offsets.sh --bootstrap-server $(KAFKA_BOOTSTRAP) --topic $(or $(TOPIC),polaris.order.lifecycle)
kafka-groups:
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-consumer-groups.sh --bootstrap-server $(KAFKA_BOOTSTRAP) --describe --all-groups
kafka-leaders:
	$(KAFKA_EXEC) $(KAFKA_BIN)/kafka-leader-election.sh --bootstrap-server $(KAFKA_BOOTSTRAP) --election-type preferred --all-topic-partitions
kafka-stop-%:
	docker compose stop kafka-$*
kafka-start-%:
	docker compose start kafka-$*
# Schema Registry (ADR-0021). schema-init already runs on `make up`; these re-run the gate by hand.
schema-register:
	docker compose run --rm schema-init register
# make schema-check SUBJECT=polaris.order.lifecycle-value FILE=path/to/candidate.avsc  (registers nothing)
schema-check:
	@test -n "$(SUBJECT)" -a -f "$(FILE)" || { echo "usage: make schema-check SUBJECT=<topic>-value FILE=<schema.avsc>"; exit 1; }
	docker compose run --rm -v "$(abspath $(FILE)):/candidate.avsc:ro" schema-init check $(SUBJECT) /candidate.avsc
schema-subjects:
	@curl -s http://localhost:8085/subjects; echo
	@curl -s http://localhost:8085/config/polaris.order.lifecycle-value; echo
playwright-ui:
	playwright-cli open https://polaris.local/swagger-ui/index.html
playwright-close:
	playwright-cli close-all

