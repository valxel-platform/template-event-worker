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
| Metrics | `worker_events_total{type,outcome}` and the Kafka client's consumer lag on `/actuator/prometheus` | Alert on lag and on parked records |
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

1. Rename the package (`com.vrixel.worker`), `rootProject.name` and `spring.application.name`.
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
