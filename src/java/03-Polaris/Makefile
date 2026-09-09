.PHONY: pull build up down validate-docs setup-hooks

pull: 
	docker compose pull
build:
	docker compose build
up:
	docker compose up -d --build
down: 
	docker compose down --volumes --remove-orphans
restart-%:
	docker compose restart $*
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