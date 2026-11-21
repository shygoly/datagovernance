package com.datagov.app.runner;

import com.datagov.data.connector.parquet.DuckDbParquetSupport;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.DataSourceConnector;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.FileCatalogReader;
import com.datagov.metadata.entity.DataSourceEntity;
import com.datagov.metadata.mapper.DataSourceMapper;
import com.datagov.metadata.service.ConnectionConfigAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link EtlObjectStore} 的生产实现:复用平台已有的数据源定义与连接装配。
 *
 * <ul>
 *   <li><b>LOCAL</b>(本地 / NFS 目录):相对路径 resolve 到数据源根目录下,做 normalize + 真实路径越界校验
 *       (含符号链接逃逸);读写都支持,写入先落同目录临时文件再原子 move;</li>
 *   <li><b>FTP / SFTP</b>({@link FileCatalogReader#openFile}):只读,流式拷到临时文件;
 *       现有连接器没有写入能力,作为 Sink 会明确拒绝;</li>
 *   <li><b>S3 / MinIO / OSS</b>:用<b>独立的、只由 Java 驱动的</b>临时 DuckDB 连接(httpfs + 凭据只注入这条连接)
 *       读远端并转码成本地 Parquet / 把本地 Parquet 写回远端。用户 SQL 所在的会话从不持有凭据。
 *       <b>此路径目前没有针对真实 S3 / MinIO 的自动化测试</b>(仅有 LOCAL 与 DuckDB 本地文件的测试)。</li>
 * </ul>
 *
 * <p>{@code fileId}(平台文件资产)目前仓库里没有对应服务,实现明确拒绝而不是猜一个路径。
 */
@Component
public class DefaultEtlObjectStore implements EtlObjectStore {

    private static final Logger log = LoggerFactory.getLogger(DefaultEtlObjectStore.class);

    private static final Pattern SCHEME_OR_DRIVE = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");

    private final DataSourceMapper dataSourceMapper;
    private final ConnectionConfigAssembler assembler;
    private final Map<DataSourceType, FileCatalogReader> fileReaders = new EnumMap<>(DataSourceType.class);

    public DefaultEtlObjectStore(DataSourceMapper dataSourceMapper, ConnectionConfigAssembler assembler,
                                 List<DataSourceConnector> connectors) {
        this.dataSourceMapper = dataSourceMapper;
        this.assembler = assembler;
        for (DataSourceConnector connector : connectors) {
            if (connector instanceof FileCatalogReader reader) {
                for (DataSourceType type : connector.supportedTypes()) {
                    fileReaders.put(type, reader);
                }
            }
        }
    }

    @Override
    public Fetched fetch(String workspaceId, ObjectRef ref, String format, Map<String, Object> options,
                         Path dir) throws Exception {
        String fmt = EtlFileFormats.normalize(format);
        DataSourceEntity ds = requireDataSource(workspaceId, ref);
        String relative = relativePath(ref);
        DataSourceType type = ds.getType();
        ConnectionConfig config = assembler.assemble(ds, ds.getWorkspaceId());
        String extension = fmt.equals("PARQUET") ? "parquet" : fmt.equals("CSV") ? "csv" : "json";

        if (type == DataSourceType.LOCAL) {
            Path real = resolveLocal(config, relative, true);
            Path target = dir.resolve("source." + extension);
            Files.copy(real, target, StandardCopyOption.REPLACE_EXISTING);
            return new Fetched(target, fmt);
        }
        if (DuckDbParquetSupport.isS3(type)) {
            Path target = dir.resolve("source.parquet");
            String remote = DuckDbParquetSupport.location(type, config, null) + "/" + relative;
            try (Connection scratch = DuckDbParquetSupport.open(type, config);
                 Statement st = scratch.createStatement()) {
                st.execute("COPY (SELECT * FROM " + EtlFileFormats.readExpression(fmt, remote, options)
                        + ") TO " + EtlFileFormats.literal(target.toString()) + " (FORMAT PARQUET)");
            }
            return new Fetched(target, "PARQUET");
        }
        FileCatalogReader reader = fileReaders.get(type);
        if (reader == null) {
            throw new IllegalArgumentException(type.displayName() + " 不是可读取文件的数据源类型");
        }
        Path target = dir.resolve("source." + extension);
        try (InputStream in = reader.openFile(type, config, relative)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return new Fetched(target, fmt);
    }

    @Override
    public void put(String workspaceId, ObjectRef ref, Path source, String compression) throws Exception {
        DataSourceEntity ds = requireDataSource(workspaceId, ref);
        String relative = relativePath(ref);
        DataSourceType type = ds.getType();
        ConnectionConfig config = assembler.assemble(ds, ds.getWorkspaceId());

        if (type == DataSourceType.LOCAL) {
            Path destination = resolveLocal(config, relative, false);
            Path parent = destination.getParent();
            Files.createDirectories(parent);
            // 创建目录之后再校验一次真实路径:防止已有的符号链接目录把写入带出根目录
            Path root = Path.of(DuckDbParquetSupport.location(type, config, null)).toRealPath();
            if (!parent.toRealPath().startsWith(root)) {
                throw new IllegalArgumentException("目标路径越出数据源根目录: " + relative);
            }
            Path staging = Files.createTempFile(parent, ".dg-etl-", ".tmp");
            try {
                Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(staging);
            }
            log.info("ETL Parquet 已写入本地数据源 ds={} path={}", ref.dataSourceId(), relative);
            return;
        }
        if (DuckDbParquetSupport.isS3(type)) {
            String remote = DuckDbParquetSupport.location(type, config, null) + "/" + relative;
            try (Connection scratch = DuckDbParquetSupport.open(type, config);
                 Statement st = scratch.createStatement()) {
                st.execute("COPY (SELECT * FROM read_parquet(" + EtlFileFormats.literal(source.toString())
                        + ")) TO " + EtlFileFormats.literal(remote) + " (FORMAT PARQUET, COMPRESSION "
                        + EtlFileFormats.literal(EtlFileFormats.parquetCompression(compression)) + ")");
            } catch (SQLException e) {
                throw new IllegalStateException("写入对象存储失败: " + e.getMessage(), e);
            }
            log.info("ETL Parquet 已写入对象存储 ds={} path={}", ref.dataSourceId(), relative);
            return;
        }
        throw new IllegalArgumentException(type.displayName() + " 暂不支持作为 Parquet 目标(只支持本地目录与 S3 兼容对象存储)");
    }

    // ── 解析与校验 ──────────────────────────────────────────────────────

    private DataSourceEntity requireDataSource(String workspaceId, ObjectRef ref) {
        if (ref.fileId() != null) {
            throw new IllegalArgumentException("fileId 引用暂不支持:平台还没有文件资产服务,"
                    + "请改用 dataSourceId + path(" + ref.describe() + ")");
        }
        DataSourceEntity entity = ref.dataSourceId() == null ? null
                : dataSourceMapper.selectById(ref.dataSourceId());
        // 空间不匹配与不存在同一个报错,不泄露别的空间有没有这个 ID
        if (entity == null || (workspaceId != null && !workspaceId.equals(entity.getWorkspaceId()))) {
            throw new IllegalArgumentException("数据源不存在: " + ref.dataSourceId());
        }
        return entity;
    }

    /** 相对路径校验:与编译器同一口径,这里再兜一次(计划可能被直接改库)。 */
    static String relativePath(ObjectRef ref) {
        String path = ref.path();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("文件路径为空");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (path.startsWith("/") || path.startsWith("\\") || path.contains("..") || path.indexOf('\0') >= 0
                || path.contains("\\") || lower.startsWith("file:") || SCHEME_OR_DRIVE.matcher(path).matches()) {
            throw new IllegalArgumentException("文件路径不合法(只允许数据源内的相对路径): " + path);
        }
        return path;
    }

    private static Path resolveLocal(ConnectionConfig config, String relative, boolean mustExist)
            throws java.io.IOException {
        Path root = Path.of(DuckDbParquetSupport.location(DataSourceType.LOCAL, config, null)).toRealPath();
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("文件路径越出数据源根目录: " + relative);
        }
        if (mustExist) {
            if (!Files.isRegularFile(candidate)) {
                throw new IllegalArgumentException("文件不存在: " + relative);
            }
            if (!candidate.toRealPath().startsWith(root)) {
                throw new IllegalArgumentException("文件路径越出数据源根目录: " + relative);
            }
        }
        return candidate;
    }
}
