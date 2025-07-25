# Ideas

This repo is an experiment & practice environment, that will focus on building stability, observability into a system.

> Leonardo da Vinci started the Mona Lisa around 1503 and possibly worked on it for over a decade.
> For Ronaldo and Messi, every 1.5-hour match is backed by 20–30 hours of intense weekly preparation.

## Run stack

It's tested to run on MAC M1 with docker desktop. I use makefile to build custom command https://www.gnu.org/software/make/manual/make.html
At the root folder run follow command to build images & start the services defined in docker compose.

```
make build
make up
```

## Document

There will be a readme.md file in each of sub-folder to explain their purposes.

## Overview

- docker-compose.override.yml provides infra services such as influxdb, postgreSQL, tempo, loki, prometheus,...
- docker-compose.yml define services for the apps located under ~/src directory
- telemetry configuration for local observability stack: loki, grafana, prometheus, tempo, otel-collector.
- Makefile to build custom, reusable commands

```bash
tree -L 2
.
├── docker-compose.override.yml
├── docker-compose.yml
├── LICENSE
├── local
│   ├── go_app_config.yml
│   └── postgres
├── Makefile
├── README.md
├── src
│   ├── app
│   ├── efcoreddd
│   ├── efcoreddd.unittests
│   ├── go_app
│   └── net9app
├── telemetry
│   ├── blackbox
│   ├── grafana
│   ├── jaeger
│   ├── loki
│   ├── otel-collector-config.yaml
│   ├── prometheus
│   └── tempo
└── tests
    ├── k6
    └── README.md
```
