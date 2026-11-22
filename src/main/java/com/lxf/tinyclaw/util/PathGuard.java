package com.lxf.tinyclaw.util;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 文件系统安全与跨平台辅助。
 *
 * <p>
 * 1. {@link #resolveInside}：把模型给出的路径限制在 workDir 之内，防止 {@code ../} 越界。<br>
 * 2. POSIX 权限只在支持的文件系统（Linux/macOS）上设置，Windows 上静默跳过。
 */
public final class PathGuard {

    private static final boolean POSIX = FileSystems.getDefault()
            .supportedFileAttributeViews().contains("posix");

    private PathGuard() {
    }

    /**
     * 将相对路径解析到 workDir 下，并校验结果没有逃逸出工作区。
     *
     * @throws SecurityException 路径越界时抛出
     */
    public static Path resolveInside(String workDir, String relativePath) {
        Path base = Paths.get(workDir).toAbsolutePath().normalize();
        Path target = base.resolve(relativePath).normalize();
        if (!target.startsWith(base)) {
            throw new SecurityException("路径越界：" + relativePath + " 不在工作区 " + base + " 之内");
        }
        return target;
    }

    public static void createDirectories(Path dir) throws IOException {
        if (dir == null) {
            return;
        }
        if (POSIX) {
            Files.createDirectories(dir,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        } else {
            Files.createDirectories(dir);
        }
    }

    /** 设置为 rw-r--r--；非 POSIX 文件系统（Windows）直接跳过。 */
    public static void setDefaultFilePermissions(Path file) throws IOException {
        if (POSIX) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }
}
