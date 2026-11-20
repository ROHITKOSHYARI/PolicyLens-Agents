.PHONY: up down test test-gateway test-agent build seed eval demo

up:
	docker compose up --build -d

down:
	docker compose down

test: test-gateway test-agent

test-gateway:
	cd gateway && mvn test

test-agent:
	cd agent-service && python -m pytest

build:
	cd gateway && mvn -DskipTests package
	docker compose build

seed:
	@echo "Synthetic data seeding is added in a later phase."

eval:
	@echo "Evaluation runner is added in a later phase."

demo:
	@echo "Demo script is added in a later phase."
