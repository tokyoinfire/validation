package ru.sbernpf.util.kafka;

import org.springframework.aop.Pointcut;
import org.springframework.aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.core.Ordered;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.KafkaListeners;
import ru.sbernpf.util.SecureValidator;

import java.util.function.Supplier;

/**
 * Проксирует бины со слушателями Kafka и подключает {@link ValidatingListenerInterceptor}.
 * <p>
 * Сообщения проверяются на уровне метода, а не десериализатора: {@code JsonDeserializer} из spring-kafka
 * создаёт собственный {@code ObjectMapper}, в котором нет Jackson-модуля. Прокси создаётся самим
 * post-processor'ом и не зависит от {@code spring.aop.auto}. Порядок выше, чем у
 * {@code KafkaListenerAnnotationBeanPostProcessor}: иначе тот зарегистрирует слушателя без прокси.
 */
public class ValidatingListenerPostProcessor extends AbstractBeanFactoryAwareAdvisingPostProcessor {

    public ValidatingListenerPostProcessor(Supplier<SecureValidator> validator) {
        this.advisor = new DefaultPointcutAdvisor(listenerMethods(), new ValidatingListenerInterceptor(validator));
        setProxyTargetClass(true);
        setBeforeExistingAdvisors(true);
        setOrder(Ordered.LOWEST_PRECEDENCE - 1);
    }

    private static Pointcut listenerMethods() {
        return new ComposablePointcut(new AnnotationMatchingPointcut(null, KafkaListener.class, true))
                .union((Pointcut) new AnnotationMatchingPointcut(null, KafkaListeners.class, true))
                .union((Pointcut) new AnnotationMatchingPointcut(null, KafkaHandler.class, true));
    }
}
