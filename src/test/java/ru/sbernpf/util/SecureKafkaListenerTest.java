package ru.sbernpf.util;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.support.MessageBuilder;
import ru.sbernpf.util.autoconfigure.SecureKafkaAutoConfiguration;
import ru.sbernpf.util.autoconfigure.SecureValidationAutoConfiguration;
import ru.sbernpf.util.kafka.SecureMessageException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureKafkaListenerTest {

    private static final String SQL = "' OR 1=1 --";

    public static class Event {
        public String name;

        Event(String name) {
            this.name = name;
        }
    }

    public static class Listener {
        int calls;

        @KafkaListener(topics = "dto")
        public void dto(Event event, Acknowledgment ack) {
            calls++;
        }

        @KafkaListener(topics = "text")
        public void text(String text) {
            calls++;
        }

        @KafkaListener(topics = "short")
        public void shortText(@SecureField(maxLength = 3) String text) {
            calls++;
        }

        @KafkaListener(topics = "record")
        public void record(ConsumerRecord<String, Event> record) {
            calls++;
        }

        @KafkaListener(topics = "message")
        public void message(Message<Event> message) {
            calls++;
        }

        @KafkaListener(topics = "batch")
        public void batch(List<Event> events) {
            calls++;
        }

        @KafkaListener(topics = "records")
        public void records(List<ConsumerRecord<String, Event>> records) {
            calls++;
        }

        @KafkaListener(topics = "header")
        public void header(String text, @Header("source") String source, @Headers Map<String, Object> headers) {
            calls++;
        }

        @KafkaListener(topics = "repeat-1")
        @KafkaListener(topics = "repeat-2")
        public void repeatable(String text) {
            calls++;
        }

        @KafkaListener(topics = "ranged")
        public void ranged(@SecureField(min = "1") Integer amount,
                           @Header("code") @SecureField(pattern = "[A-Z]+") String code) {
            calls++;
        }

        @KafkaHandler
        public void handler(String text) {
            calls++;
        }

        public void notListener(String text) {
            calls++;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SecureValidationAutoConfiguration.class,
                    SecureKafkaAutoConfiguration.class))
            .withBean(Listener.class);

    private static ConsumerRecord<String, Event> record(String name) {
        return new ConsumerRecord<>("t", 0, 0, "k", new Event(name));
    }

    @Test
    void rejectsBadPayloadsBeforeListenerIsCalled() {
        runner.run(ctx -> {
            Listener listener = ctx.getBean(Listener.class);
            assertThat(AopUtils.isCglibProxy(listener)).isTrue();

            assertThrows(SecureMessageException.class, () -> listener.dto(new Event(SQL), null));
            assertThrows(SecureMessageException.class, () -> listener.text(SQL));
            assertThrows(SecureMessageException.class, () -> listener.shortText("abcd"));
            assertThrows(SecureMessageException.class, () -> listener.record(record(SQL)));
            assertThrows(SecureMessageException.class,
                    () -> listener.message(MessageBuilder.withPayload(new Event(SQL)).build()));
            assertThrows(SecureMessageException.class,
                    () -> listener.batch(List.of(new Event("ok"), new Event(SQL))));
            assertThrows(SecureMessageException.class,
                    () -> listener.records(List.of(record("ok"), record(SQL))));
            assertThrows(SecureMessageException.class, () -> listener.header("ok", SQL, Map.of()));
            assertThrows(SecureMessageException.class, () -> listener.repeatable(SQL));
            assertThrows(SecureMessageException.class, () -> listener.handler(SQL));
            assertThrows(SecureMessageException.class, () -> listener.ranged(0, "ABC"));
            assertThrows(SecureMessageException.class, () -> listener.ranged(1, "abc"));

            Listener target = (Listener) ((Advised) listener).getTargetSource().getTarget();
            assertThat(target.calls).isZero();
        });
    }

    @Test
    void passesCleanPayloads() {
        runner.run(ctx -> {
            Listener listener = ctx.getBean(Listener.class);
            assertDoesNotThrow(() -> listener.dto(new Event("Иванов"), null));
            assertDoesNotThrow(() -> listener.record(record("O'Brien")));
            assertDoesNotThrow(() -> listener.header("ok", "crm", Map.of("x", SQL)));
        });
    }

    @Test
    void reportsViolationsWithoutValues() {
        runner.run(ctx -> {
            SecureMessageException e = assertThrows(SecureMessageException.class,
                    () -> ctx.getBean(Listener.class).dto(new Event(SQL), null));
            assertThat(e.getViolations()).extracting(Violation::check).contains(SecurityCheck.SQL_INJECTION);
            assertThat(e.getMessage()).doesNotContain(SQL);
        });
    }

    @Test
    void leavesOtherMethodsAlone() {
        runner.run(ctx -> assertDoesNotThrow(() -> ctx.getBean(Listener.class).notListener(SQL)));
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("sbernpf.secure-validation.kafka.enabled=false").run(ctx -> {
            assertThat(AopUtils.isAopProxy(ctx.getBean(Listener.class))).isFalse();
            assertDoesNotThrow(() -> ctx.getBean(Listener.class).text(SQL));
        });
    }
}
