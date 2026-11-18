package ru.sbernpf.util.jackson;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.deser.BuilderBasedDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import ru.sbernpf.util.SecureDto;
import ru.sbernpf.util.SecureValidator;

/**
 * Jackson-модуль: для классов с {@link SecureDto} запускает {@link SecureValidator}
 * после десериализации и перед сериализацией.
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper().registerModule(new SecureValidationModule());
 * }</pre>
 * В Spring Boot модуль регистрируется автоматически.
 */
public class SecureValidationModule extends SimpleModule {

    public SecureValidationModule() {
        this(new SecureValidator());
    }

    public SecureValidationModule(SecureValidator validator) {
        super("SecureValidationModule", Version.unknownVersion());
        setDeserializerModifier(new BeanDeserializerModifier() {
            @Override
            public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription beanDesc,
                                                          JsonDeserializer<?> deserializer) {
                if (isSecure(beanDesc)) {
                    return new ValidatingDeserializer(deserializer, validator, false);
                }
                if (deserializer instanceof BuilderBasedDeserializer) {
                    // @JsonDeserialize(builder = ...), Lombok @Jacksonized: beanDesc описывает builder,
                    // а не DTO, поэтому @SecureDto проверяем у построенного объекта
                    return new ValidatingDeserializer(deserializer, validator, true);
                }
                return deserializer;
            }
        });
        setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc,
                                                      JsonSerializer<?> serializer) {
                return isSecure(beanDesc) ? new ValidatingSerializer(serializer, validator) : serializer;
            }
        });
    }

    @Override
    public Object getTypeId() {
        return SecureValidationModule.class.getName();
    }

    private static boolean isSecure(BeanDescription beanDesc) {
        return beanDesc.getBeanClass().isAnnotationPresent(SecureDto.class);
    }

    /**
     * Регистрирует модуль и дополнительно ужесточает настройки парсера
     * (защита от DoS глубокой вложенностью, гигантскими строками и числами)
     * и отключает полиморфную десериализацию по умолчанию (gadget-цепочки).
     *
     * @throws IllegalStateException если jackson-core старше 2.15: ограничения парсера недоступны
     */
    public static ObjectMapper harden(ObjectMapper mapper) {
        return harden(mapper, new SecureValidator());
    }

    /**
     * То же, что {@link #harden(ObjectMapper)}, с заданным валидатором:
     * например, с запрещёнными значениями конкретной АС.
     */
    public static ObjectMapper harden(ObjectMapper mapper, SecureValidator validator) {
        requireStreamReadConstraints(mapper);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(200)
                .maxStringLength(1_000_000)
                .maxNumberLength(100)
                .build());
        mapper.deactivateDefaultTyping();
        return mapper.registerModule(new SecureValidationModule(validator));
    }

    private static void requireStreamReadConstraints(ObjectMapper mapper) {
        try {
            Class.forName("com.fasterxml.jackson.core.StreamReadConstraints", false,
                    ObjectMapper.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SecureValidationModule.harden() requires jackson-core 2.15+, found "
                    + mapper.getFactory().version() + ". Upgrade Jackson (in Spring Boot 2.x set the"
                    + " jackson-bom.version property) or register SecureValidationModule without harden()", e);
        }
    }
}
