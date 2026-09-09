package com.example.cs.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ★ 桌宠基础工具集（纯后端执行）：时间日期 / 数学计算 / 随机数 / 待办清单
 * 由 ChatService 通过 defaultTools 挂载，AI 按需调用
 */
@Component
public class PetTools {

    // 待办清单持久化文件（用户主目录下，独立于记忆/配置）
    private static final Path TODO_FILE = Paths.get(System.getProperty("user.home"), ".cyberpet-todos.json");
    private final ObjectMapper mapper = new ObjectMapper();

    // 工具一：当前日期时间
    @Tool(description = "获取当前的日期、时间和星期几。当用户询问时间、日期，或对话中需要知道当前时间时调用")
    public String getCurrentDatetime() {
        LocalDateTime now = LocalDateTime.now();
        String dt = now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
        String[] week = {"一", "二", "三", "四", "五", "六", "日"};
        return "当前时间：" + dt + " 星期" + week[now.getDayOfWeek().getValue() % 7];
    }

    // 工具二：数学计算（递归下降四则运算器，支持 + - * / ( ) 和小数）
    @Tool(description = "精确计算数学表达式，支持加减乘除、括号和小数。需要算数（尤其是多位数乘除）时必须调用此工具而不是心算")
    public String calculate(
            @ToolParam(description = "数学表达式，例如 3+4*2 或 (12.5-3)*4") String expression
    ) {
        try {
            double result = new ExprParser(expression).parse();
            if (result == Math.rint(result) && !Double.isInfinite(result)) {
                return expression + " = " + (long) result;
            }
            return expression + " = " + result;
        } catch (Exception e) {
            return "无法计算表达式「" + expression + "」：" + e.getMessage();
        }
    }

    // 工具三：随机数
    @Tool(description = "生成一个指定范围内的随机整数（包含两端）。用于抽签、掷骰子、随机选择等场景")
    public String randomNumber(
            @ToolParam(description = "最小值（整数）") int min,
            @ToolParam(description = "最大值（整数）") int max
    ) {
        if (min > max) { int t = min; min = max; max = t; }
        int v = ThreadLocalRandom.current().nextInt(min, max + 1);
        return "随机结果（" + min + " ~ " + max + "）：" + v;
    }

    // 工具四：添加待办
    @Tool(description = "帮用户添加一条待办事项。当用户说\"提醒我…\"、\"帮我记一下…\"或明确要添加待办时调用")
    public synchronized String addTodo(
            @ToolParam(description = "待办事项的内容描述") String text
    ) {
        try {
            List<String> todos = loadTodos();
            todos.add(text.trim());
            saveTodos(todos);
            return "已添加待办（第 " + todos.size() + " 条）：" + text.trim();
        } catch (Exception e) {
            return "添加待办失败：" + e.getMessage();
        }
    }

    // 工具五：列出待办
    @Tool(description = "查看当前所有的待办事项列表")
    public synchronized String listTodos() {
        try {
            List<String> todos = loadTodos();
            if (todos.isEmpty()) return "待办清单是空的哦";
            StringBuilder sb = new StringBuilder("共 " + todos.size() + " 条待办：");
            for (int i = 0; i < todos.size(); i++) sb.append("\n").append(i + 1).append(". ").append(todos.get(i));
            return sb.toString();
        } catch (Exception e) {
            return "读取待办失败：" + e.getMessage();
        }
    }

    // 工具六：删除待办
    @Tool(description = "按序号删除一条待办事项，序号从1开始（先调用 listTodos 获取序号）")
    public synchronized String removeTodo(
            @ToolParam(description = "要删除的待办序号（从1开始）") int index
    ) {
        try {
            List<String> todos = loadTodos();
            if (index < 1 || index > todos.size()) return "序号 " + index + " 不存在，当前共 " + todos.size() + " 条待办";
            String removed = todos.remove(index - 1);
            saveTodos(todos);
            return "已删除待办：" + removed;
        } catch (Exception e) {
            return "删除待办失败：" + e.getMessage();
        }
    }

    // -- 待办持久化（JSON 文件） --
    private List<String> loadTodos() throws Exception {
        if (!Files.exists(TODO_FILE)) return new ArrayList<>();
        return mapper.readValue(TODO_FILE.toFile(), new TypeReference<List<String>>() { });
    }

    private void saveTodos(List<String> todos) throws Exception {
        mapper.writerWithDefaultPrettyPrinter().writeValue(TODO_FILE.toFile(), todos);
    }

    // -- 四则运算解析器：递归下降，支持 + - * / ( ) 小数与空格 --
    private static class ExprParser {
        private final String src;
        private int pos = 0;

        ExprParser(String src) { this.src = src; }

        double parse() {
            double v = expr();
            skip();
            if (pos < src.length()) throw new IllegalArgumentException("位置 " + (pos + 1) + " 有多余字符");
            return v;
        }

        private double expr() { // 加减
            double v = term();
            while (true) {
                skip();
                if (match('+')) v += term();
                else if (match('-')) v -= term();
                else return v;
            }
        }

        private double term() { // 乘除
            double v = factor();
            while (true) {
                skip();
                if (match('*')) v *= factor();
                else if (match('/')) {
                    double d = factor();
                    if (d == 0) throw new ArithmeticException("除数不能为零");
                    v /= d;
                } else return v;
            }
        }

        private double factor() {
            skip();
            if (match('(')) {
                double v = expr();
                if (!match(')')) throw new IllegalArgumentException("缺少右括号");
                return v;
            }
            if (match('-')) return -factor(); // 一元负号
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
            if (start == pos) throw new IllegalArgumentException("位置 " + (pos + 1) + " 缺少数字");
            return Double.parseDouble(src.substring(start, pos));
        }

        private void skip() { while (pos < src.length() && src.charAt(pos) == ' ') pos++; }
        private boolean match(char c) {
            if (pos < src.length() && src.charAt(pos) == c) { pos++; return true; }
            return false;
        }
    }
}
