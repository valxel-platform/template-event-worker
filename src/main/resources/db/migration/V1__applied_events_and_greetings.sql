-- Event ids already applied, so a duplicate delivery changes nothing. Pruned after
-- app.worker.dedup-retention (AppliedEventsPruner).
create table applied_events (
    event_id   text        primary key,
    topic      text        not null,
    partition  integer     not null,
    "offset"   bigint      not null,
    applied_at timestamptz not null default now()
);
create index applied_events_applied_at on applied_events (applied_at);

-- The example read model, kept from valxel.greeting.saved.v1. source_* is the Kafka position of
-- the last applied change; an older position never overwrites a newer one.
create table greetings (
    id               text        primary key,
    name             text        not null,
    message          text        not null,
    saved_by         text        not null,
    event_id         text        not null,
    source_partition integer     not null,
    source_offset    bigint      not null,
    updated_at       timestamptz not null
);
