package com.datagov.app.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 本地文件版死信存储:NDJSON,每行一条坏行。
 *
 * <p>路径 = {@code <根>/<workspaceId>/<executionId>/<nodeId>.ndjson},三段都经白名单校验,
 * 引用形如 {@code local://ws/exec/node.ndjson}(相对根,不暴露服务器绝对路径)。
 * 对象存储版是后续替换点 —— 接口只约定「写进去、给个引用」。
 */
@Component
public class LocalFileDeadLetterStore implements EtlDeadLetterStore {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9_\\-]{1,128}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    public LocalFileDeadLetterStore(
            @Value("${dg.etl.dead-letter-dir:./data/etl-dead-letter}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public String write(String workspaceId, String executionId, String nodeId,
                        List<Map<String, Object>> rows) throws IOException {
        String ws = segment(workspaceId);
        String exec = segment(executionId);
        String node = segment(nodeId);
        Path dir = root.resolve(ws).resolve(exec).normalize();
        if (!dir.startsWith(root)) {
            throw new IOException("死信路径越界");
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(node + ".ndjson");
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Map<String, Object> row : rows) {
                out.write(JSON.writeValueAsString(row));
                out.newLine();
            }
        }
        return "local://" + ws + "/" + exec + "/" + node + ".ndjson";
    }

    private static String segment(String value) throws IOException {
        if (value == null || !SAFE_SEGMENT.matcher(value).matches()) {
            throw new IOException("死信路径段不合法: " + value);
        }
        return value;
    }
}
