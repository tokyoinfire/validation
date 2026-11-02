package ru.sbernpf.util.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import ru.sbernpf.util.SecureValidator;

import java.io.IOException;

/**
 * Оборачивает штатный десериализатор и проверяет результат.
 */
class ValidatingDeserializer extends DelegatingDeserializer {

    private final SecureValidator validator;

    ValidatingDeserializer(JsonDeserializer<?> delegate, SecureValidator validator) {
        super(delegate);
        this.validator = validator;
    }

    @Override
    protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee) {
        return new ValidatingDeserializer(newDelegatee, validator);
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
        validator.validateOrThrow(value);
        return value;
    }
}
