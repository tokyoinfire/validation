package ru.sbernpf.util.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import ru.sbernpf.util.SecureDto;
import ru.sbernpf.util.SecureValidator;

import java.io.IOException;

/**
 * Оборачивает штатный десериализатор и проверяет результат.
 */
class ValidatingDeserializer extends DelegatingDeserializer {

    private final SecureValidator validator;
    /** Проверять, только если у результата есть {@link SecureDto} (десериализация через builder). */
    private final boolean onlySecureResult;

    ValidatingDeserializer(JsonDeserializer<?> delegate, SecureValidator validator, boolean onlySecureResult) {
        super(delegate);
        this.validator = validator;
        this.onlySecureResult = onlySecureResult;
    }

    @Override
    protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee) {
        return new ValidatingDeserializer(newDelegatee, validator, onlySecureResult);
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        return validated(super.deserialize(p, ctxt));
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt, Object intoValue) throws IOException {
        return validated(super.deserialize(p, ctxt, intoValue));
    }

    @Override
    public Object deserializeWithType(JsonParser p, DeserializationContext ctxt, TypeDeserializer typeDeserializer)
            throws IOException {
        return validated(super.deserializeWithType(p, ctxt, typeDeserializer));
    }

    @Override
    public Object deserializeWithType(JsonParser p, DeserializationContext ctxt, TypeDeserializer typeDeserializer,
                                      Object intoValue) throws IOException {
        return validated(super.deserializeWithType(p, ctxt, typeDeserializer, intoValue));
    }

    private Object validated(Object value) throws IOException {
        if (onlySecureResult && (value == null || !value.getClass().isAnnotationPresent(SecureDto.class))) {
            return value;
        }
        validator.validateOrThrow(value);
        return value;
    }
}
