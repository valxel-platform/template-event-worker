# Event worker template (Java 21, Spring Boot 4, Spring Kafka)

Keeps one of the product's data stores up to date from Kafka. Services change state by publishing
events to Kafka first (constitution VX-16, `com.vrixel.service.events` in the service template);
this worker reads those topics and applies each event to its store. Kafka is the record. The
store is a copy that can always be rebuilt by replaying the topics.

The example applies `vrixel.greeting.saved.v1`, published by the service template's
`PUT /api/v1/greetings/{id}`, to a `greetings` table in Postgres. Replace it with your handlers.

## What you get

| Concern | How | Why |
| --- | --- | --- |
| Exactly-once effect | Each event's id goes into `applied_events` in the same transaction as the change; a duplicate (producer retry, redelivery) is skipped | At-least-once delivery without double-applying |
| Order | Events for one record share a key, so one partition; each row keeps the Kafka position it came from and ignores older ones | Replays over an existing store never undo a newer change |
| Offsets | Committed per record, after the transaction commits (`ack-mode: record`) | A crash re-delivers, never skips |
| Failures | Retries with exponential backoff (`WORKER_RETRIES`), then the record is parked on `<topic>.dlt` with the exception in its headers; invalid events skip the retries | One bad record never stops its partition |
| New event types | Types without a handler are skipped and counted | Producers can add types before workers handle them |
| Schema | Flyway migrations in `src/main/resources/db/migration`, run at start | The store's shape is versioned with the code that fills it |
| Tracing | Kafka observations continue the producer's trace from the `traceparent` header; OTLP export as in the service template | Follow a change from the request to every store it reaches |
| AWS calls | OpenTelemetry's AWS SDK instrumentation on the DynamoDB client: each call is a span of this component, with its table, latency and error (`StoreConfig`) | See a slow or throttled table from the component, in OpenChoreo's traces; the table's own CloudWatch alarms come with it from the platform |
| Product events | `ProductEvents.record("consent_synced")`: a counter (`vrixel.events`), a summary line a minute and per-flag counts | The SDLC tool proves in staging that a feature's goal events fire, reads goals from them after release, and compares work with the feature on against off |
| Feature flags | OpenFeature with an OFREP provider for the product's Flipt (`FLAGS_URL`); `Flags.enabled(key, user)` | A feature's new handling ships dark and is released with the rest of the feature |
| Metrics | `worker_events_total{type,outcome}`, `worker_dead_lettered_total{topic,exception}` and the Kafka client's consumer lag on `/actuator/prometheus` | Alert on lag, and on the first parked record |
| Throughput | Offsets are committed once per poll (`ack-mode: batch`), after every record in it is applied; flags are cached 30 s per flag and user (`FLAGS_CACHE_SECONDS`) and a Flipt call waits at most 250 ms | Far fewer broker round trips per event; a crash replays a few applied records, which are skipped as duplicates |
| Encryption | `DATA_CLASS` `phi` or `payments` refuses to start unless Kafka uses `SSL` or `SASL_SSL`; the database password comes from a secret (`DATABASE_PASSWORD`, no default) | PHI never crosses the network in plain text |
| Tests | Unit tests, and integration tests against real Postgres and Kafka (Testcontainers) | Duplicates, ordering, replays and dead letters are proven on every PR |

## Writing a handler

```java
@Component
class PatientRegisteredHandler implements EventHandler {
    public String type() { return "vrixel.patient.registered.v1"; }

    public void apply(Event event, Position at) {
        // Upsert keyed by the record's id; only move forward:
        //   ... on conflict (id) do update set ... where patients.source_offset < excluded.source_offset
    }
}
```

- Throw `InvalidEventException` (naming the field, never its value) when an event can't be
  applied however often it's retried: it goes straight to the dead-letter topic.
- Any other exception is retried, then parked.
- Never call other services or publish events from a handler: a worker only keeps its store. If a
  change should cause another change, the service that owns it reacts to the event.
- New event versions are new types (`...v2`); handle both until producers have moved.
- Never change a topic's partition count: per-record order depends on it. Create a new topic version.

## Feature flags

A feature that changes how an event is applied puts the new handling behind its flag, keyed by
the user the event is about or who caused it (the same key the service used, so a person gets the
same answer in both):

```java
if (flags.enabled("emr.emr-feat-3.check-in-kiosk", event.dataText("saved_by"))) {
    // the new handling
} else {
    // today's handling
}
```

`com.vrixel.worker.flags` reads flags over OFREP from the product's Flipt: `FLAGS_URL`,
`FLAGS_ENVIRONMENT`, `FLAGS_NAMESPACE` and, if needed, `FLAGS_TOKEN`, as in the service template.
Without `FLAGS_URL`, or when Flipt can't be reached, every flag reads as off.

