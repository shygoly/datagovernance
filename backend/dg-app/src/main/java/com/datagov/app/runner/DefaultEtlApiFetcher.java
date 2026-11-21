package com.datagov.app.runner;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceType;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.metadata.service.ConnectionConfigAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link EtlApiFetcher} 的生产实现,沿用 {@link ApiParseRunner} 的模式:
 * 不跟随重定向、认证头由数据源凭据在执行期注入(计划里没有 Token)、报错只带截断的响应体。
 *
 * <h3>SSRF 防护(仅对计划里直接写的 {@code url})</h3>
 * 地址解析出的任一 IP 若是回环 / 链路本地(含云元数据 169.254.x.x)/ 通配地址 / 组播,一律拒绝;
 * 私网地址(10/8、172.16/12、192.168/16、fc00::/7)默认也拒绝,内网部署可设
 * {@code dg.etl.api.allow-private-hosts=true} 放开。通过 {@code apiConfigId} 引用的 REST_API
 * 数据源由管理员配置,视为受信,不做此检查。已知局限:校验与实际连接各解析一次 DNS,
 * 不防 DNS rebinding。
 *
 * <p>响应体上限 {@value #MAX_BODY_BYTES} 字节 —— 整个 JSON 要读进内存才能解析。
 */
@Component
public class DefaultEtlApiFetcher implements EtlApiFetcher {

    private static final Logger log = LoggerFactory.getLogger(DefaultEtlApiFetcher.class);

    static final long MAX_BODY_BYTES = 64L * 1024 * 1024;

    private final DataSourceMapper dataSourceMapper;
    private final ConnectionConfigAssembler assembler;
    private final ObjectMapper objectMapper;
    private final boolean allowPrivateHosts;

    public DefaultEtlApiFetcher(DataSourceMapper dataSourceMapper, ConnectionConfigAssembler assembler,
                                ObjectMapper objectMapper,
                                @Value("${dg.etl.api.allow-private-hosts:false}") boolean allowPrivateHosts) {
        this.dataSourceMapper = dataSourceMapper;
        this.assembler = assembler;
        this.objectMapper = objectMapper;
        this.allowPrivateHosts = allowPrivateHosts;
    }

    @Override
    public List<Map<String, Object>> fetch(String workspaceId, String url, String apiConfigId,
                                           String recordsPath, long maxRows) throws Exception {
        ConnectionConfig config = null;
        String target = url;
        if (apiConfigId != null) {
            DataSourceEntity ds = dataSourceMapper.selectById(apiConfigId);
            if (ds == null || (workspaceId != null && !workspaceId.equals(ds.getWorkspaceId()))) {
                throw new IllegalArgumentException("接口数据源不存在: " + apiConfigId);
            }
            if (ds.getType() != DataSourceType.REST_API) {
                throw new IllegalArgumentException("数据源 " + apiConfigId + " 不是 REST_API 类型");
            }
            config = assembler.assemble(ds, ds.getWorkspaceId());
            target = url != null ? url : config.baseUrl();
        } else {
            checkHost(target);
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("接口地址为空");
        }
        URI uri = URI.create(target);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("接口地址只允许 http(s)");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config == null
                        ? ConnectionConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS : config.connectTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(config == null
                        ? ConnectionConfig.DEFAULT_READ_TIMEOUT_MILLIS : config.readTimeoutMillis()))
                .header("Accept", "application/json");
        if (config != null) {
            ApiParseRunner.applyAuth(builder, config);
        }

        HttpResponse<InputStream> response = client.send(builder.GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (InputStream in = response.body()) {
            body = readCapped(in);
        }
        if (response.statusCode() >= 300) {
            String preview = new String(body, 0, Math.min(500, body.length),
                    java.nio.charset.StandardCharsets.UTF_8);
            throw new IllegalStateException("接口返回 HTTP %d: %s".formatted(response.statusCode(), preview));
        }

        JsonNode array = ApiParseRunner.extractNode(objectMapper.readTree(body), recordsPath);
        if (array == null || !array.isArray()) {
            throw new IllegalStateException("JSON 路径「%s」指向的不是数组"
                    .formatted(recordsPath == null || recordsPath.isBlank() ? "(顶层)" : recordsPath));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isObject()) {
                throw new IllegalStateException("接口记录必须是 JSON 对象,实际: " + item.getNodeType());
            }
            rows.add(FileParseRunner.toRow(item));
            if (maxRows > 0 && rows.size() >= maxRows) {
                break;
            }
        }
        log.info("ETL 接口源取到 {} 行 url={}", rows.size(), safeHost(uri));
        return rows;
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BODY_BYTES) {
                throw new IllegalStateException("接口响应超过 " + (MAX_BODY_BYTES >> 20) + "MB 上限");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private void checkHost(String url) throws IOException {
        String host = url == null ? null : URI.create(url).getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("接口地址缺少主机名");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("接口地址指向不允许的内部地址: " + host);
            }
            if (!allowPrivateHosts && (address.isSiteLocalAddress() || isUniqueLocalV6(address))) {
                throw new IllegalArgumentException("接口地址指向私网地址: " + host
                        + "(内网部署可设置 dg.etl.api.allow-private-hosts=true,或改用 apiConfigId 引用数据源)");
            }
        }
    }

    private static boolean isUniqueLocalV6(InetAddress address) {
        byte[] raw = address.getAddress();
        return raw.length == 16 && (raw[0] & 0xFE) == 0xFC;
    }

    /** 日志里只带主机与路径,不带查询串(可能含 token)。 */
    private static String safeHost(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPath() == null ? "" : uri.getPath());
    }
}
