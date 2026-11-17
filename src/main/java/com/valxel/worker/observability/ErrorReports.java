package com.valxel.worker.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import java.util.function.Supplier;

/**
 * What an error report to Sentry (or GlitchTip) may carry. Sentry runs only when SENTRY_DSN is set;
 * an event the worker can't apply, a store that keeps failing, or any ERROR log becomes an event,
 * grouped into an issue whose alert reaches the SDLC tool as an incident signal.
 *
 * <p>An event keeps the error's type, its stack, the log message's pattern, the trace id (the
 * producer's trace the event continued, so the request that changed the record and this failure
 * are one incident) and the component tag. Never exception messages, formatted log messages or
 * their arguments, event data, users or breadcrumbs: any of them can carry PHI.
 */
public class ErrorReports implements SentryOptions.BeforeSendCallback {

    private final Supplier<Tracer> tracer;

    public ErrorReports(Supplier<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        event.setUser(null);
        event.setExtras(null);
        event.setBreadcrumbs(null);
        event.setRequest(null);
        if (event.getExceptions() != null) {
            for (SentryException ex : event.getExceptions()) {
                ex.setValue(null);
            }
        }
        Message message = event.getMessage();
        if (message != null) {
            message.setFormatted(null);
            message.setParams(null);
        }
        String trace = traceId(tracer.get());
        if (trace != null) {
            event.setTag("trace_id", trace);
        }
        return event;
    }

    static String traceId(Tracer tracer) {
        Span span = tracer == null ? null : tracer.currentSpan();
        if (span == null || span.context().traceId().isBlank()) {
            return null;
        }
        return span.context().traceId();
    }
}
