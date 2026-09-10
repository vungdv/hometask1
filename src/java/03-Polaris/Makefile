.PHONY: pull build up down validate-docs setup-hooks test status playwright-ui playwright-close run

run:
	mvn spring-boot:run -pl apps/polaris-server

pull: 
	docker compose pull
build:
	docker compose build
up:
	docker compose up -d --build
down: 
	docker compose down --volumes --remove-orphans
status:
	docker compose ps
restart-%:
	docker compose restart $*
test:
	mvn clean test
gcx: 
	docker exec -it gcx-cli sh
agy-ls:
	ls -t ~/.gemini/antigravity-cli/brain | head -n 10
validate-docs:
	node scripts/validate-mermaid.mjs --all
setup-hooks:
	node scripts/validate-mermaid.mjs --install-git-hook
polaris-sql:
	docker compose exec polaris-db psql -U polaris -d polaris
playwright-ui:
	playwright-cli open https://polaris.local/swagger-ui/index.html
playwright-close:
	playwright-cli close-all