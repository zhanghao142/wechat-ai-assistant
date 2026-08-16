package com.wechatai.ai.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从出行/路线类工具结果中硬提取桌面地图用的 route 附件（不经 LLM）。
 * 兼容滴滴 MCP maps_direction_*（geo_list）与高德 polyline 等常见形态。
 */
public final class RoutePayloadExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RoutePayloadExtractor() {}

    public static boolean looksLikeRouteTool(String toolName) {
        if (toolName == null) return false;
        String n = toolName.toLowerCase(Locale.ROOT);
        return n.contains("direction")
                || n.contains("driving")
                || n.contains("walking")
                || n.contains("bicycling")
                || n.contains("transit")
                || n.contains("route")
                || n.contains("导航")
                || n.contains("路线")
                || n.contains("规划");
    }

    /**
     * @return type=route 的附件 Map；无法提取时返回 null
     */
    public static Map<String, Object> extract(String toolName, Map<String, Object> args, String resultJson) {
        boolean toolOk = looksLikeRouteTool(toolName) || hasRouteArgs(args);
        boolean bodyOk = resultJson != null && (
                resultJson.contains("polyline")
                        || resultJson.contains("geo_list")
                        || resultJson.contains("\"path\"")
                        || resultJson.contains("steps"));
        if (!toolOk && !bodyOk) {
            return null;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "route");

        String originRaw = firstString(args, "origin", "from", "start", "origin_name", "departure", "起点");
        String destRaw = firstString(args, "destination", "to", "end", "dest", "destination_name", "arrival", "终点");

        double[] originLL = parseLngLatString(originRaw);
        double[] destLL = parseLngLatString(destRaw);
        if (originLL != null) {
            out.put("originLngLat", List.of(originLL[0], originLL[1]));
            out.put("origin", looksLikeCoordString(originRaw) ? "起点" : originRaw);
        } else if (originRaw != null) {
            out.put("origin", originRaw);
        }
        if (destLL != null) {
            out.put("destLngLat", List.of(destLL[0], destLL[1]));
            out.put("destination", looksLikeCoordString(destRaw) ? "终点" : destRaw);
        } else if (destRaw != null) {
            out.put("destination", destRaw);
        }

        String mode = inferMode(toolName);
        if (mode != null) out.put("mode", mode);

        List<double[]> path = new ArrayList<>();
        try {
            if (resultJson != null && !resultJson.isBlank()) {
                JsonNode root = tryParse(resultJson);
                if (root != null) {
                    // 滴滴：优先 geo_list
                    collectGeoList(root, path);
                    if (path.size() < 2) {
                        collectPath(root, path, 0);
                    }
                    fillNamesFromResult(root, out);
                    fillMetricsFromResult(root, out);

                    // 结果里的起终点坐标
                    if (!out.containsKey("originLngLat")) {
                        double[] o = readLngLat(root.get("origin"));
                        if (o == null && root.has("origin") && root.get("origin").has("coordinates")) {
                            o = parseLngLatString(root.get("origin").get("coordinates").asText());
                        }
                        if (o != null) out.put("originLngLat", List.of(o[0], o[1]));
                    }
                    if (!out.containsKey("destLngLat")) {
                        double[] d = readLngLat(root.get("destination"));
                        if (d == null && root.has("destination") && root.get("destination").has("coordinates")) {
                            d = parseLngLatString(root.get("destination").get("coordinates").asText());
                        }
                        if (d != null) out.put("destLngLat", List.of(d[0], d[1]));
                    }
                } else {
                    decodePolylineString(resultJson, path);
                }
            }
        } catch (Exception ignored) {
            /* keep partial */
        }

        if (path.size() >= 2) {
            // 抽稀：过密点影响前端性能
            List<double[]> slim = downsample(path, 800);
            List<List<Double>> pts = new ArrayList<>();
            for (double[] p : slim) {
                pts.add(List.of(p[0], p[1]));
            }
            out.put("path", pts);
            out.put("originLngLat", List.of(slim.get(0)[0], slim.get(0)[1]));
            out.put("destLngLat", List.of(slim.get(slim.size() - 1)[0], slim.get(slim.size() - 1)[1]));
        }

        if (out.containsKey("path")
                || (out.containsKey("originLngLat") && out.containsKey("destLngLat"))
                || (out.get("origin") != null && out.get("destination") != null
                && !looksLikeCoordString(String.valueOf(out.get("origin"))))) {
            return out;
        }
        return null;
    }

    private static void fillNamesFromResult(JsonNode root, Map<String, Object> out) {
        if (!out.containsKey("origin") || "起点".equals(out.get("origin"))) {
            String o = textAt(root, "start_name", "startName", "origin_name", "originName");
            if (o != null) out.put("origin", o);
        }
        if (!out.containsKey("destination") || "终点".equals(out.get("destination"))) {
            String d = textAt(root, "end_name", "endName", "destination_name", "destinationName");
            if (d != null) out.put("destination", d);
        }
    }

    private static void fillMetricsFromResult(JsonNode root, Map<String, Object> out) {
        Double dist = nestedNumber(root, "distance");
        if (dist == null) dist = numberAt(root, "distance_meters", "distanceMeters");
        if (dist != null) out.put("distanceMeters", dist);

        Double dur = nestedNumber(root, "duration");
        if (dur == null) dur = numberAt(root, "duration_seconds", "durationSeconds");
        if (dur != null) out.put("durationSeconds", dur);
    }

    /** distance: { value: 39597 } 或纯数字 */
    private static Double nestedNumber(JsonNode root, String field) {
        if (root == null || !root.has(field)) return null;
        JsonNode n = root.get(field);
        if (n.isNumber()) return n.asDouble();
        if (n.isObject() && n.has("value") && n.get("value").isNumber()) {
            return n.get("value").asDouble();
        }
        if (n.isTextual()) {
            try {
                return Double.parseDouble(n.asText().trim().replace("km", "").trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void collectGeoList(JsonNode root, List<double[]> sink) {
        if (root == null) return;
        JsonNode list = root.get("geo_list");
        if (list == null) list = root.get("geoList");
        if (list == null || !list.isArray()) return;
        for (JsonNode p : list) {
            double[] ll = readLngLat(p);
            if (ll != null) sink.add(ll);
            if (sink.size() > 8000) break;
        }
    }

    private static boolean hasRouteArgs(Map<String, Object> args) {
        if (args == null) return false;
        return firstString(args, "origin", "from", "start") != null
                && firstString(args, "destination", "to", "end") != null;
    }

    private static String inferMode(String toolName) {
        if (toolName == null) return null;
        String n = toolName.toLowerCase(Locale.ROOT);
        if (n.contains("walk")) return "walking";
        if (n.contains("bicyc") || n.contains("骑行")) return "bicycling";
        if (n.contains("transit") || n.contains("公交") || n.contains("地铁")) return "transit";
        if (n.contains("driv") || n.contains("驾车")) return "driving";
        return "driving";
    }

    private static JsonNode tryParse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static void collectPath(JsonNode node, List<double[]> sink, int depth) {
        if (node == null || sink.size() > 5000 || depth > 8) return;

        if (node.isTextual()) {
            decodePolylineString(node.asText(), sink);
            return;
        }
        if (node.isArray() && looksLikeLngLatPair(node)) {
            sink.add(new double[]{node.get(0).asDouble(), node.get(1).asDouble()});
            return;
        }

        for (String key : List.of(
                "geo_list", "geoList", "polyline", "path", "points", "steps", "routes")) {
            if (!node.has(key)) continue;
            JsonNode child = node.get(key);
            if (("polyline".equals(key)) && child.isTextual()) {
                decodePolylineString(child.asText(), sink);
            } else if (("geo_list".equals(key) || "geoList".equals(key)) && child.isArray()) {
                for (JsonNode p : child) {
                    double[] ll = readLngLat(p);
                    if (ll != null) sink.add(ll);
                }
            } else if ("steps".equals(key) && child.isArray()) {
                for (JsonNode step : child) {
                    if (step.has("polyline")) {
                        decodePolylineString(step.get("polyline").asText(), sink);
                    } else if (step.has("path")) {
                        collectPath(step.get("path"), sink, depth + 1);
                    } else if (step.has("geo_list")) {
                        collectPath(step.get("geo_list"), sink, depth + 1);
                    }
                }
            } else if ("routes".equals(key) && child.isArray() && !child.isEmpty()) {
                collectPath(child.get(0), sink, depth + 1);
            } else {
                collectPath(child, sink, depth + 1);
            }
            if (sink.size() >= 2 && ("geo_list".equals(key) || "geoList".equals(key) || "polyline".equals(key))) {
                return;
            }
        }

        if (node.isArray()) {
            for (JsonNode c : node) {
                double[] ll = readLngLat(c);
                if (ll != null) {
                    sink.add(ll);
                } else if (!(c.isObject() && (c.has("lng") || c.has("lat") || c.has("latitude")))) {
                    // 避免把 distance 等无关对象扫进来；仅继续数组点列
                    if (c.isArray()) collectPath(c, sink, depth + 1);
                }
                if (sink.size() > 5000) break;
            }
        }
    }

    private static double[] readLngLat(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        JsonNode lng = node.get("lng");
        if (lng == null) lng = node.get("longitude");
        JsonNode lat = node.get("lat");
        if (lat == null) lat = node.get("latitude");
        if (lng != null && lat != null && lng.isNumber() && lat.isNumber()) {
            double x = lng.asDouble();
            double y = lat.asDouble();
            if (Math.abs(x) <= 180 && Math.abs(y) <= 90) {
                return new double[]{x, y};
            }
        }
        if (node.has("coordinates") && node.get("coordinates").isTextual()) {
            return parseLngLatString(node.get("coordinates").asText());
        }
        return null;
    }

    private static boolean looksLikeLngLatPair(JsonNode arr) {
        return arr.size() >= 2 && arr.get(0).isNumber() && arr.get(1).isNumber()
                && Math.abs(arr.get(0).asDouble()) <= 180
                && Math.abs(arr.get(1).asDouble()) <= 90;
    }

    private static boolean looksLikeCoordString(String s) {
        return parseLngLatString(s) != null;
    }

    /** "116.37,39.86" → [lng, lat] */
    private static double[] parseLngLatString(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (!t.contains(",")) return null;
        String[] parts = t.split(",");
        if (parts.length != 2) return null;
        try {
            double a = Double.parseDouble(parts[0].trim());
            double b = Double.parseDouble(parts[1].trim());
            // 中国常见：经度在前
            if (Math.abs(a) <= 180 && Math.abs(b) <= 90) {
                return new double[]{a, b};
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private static void decodePolylineString(String poly, List<double[]> sink) {
        if (poly == null || poly.isBlank()) return;
        String s = poly.trim();
        if (s.contains(";")) {
            for (String part : s.split(";")) {
                double[] ll = parseLngLatString(part);
                if (ll != null) sink.add(ll);
            }
            return;
        }
        String[] nums = s.split(",");
        if (nums.length >= 4 && nums.length % 2 == 0) {
            for (int i = 0; i + 1 < nums.length; i += 2) {
                try {
                    sink.add(new double[]{
                            Double.parseDouble(nums[i].trim()),
                            Double.parseDouble(nums[i + 1].trim())
                    });
                } catch (NumberFormatException ignored) { /* skip */ }
            }
        }
    }

    private static List<double[]> downsample(List<double[]> path, int maxPts) {
        if (path.size() <= maxPts) return path;
        List<double[]> out = new ArrayList<>(maxPts);
        int n = path.size();
        for (int i = 0; i < maxPts; i++) {
            int idx = (int) Math.round(i * (n - 1) * 1.0 / (maxPts - 1));
            out.add(path.get(idx));
        }
        return out;
    }

    private static String firstString(Map<String, Object> args, String... keys) {
        if (args == null) return null;
        for (String k : keys) {
            Object v = args.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v).trim();
            }
        }
        return null;
    }

    private static String textAt(JsonNode root, String... keys) {
        for (String k : keys) {
            JsonNode n = root.findValue(k);
            if (n != null && n.isTextual() && !n.asText().isBlank()) return n.asText().trim();
        }
        return null;
    }

    private static Double numberAt(JsonNode root, String... keys) {
        for (String k : keys) {
            JsonNode n = root.findValue(k);
            if (n != null && n.isNumber()) return n.asDouble();
            if (n != null && n.isTextual()) {
                try {
                    return Double.parseDouble(n.asText().trim());
                } catch (NumberFormatException ignored) { /* next */ }
            }
        }
        return null;
    }
}
