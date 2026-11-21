package com.datagov.app.runner;

import java.nio.file.Path;
import java.util.Map;

/**
 * ETL 管道读写「文件 / 对象」的唯一缝隙(SOURCE_FILE / SINK_PARQUET)。
 *
 * <p>计划里<b>永远不会出现服务器绝对路径</b>:对象只用 {@link ObjectRef} 定位 —— 平台文件 ID,
 * 或「对象存储数据源 ID + 相对路径」。实现负责把它解析成真实位置并做越界校验,
 * 引擎只拿到一个自己创建的临时文件。
 *
 * <p>安全顺序(见 {@link EtlPipelineEngine}):{@link #fetch} 在 DuckDB 锁定外部访问<b>之前</b>调用,
 * 引擎再把临时文件读进内存临时表;{@link #put} 在管道成功写完临时 Parquet 后调用,
 * 用户 SQL 从头到尾碰不到文件系统或网络。
 */
public interface EtlObjectStore {

    /**
     * 对象定位。{@code fileId} 与 {@code dataSourceId + path} 二选一。
     *
     * @param path 数据源内的相对路径(不含 ..、不是绝对路径)
     */
    record ObjectRef(String fileId, String dataSourceId, String path) {

        public String describe() {
            return fileId != null ? "fileId=" + fileId : "dataSourceId=" + dataSourceId + " path=" + path;
        }
    }

    /**
     * 取到的本地副本。{@code format} 可能与请求的不同:有些实现(S3 系)用独立的临时 DuckDB
     * 连接读远端后转码成 PARQUET,此时返回 {@code PARQUET}。
     */
    record Fetched(Path path, String format) {
    }

    /**
     * 把对象落到 {@code dir} 下的临时文件。
     *
     * @param format  CSV / JSON / NDJSON / PARQUET
     * @param options 文件读取选项(已由调用方原样透传,实现若要用 DuckDB 读须走 {@link EtlFileFormats})
     */
    Fetched fetch(String workspaceId, ObjectRef ref, String format, Map<String, Object> options, Path dir)
            throws Exception;

    /**
     * 把本地 Parquet 文件写到目标对象(覆盖语义,尽量原子)。
     *
     * @param compression SNAPPY / ZSTD / GZIP / NONE;源文件已按它压缩,仅需转存的实现用它重编码
     */
    void put(String workspaceId, ObjectRef ref, Path source, String compression) throws Exception;
}
