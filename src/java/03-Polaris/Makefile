.PHONY: pull build up down

pull: 
	docker compose pull
build:
	docker compose build
up:
	docker compose up -d
down: 
	docker compose down --volumes --remove-orphans
restart-%:
	docker compose restart $*
gcx: 
	docker exec -it gcx-cli sh
agy-ls:
	ls -t ~/.gemini/antigravity-cli/brain | head -n 10
