package com.lrj.wms.runtime.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 有界键集分页。游标绑定资源和身份范围，不能被误用于另一筛选；授权仍由 SQL 范围保证。 */
public record CursorPage(int limit, String id, LocalDateTime time, String scope) {
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    /** 长度与类型由 JSON 数组编码，避免标识符含冒号时产生查询范围碰撞。 */
    public static String scope(Object... parts) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(parts)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    /** 缺省第一页；非法或超范围输入必须明确拒绝，不能悄悄换成第一页。 */
    public static CursorPage parse(Integer limit, String cursor, String scope) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new InvalidPageException("limit 必须在 1–200 之间");
        }
        if (cursor == null || cursor.isBlank()) {
            return new CursorPage(size, null, null, scope);
        }
        if (cursor.length() > 2048) {
            throw new InvalidPageException("游标过长");
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(cursor)))) {
            if (in.readUnsignedByte() != 1 || !scope.equals(in.readUTF())) {
                throw new InvalidPageException("游标不属于当前查询范围");
            }
            String id = in.readUTF();
            String time = in.readUTF();
            if (id.isBlank() || id.length() > 64 || in.available() != 0) {
                throw new InvalidPageException("游标无效");
            }
            return new CursorPage(size, id, time.isEmpty() ? null : LocalDateTime.parse(time), scope);
        } catch (InvalidPageException error) {
            throw error;
        } catch (Exception error) {
            throw new InvalidPageException("游标无效");
        }
    }

    /** 多取一条判断是否有下一页，不把恰好满页误认为尚有数据。 */
    public int fetchLimit() {
        return limit + 1;
    }

    /** MyBatis OGNL 属性访问兼容 JavaBean 读取方式。 */
    public int getFetchLimit() {
        return fetchLimit();
    }

    /** 时间排序列表拒绝缺少时间的游标，避免 NULL 比较造成静默空页。 */
    public static CursorPage chronological(Integer limit, String cursor, String scope) {
        CursorPage page = parse(limit, cursor, scope);
        if (page.id() != null && page.time() == null) {
            throw new InvalidPageException("时间游标缺少创建时间");
        }
        return page;
    }

    /** 输入按 SQL 顺序排列；单据以 created_at + id 倒序，主数据以 id 升序。 */
    public Map<String, Object> result(List<Map<String, Object>> fetched, boolean chronological) {
        List<Map<String, Object>> items = List.copyOf(fetched.subList(0, Math.min(limit, fetched.size())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("limit", limit);
        if (fetched.size() > limit) {
            Map<String, Object> last = items.getLast();
            Object raw = chronological ? last.get("created_at") : null;
            String time = raw instanceof Timestamp value ? value.toLocalDateTime().toString()
                    : raw == null ? "" : raw.toString();
            result.put("nextCursor", encode(String.valueOf(last.get("id")), time));
        }
        return result;
    }

    private String encode(String id, String time) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeByte(1);
                out.writeUTF(scope);
                out.writeUTF(id);
                out.writeUTF(time);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (java.io.IOException error) {
            throw new IllegalStateException("游标编码失败", error);
        }
    }
}
