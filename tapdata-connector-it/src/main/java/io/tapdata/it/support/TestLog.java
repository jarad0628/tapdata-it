package io.tapdata.it.support;

import io.tapdata.entity.logger.Log;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 测试日志：将 PDK {@link Log} 输出到控制台，便于集成测试观察 Connector 内部日志。
 */
public class TestLog implements Log {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public void debug(String message, Object... params) {
        println("DEBUG", message, params);
    }

    @Override
    public void info(String message, Object... params) {
        println("INFO", message, params);
    }

    @Override
    public void trace(String message, Object... params) {
        println("TRACE", message, params);
    }

    @Override
    public void warn(String message, Object... params) {
        println("WARN", message, params);
    }

    @Override
    public void error(String message, Object... params) {
        println("ERROR", message, params);
    }

    @Override
    public void error(String message, Throwable throwable) {
        System.err.println(timestamp() + " [ERROR] " + message);
        throwable.printStackTrace(System.err);
    }

    @Override
    public void fatal(String message, Object... params) {
        println("FATAL", message, params);
    }

    private void println(String level, String message, Object... params) {
        String text = (params == null || params.length == 0) ? message : format(message, params);
        System.out.println(timestamp() + " [" + level + "] " + text);
    }

    private static String format(String message, Object... params) {
        try {
            return String.format(message, params);
        } catch (Exception e) {
            return message;
        }
    }

    private static String timestamp() {
        return LocalDateTime.now().format(FORMAT);
    }
}
