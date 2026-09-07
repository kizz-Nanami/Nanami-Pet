package com.example.cs.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

@Component
public class FileTools {

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(FileTools.class);

    //  1.操作文件 查路径、读文件、找文件、打开文件 --- 权限问题、约束、系统安全
    //  2.路径安全【所有的操作必须在用户主目录里】安全边界
    private static final Path HOME = Paths.get(System.getProperty("user.home"));
    //  3.大小安全【保护AI额度 token（阅读、推理、输出） 100kb】
    private static final int MAX_FILE_SIZE = 100 * 1024;
    private static final int MAX_READ_CHARS = 5000;
    private static final int MAX_SEARCH_RESULTS = 30;
    //1.封装文件安全的方法 路径校验 + 路径拼接 桌面 --> 用户主目录 + 桌面相对路径
    private Path resolveSafePath(String input) {
        Path resolved = HOME.resolve(input.replace("\\","/")).normalize();
        //安全检查
        if(!resolved.startsWith(HOME)) {
            throw new SecurityException("路径超出允许范围 " + input);
        }
        return resolved;
    }
    //2.解析文件大小 -- 格式解析 字节数
    private String formatSize(long bytes) {
        if(bytes < 1024) return bytes + " B";
        if(bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
    //  4.类型安全【防止喂一些乱码文件】 -- 文本文件 检查
    private boolean isTextFile(Path path) throws IOException {
        // ★ 只读前512字节判断类型（修复：原 readAllBytes 会把整个文件读进内存，大文件触发 OOM）
        byte[] head = new byte[512];
        int len = 0;
        try (java.io.InputStream in = Files.newInputStream(path)) {
            int r;
            while (len < head.length && (r = in.read(head, len, head.length - len)) != -1) len += r;
        }
        if (len == 0) return true; // 空文件视为文本（防除零）
        int noPrintable = 0;
        //统计非可打印的字符数量
        for (int i = 0; i < len; i++) {
            int b = head[i] & 0xff; //把byte转化为0-255的整数
            if (b < 0x09 || (b > 0x0D && b < 0x20)) {
                //计数二进制字符
                noPrintable++;
            }
        }
        return (double) noPrintable / len < 0.3;
    }
    //工具一：读取目录下的文件
    @Tool(description = "列出指定目录下的文件和子目录，参数path是相对于用户主目录的路径")
    public String listDirectory(
            @ToolParam(description = "需要列出的目标目录路径，相对于用户主目录") String path
    ) throws IOException {
        //1.安全解析路径 + 参数校验
        Path dir = resolveSafePath(path.isEmpty() ? "" : path);
        //2.验证路径是否是一个目录
        if (!Files.isDirectory(dir)) {
            return "路径不是目录" + dir;
        }
        //3.遍历目录 拼接返回数字字符串
        //list返回目录下的所有条目 sorted排序
        // ★ try-with-resources：Files.list 返回的 Stream 持有目录句柄，不关闭会在 Windows 上
        //   造成句柄泄漏（影响目录删除）；同时移除调试用的 System.out.println
        String listing;
        try (var stream = Files.list(dir)) {
            listing = stream.sorted().map(p -> {
                //分类 -- 文件/目录
                String prefix = Files.isDirectory(p) ? "[DIR]" : "[FILE]";
                //判断文件大小
                String size = Files.isDirectory(p) ? " " : " " + formatSize(p.toFile().length());
                return prefix + p.getFileName() + size;
            }).collect(Collectors.joining("\n")); //用换行符拼接成一个大的字符串
        }
        return listing.isEmpty() ? "目录为空" : listing;
    }

    //工具二：readFile
    @Tool(description = "读取文件内容，只能读文本文件，超过5000个字节会被截断，参数path是相当于用户主目录")
    public String readFile(
            @ToolParam(description = "需要读取的目标文件路径，相对于用户主目录") String path
    ) throws IOException {
        //1,路径安全
        Path file = resolveSafePath(path);
        //2.必须是一个文件
        if(!Files.isRegularFile(file)) {
            return "文件不存在" + file;
        }
        //3.文件大小限制
        if(Files.size(file) > MAX_FILE_SIZE) {
            return "文件过大" + file + "超出100kb的限制";
        }
        //4。文本/二进制
        if(!isTextFile(file)) {
            return "这是一个二进制文件无法读取";
        }
        //5.读取内容 + 截断超出范围的字符
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if(content.length() > MAX_READ_CHARS) {
            content = content.substring(0, MAX_READ_CHARS) + "\n...(已截断到5000字)";
        }
        return "文件内容（" + file.getFileName() + "）" + content;
    }
    //工具三：打开文件openFile
    @Tool(description = "用系统的默认程序打开文件，参数path是相当于用户主目录")
    public String openFile(
            @ToolParam(description = "需要打开的目标文件路径，相当于用户主目录") String path
    ) {
        //1.安全解析路径
        Path file = resolveSafePath(path);
        //2.判断文件类型
        if(!Files.isRegularFile(file)) {
            return "文件不存在" + file;
        }

        //调用打开文件的系统函数
        // ★ 修复：Spring Boot 默认设置 java.awt.headless=true，AWT 的 Desktop.getDesktop() 会抛
        //   HeadlessException（且 message 为 null，AI 只能收到"打开文件失败：null"）。
        //   改用 Windows 标准的 rundll32 FileProtocolHandler（资源管理器同款机制）：
        //   不依赖 AWT/headless，参数独立传递，含空格路径也不会被拆断
        try {
            Process p = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", file.toAbsolutePath().toString()).start();
            // ★ 等待退出并检查返回码：rundll32 可能静默失败（进程在但窗口没弹），此前只 start 不检查，
            //   AI 一律收到"已打开文件"，与实际不符
            boolean exited = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            int code = exited ? p.exitValue() : -1;
            logger.info("[openFile] path={}, rundll32 exited={}, exitCode={}", file.toAbsolutePath(), exited, code);
            if (exited && code != 0) {
                return "打开文件失败：系统返回码 " + code + "（文件关联可能损坏或路径无法访问）";
            }
            return "已打开文件：" + file.getFileName();
        } catch (Exception e) {
            logger.warn("[openFile] 失败: {}", e.toString());
            return "打开文件失败：" + e; // 用 toString 而非 getMessage（部分异常 message 为 null）
        }
    }
}
