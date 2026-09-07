package com.example.cs.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * ★ 天气查询工具（Open-Meteo 免费接口，无需 API key）
 * 城市名 → geocoding 接口查经纬度 → forecast 接口取实时天气
 * 用户未指定城市时使用 application.properties 中 pet.city 配置的默认城市
 */
@Component
public class WeatherTools {

    // 默认城市用拼音（geonames 数据库对部分中文城市名检索有盲区，拼音/英文检索最可靠）
    @Value("${pet.city:Sanming}")
    private String defaultCity;

    private final ObjectMapper mapper = new ObjectMapper();

    // JDK 内置 HttpClient：10 秒超时，防止外部接口挂起阻塞工具调用（外层还有 10s 工具超时兜底）
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Tool(description = "查询指定城市当前的实时天气（温度、天气现象、湿度、风速、今日温度范围）。用户问天气、出门穿衣建议等场景调用。城市参数必须用拼音或英文名（如 hangzhou、sanming、beijing），严禁传中文汉字；用户没说城市时不传参数，将使用默认城市")
    public String getWeather(
            @ToolParam(description = "城市拼音或英文名，例如 hangzhou、sanming；留空表示使用默认城市", required = false) String city
    ) {
        String target = (city == null || city.isBlank()) ? defaultCity : city.trim();
        try {
            // 1. 城市名 → 经纬度（中文 + 拼音双检索后打分选优，避开同名村镇等错误定位）
            JsonNode loc = geocode(target);
            if (loc == null) {
                return "找不到城市「" + target + "」的天气信息";
            }
            double lat = loc.path("latitude").asDouble();
            double lon = loc.path("longitude").asDouble();
            String admin1 = loc.path("admin1").asText("");
            String resolvedName = (admin1.isEmpty() || admin1.equals("直辖市") ? "" : admin1)
                    + loc.path("name").asText(target);

            // 2. 经纬度 → 实时天气 + 今日温度范围
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                    + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1";
            JsonNode root = mapper.readTree(get(url));
            JsonNode cur = root.path("current");
            if (cur.isMissingNode()) return "天气接口没有返回当前数据，请稍后再试";

            double temp = cur.path("temperature_2m").asDouble();
            int code = cur.path("weather_code").asInt();
            int humidity = cur.path("relative_humidity_2m").asInt();
            double wind = cur.path("wind_speed_10m").asDouble();
            String dayRange = root.path("daily").path("temperature_2m_min").path(0).asText() + "~"
                    + root.path("daily").path("temperature_2m_max").path(0).asText() + "℃";

            return resolvedName + "当前天气：" + describeWeatherCode(code)
                    + "，气温" + temp + "℃，今日" + dayRange
                    + "，湿度" + humidity + "%，风速" + wind + "km/h";
        } catch (Exception e) {
            return "天气查询失败（可能是网络问题），请稍后再试：" + e.getMessage();
        }
    }

    /**
     * ★ 城市定位：检索候选后打分选优（调用方约定传拼音/英文名）
     * 背景：geonames 的中文检索有盲区——如中文"三明"只能查到四川雅安的同名村庄（海拔1870m），
     * 福建三明市只有英文名 Sanming 能命中；且同名村镇很多。
     * 行政级别/人口/名称精确匹配的打分机制可通用地避开这类错误定位（如机场条目）
     */
    private JsonNode geocode(String city) throws Exception {
        JsonNode results = searchGeocode(city);
        java.util.LinkedHashMap<Long, JsonNode> merged = new java.util.LinkedHashMap<>();
        collectCandidates(results, merged);
        if (merged.isEmpty()) return null;

        // 打分选优：精确名称匹配 > 行政级别 > 人口规模（机场/村镇等自然落败）
        String norm = city.endsWith("市") ? city.substring(0, city.length() - 1) : city;
        JsonNode best = null;
        long bestScore = Long.MIN_VALUE;
        for (JsonNode c : merged.values()) {
            String code = c.path("feature_code").asText("");
            if (code.startsWith("AIRP") || code.startsWith("AIRH")) continue; // 排除机场
            long score = 0;
            String name = c.path("name").asText("");
            String nameNorm = name.endsWith("市") || name.endsWith("县") ? name.substring(0, name.length() - 1) : name;
            if (name.equals(city) || nameNorm.equals(norm)) score += 1_000_000; // 名称精确匹配
            score += switch (code) {
                case "PPLC" -> 80_000;             // 首都
                case "PPLA", "ADM1" -> 60_000;     // 一级行政中心
                case "PPLA2", "ADM2" -> 50_000;    // 二级行政中心（地级市）
                case "PPLA3", "PPLA4", "ADM3" -> 40_000;
                case "PPLX" -> 30_000;             // 大都市的区
                default -> 0;                      // 普通村镇 PPL 等
            };
            score += Math.min(c.path("population").asLong(0), 900_000); // 人口加权
            if (score > bestScore) { bestScore = score; best = c; }
        }
        return best;
    }

    // 单次 geocoding 检索（count=10），按 id 去重合并
    private void collectCandidates(JsonNode results, java.util.Map<Long, JsonNode> merged) {
        if (results.isArray()) {
            for (JsonNode r : results) merged.putIfAbsent(r.path("id").asLong(), r);
        }
    }

    private JsonNode searchGeocode(String query) throws Exception {
        String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&count=10&language=zh&format=json";
        return mapper.readTree(get(url)).path("results");
    }

    // WMO 天气代码 → 中文描述
    private String describeWeatherCode(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "基本晴朗";
            case 2 -> "局部多云";
            case 3 -> "阴";
            case 45, 48 -> "有雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61 -> "小雨";
            case 63 -> "中雨";
            case 65 -> "大雨";
            case 66, 67 -> "冻雨";
            case 71 -> "小雪";
            case 73 -> "中雪";
            case 75 -> "大雪";
            case 77 -> "雪粒";
            case 80 -> "小阵雨";
            case 81 -> "阵雨";
            case 82 -> "强阵雨";
            case 85, 86 -> "阵雪";
            case 95 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "天气代码 " + code;
        };
    }

    // GET 请求（跟随重定向，10 秒超时，非 2xx 抛异常）
    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
