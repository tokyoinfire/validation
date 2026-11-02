package ru.sbernpf.util.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.ResolvableSerializer;
import com.fasterxml.jackson.databind.util.NameTransformer;
import ru.sbernpf.util.SecureValidator;

import java.io.IOException;

/**
 * Оборачивает штатный сериализатор и проверяет объект перед записью.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class ValidatingSerializer extends JsonSerializer<Object> implements ContextualSerializer, ResolvableSerializer {

    private final JsonSerializer<Object> delegate;
    private final SecureValidator validator;

    ValidatingSerializer(JsonSerializer<?> delegate, SecureValidator validator) {
        this.delegate = (JsonSerializer<Object>) delegate;
        this.validator = validator;
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        validator.validateOrThrow(value);
        delegate.serialize(value, gen, serializers);
    }

    @Override
    public void serializeWithType(Object value, JsonGenerator gen, SerializerProvider serializers,
                                  TypeSerializer typeSer) throws IOException {
        validator.validateOrThrow(value);
        delegate.serializeWithType(value, gen, serializers, typeSer);
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property)
            throws JsonMappingException {
        if (delegate instanceof ContextualSerializer) {
            JsonSerializer<?> contextual = ((ContextualSerializer) delegate).createContextual(prov, property);
            if (contextual != delegate) {
                return new ValidatingSerializer(contextual, validator);
            }
        }
        return this;
    }

    @Override
    public void resolve(SerializerProvider provider) throws JsonMappingException {
        if (delegate instanceof ResolvableSerializer) {
            ((ResolvableSerializer) delegate).resolve(provider);
        }
    }

    @Override
    public JsonSerializer<Object> unwrappingSerializer(NameTransformer unwrapper) {
        JsonSerializer<Object> unwrapping = delegate.unwrappingSerializer(unwrapper);
        return unwrapping == delegate ? this : new ValidatingSerializer(unwrapping, validator);
    }

    @Override
    public boolean isUnwrappingSerializer() {
        return delegate.isUnwrappingSerializer();
    }

    @Override
    public boolean isEmpty(SerializerProvider provider, Object value) {
        return delegate.isEmpty(provider, value);
    }

    @Override
    public boolean usesObjectId() {
        return delegate.usesObjectId();
    }

    @Override
    public Class<Object> handledType() {
        return delegate.handledType();
    }

    @Override
    public JsonSerializer<?> getDelegatee() {
        return delegate;
    }
}
