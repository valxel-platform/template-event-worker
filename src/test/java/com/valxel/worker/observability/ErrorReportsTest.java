package com.valxel.worker.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorReportsTest {

    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    static Tracer tracer(String traceId) {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext ctx = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(ctx);
        when(ctx.traceId()).thenReturn(traceId);
        return tracer;
    }

    @Test
    void aReportKeepsTypeStackPatternAndTraceButNothingAboutPeople() {
        SentryEvent event = new SentryEvent();
        User user = new User();
        user.setEmail("jane@example.com");
        event.setUser(user);
        event.setExtras(Map.of("event", "{\"name\":\"Jane\"}"));
        event.setBreadcrumbs(List.of(new Breadcrumb("Applied greeting for Jane")));
        event.setRequest(new Request());
        SentryException ex = new SentryException();
        ex.setType("DataIntegrityViolationException");
        ex.setValue("Key (name)=(Jane) already exists");
        event.setExceptions(List.of(ex));
        Message message = new Message();
        message.setMessage("Couldn't apply {}");
        message.setFormatted("Couldn't apply Jane");
        message.setParams(List.of("Jane"));
        event.setMessage(message);

        SentryEvent out = new ErrorReports(() -> tracer(TRACE)).execute(event, new Hint());

        assertThat(out.getUser()).isNull();
        assertThat(out.getExtras()).isNullOrEmpty();
        assertThat(out.getBreadcrumbs()).isNullOrEmpty();
        assertThat(out.getRequest()).isNull();
        assertThat(out.getExceptions().get(0).getType()).isEqualTo("DataIntegrityViolationException");
        assertThat(out.getExceptions().get(0).getValue()).isNull();
        assertThat(out.getMessage().getMessage()).isEqualTo("Couldn't apply {}");
        assertThat(out.getMessage().getFormatted()).isNull();
        assertThat(out.getMessage().getParams()).isNull();
        assertThat(out.getTag("trace_id")).isEqualTo(TRACE);
    }

    @Test
    void withoutATraceThereIsNoTraceTag() {
        assertThat(new ErrorReports(() -> null).execute(new SentryEvent(), new Hint()).getTag("trace_id")).isNull();
        assertThat(new ErrorReports(() -> tracer("")).execute(new SentryEvent(), new Hint()).getTag("trace_id")).isNull();
    }
}
