# Observability

Three signals, all verified against the running stack: metrics
(Micrometer to Prometheus), traces (Micrometer Tracing over the
OpenTelemetry bridge), structured logs (ECS JSON with correlation and
trace ids).

## Metrics

`/actuator/prometheus` exposes standard JVM/HTTP/Hikari/Kafka-client
metrics plus domain metrics that answer the questions an on-call engineer
actually asks:

| Metric | Question it answers |
|---|---|
| `ledgerflow_movements_total{type,result}` | are transfers/payments flowing, and how many hit INSUFFICIENT_FUNDS |
| `ledgerflow_outbox_pending` | is the event pipeline backing up (Kafka down shows here first) |
| `ledgerflow_outbox_events_total{result}` | publish success vs failed attempts |
| `ledgerflow_db_transient_retries_total` | deadlock / serialization pressure |
| `ledgerflow_cache_gets_total{result}` | Redis hit ratio; a falling ratio means eviction storms or an outage |
| `ledgerflow_ratelimit_rejections_total` | is someone hammering the money endpoints |
| `http_server_requests_seconds` (histogram) | p50/p95/p99 latency, request and error rate |
| `hikaricp_connections_*` | pool saturation before it becomes an outage |
| `kafka_consumer_fetch_manager_records_lag_max` | consumer lag per listener |

`http.server.requests` publishes full histograms
(`percentiles-histogram: true`), so quantiles are computed in Prometheus
with `histogram_quantile()` and can be aggregated across instances.

## Dashboards

`docker compose up` brings Prometheus (:9090) and Grafana (:3000,
admin/admin dev-only) with everything provisioned from the repo, no
clicking: datasource + the "LedgerFlow" dashboard
([deploy/observability/grafana/dashboards/ledgerflow.json](../deploy/observability/grafana/dashboards/ledgerflow.json)):
request rate by endpoint, 5xx rate, latency quantiles, money movements by
type/result, Hikari pool, outbox pending + publish rate, cache hit ratio,
rate-limit rejections, transient retries, consumer lag, JVM heap.

## Traces

Micrometer Tracing with the OTel bridge instruments HTTP server/client,
JDBC-adjacent spans and Kafka templates; trace and span ids are stamped
into every log line, and W3C `traceparent` propagates outward. Export is
deliberately opt-in (`OTLP_TRACING_ENABLED=true`, `OTLP_ENDPOINT=...`):
the dev stack does not run a trace backend by default, and pretending
otherwise would be decorative. Pointing the exporter at any OTLP collector
(Tempo, Jaeger, vendor) requires no code change.

## Logs

Default profile: human-readable console. With
`SPRING_PROFILES_ACTIVE=json-logs` (used by containers/Kubernetes), Spring
Boot's built-in structured logging emits ECS JSON per line:

```json
{"@timestamp":"2026-08-11T18:37:19.004Z","log":{"level":"INFO",...},
 "service":{"name":"ledgerflow","version":"0.1.0-SNAPSHOT"},"message":"..."}
```

Every request carries `correlationId` (accepted or minted by
CorrelationIdFilter, echoed in responses, stored on transactions, outbox
events and audit rows), so one id follows a payment from HTTP through SQL
to Kafka consumers and into the audit trail.

## Verification performed

- Custom metrics visible on `/actuator/prometheus` and scraped by the
  compose Prometheus (query returned the live deposit as
  `ledgerflow_movements_total{type="DEPOSIT",result="completed"} = 1`).
- Grafana API confirmed the provisioned dashboard (`uid: ledgerflow-main`).
- JSON log format verified by booting with the `json-logs` profile.
