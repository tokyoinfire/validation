package ru.sbernpf.util;

/**
 * Нарушение правила безопасности.
 * <p>
 * Само значение поля намеренно не сохраняется, чтобы вредоносные данные
 * не попадали в логи и ответы клиенту.
 *
 * @param path    путь к полю, например {@code address.street} или {@code items[2].name}
 * @param check   сработавшая проверка
 * @param message описание нарушения
 */
public record Violation(String path, SecurityCheck check, String message) {

    @Override
    public String toString() {
        return path + ": " + message + " [" + check + "]";
    }
}
