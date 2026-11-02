package ru.sbernpf.util.kafka;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.aop.support.AopUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.handler.annotation.Headers;
import ru.sbernpf.util.DateTimeRange;
import ru.sbernpf.util.SecureField;
import ru.sbernpf.util.SecureValidator;
import ru.sbernpf.util.SecurityValidationException;
import ru.sbernpf.util.Violation;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Проверяет аргументы метода-слушателя до его вызова.
 * <p>
 * Проверяются payload (в том числе из {@link ConsumerRecord}, {@link Message} и пакетов) и параметры
 * с {@code @Header}. Набор заголовков целиком и ключ {@link ConsumerRecord} не проверяются.
 * Параметры настраиваются теми же {@link SecureField} и {@link DateTimeRange}, что и поля DTO.
 */
class ValidatingListenerInterceptor implements MethodInterceptor {

    private final Supplier<SecureValidator> validator;

    ValidatingListenerInterceptor(Supplier<SecureValidator> validator) {
        this.validator = validator;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = AopUtils.getMostSpecificMethod(invocation.getMethod(),
                AopUtils.getTargetClass(invocation.getThis()));
        Parameter[] parameters = method.getParameters();
        Object[] arguments = invocation.getArguments();
        List<Violation> violations = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            if (parameter.isAnnotationPresent(Headers.class) || arguments[i] instanceof MessageHeaders) {
                continue;
            }
            violations.addAll(validator.get().validateParameter(payloadOf(arguments[i]), parameter.getName(),
                    parameter.getAnnotation(SecureField.class), parameter.getAnnotation(DateTimeRange.class)));
        }
        if (!violations.isEmpty()) {
            throw new SecureMessageException(new SecurityValidationException(method.getDeclaringClass(), violations));
        }
        return invocation.proceed();
    }

    private static Object payloadOf(Object argument) {
        if (argument instanceof ConsumerRecord) {
            return ((ConsumerRecord<?, ?>) argument).value();
        }
        if (argument instanceof Message) {
            return ((Message<?>) argument).getPayload();
        }
        if (argument instanceof ConsumerRecords) {
            List<Object> payloads = new ArrayList<>();
            ((ConsumerRecords<?, ?>) argument).forEach(record -> payloads.add(record.value()));
            return payloads;
        }
        if (argument instanceof Collection) {
            List<Object> payloads = new ArrayList<>();
            ((Collection<?>) argument).forEach(element -> payloads.add(payloadOf(element)));
            return payloads;
        }
        return argument;
    }
}