While the SDLC tool releases a feature gradually, each delivery attempt that checked a flag counts
on the side it got, as failed when the handler throws (each retry is another attempt; the last
failure parks the record), and as slow when it takes longer than 5 s, the lag budget
(`FLAG_COHORTS_SLOW_MS`). Once a minute the worker logs one `vrixel.flag_cohorts` line per flag
with the counts and nothing else; the tool compares the two sides from OpenChoreo's logs and stops
the release when the "on" side fails more or is slow more often.

## Product events: whether a feature moves its goal

When the HLD's Instrumentation section names this worker as the one that records an event (the
outcome happens as the event is applied), record it in the handler:

```java
ProductEvents.record("consent_synced");
ProductEvents.record("consent_synced", Map.of("consent_type", "treatment")); // categories only
```

`com.vrixel.worker.outcomes.ProductEvents` counts it on the counter `vrixel.events` (attribute
`event`), in a `vrixel.events {"consent_synced":12}` line once a minute (the SDLC tool counts these
in staging before G6), and on each side of the attempt's `vrixel.flag_cohorts` line as `"events"`
(compared during gradual releases). It works as in the service template: names are lowercase
words, and an attribute that names or identifies a person throws.

## Rebuilding the store

The store is a copy, so it can be rebuilt (a new column, a bug fixed in a handler):

1. Scale the worker to zero.
2. Truncate the handler's tables and `applied_events`, or point `DATABASE_URL` at a new database.
3. Reset the group: `kafka-consumer-groups --bootstrap-server ... --group <KAFKA_GROUP> --topic <topic> --reset-offsets --to-earliest --execute`
4. Scale back up and watch the lag drain.

Topics must keep every event the store needs (`retention.ms=-1`, or compaction keyed by record id
for state-carrying events).

## Dead letters

Parked records keep their key, value and headers, plus `kafka_dlt-exception-*` and
`kafka_dlt-original-*` headers. Fix the cause (a handler bug, a producer bug), then copy the records
back to the original topic. Alert when `<topic>.dlt` is not empty.

## Develop

```bash
./gradlew test        # unit and integration tests; needs Docker for Postgres and Kafka
./gradlew bootRun     # against DATABASE_URL and KAFKA_BOOTSTRAP
```

Settings: `KAFKA_BOOTSTRAP`, `KAFKA_GROUP`, `WORKER_TOPICS` (comma-separated), `WORKER_CONCURRENCY`
(up to the partition count), `WORKER_RETRIES`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`.
Kafka security settings go under `spring.kafka.properties`.

## After creating a repo from this template

1. Rename the package (`com.vrixel.worker`). The SDLC tool has already put the component's name in
   the files `.vrixel/identity` lists (`rootProject.name`, `spring.application.name`, the Workload,
   CI); a repo created by hand needs them renamed.
2. Set `WORKER_TOPICS` and `KAFKA_GROUP` (one group per worker; every replica shares it).
3. Replace the greeting handler, its table and its tests with your own.

## AWS read models (VX-19)

A read model can live in DynamoDB instead of Postgres when the design's Resources section gives
the worker a `dynamodb-table` (for example `greetings: dynamodb-table; partition_key=id`, with
`writes: <this worker>`):

- `READ_MODEL_STORE=dynamodb`, and `workload.yaml` binds the table's name to
  `GREETINGS_TABLE_NAME` (`dependencies.resources`). The SDLC tool writes both.
- Credentials come from the SDK's default chain: in the platform, the pod's IAM role through EKS
  Pod Identity, with only the actions the design declared. Never set keys.
- `DynamoGreetingStore` writes one conditional update per event, applied only if the item is new
  or from an earlier Kafka offset, so duplicates and replays change nothing. Applied events are
  still recorded in Postgres.
- Tests run against DynamoDB Local in a container (`DynamoGreetingStoreTest`).

## Performance tests

When the Vrixel SDLC labels a pull request `vrixel:perf-smoke` (a handler, a migration, the store,
settings or the runtime changes; or the reviewer saw a performance risk), `perf.yml` runs
`WorkerPerfTest` on main's code and the PR's: a burst of 3,000 events through real Kafka and
Postgres, measuring seconds until 95% are in the store, events per second, and parked events.
`perf/compare.py` posts `perf-smoke / report`: failed when the PR is more than 20% slower than main
or misses the budget (95% within 5 s, under 1% parked). The test runs only with
`-Dperf.out=<file>`, so unit runs don't start its containers. A worker has no staging load test (it
would need the environment's Kafka credentials); consumer lag and `worker_dead_lettered_total` watch
it in production.

