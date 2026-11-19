package com.doan.game.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Từ điển 65 mã của khung kiến thức v3 (mục 4) cho báo cáo người lớn — classpath content/knowledge-units.json, sinh và
 * kiểm bằng scripts/knowledge_units.py. name / objective / level chép nguyên khung; nameVi = tên ngắn cho phụ huynh (nháp
 * 19/11, chờ HA duyệt). Báo cáo không bao giờ hiện mã thô cho người lớn: luôn qua {@link #nameOf}.
 */
final class KnowledgeUnits {

    record Unit(String id, String domain, String name, String objective, String level, String nameVi) {}

    static final Map<String, Unit> ALL = load();

    private KnowledgeUnits() {
    }

    static Unit get(String id) {
        return id == null ? null : ALL.get(id);
    }

    /** Tên cho phụ huynh; mã ngoài khung (chỉ có trong dữ liệu test) → chính mã. */
    static String nameOf(String id) {
        Unit u = get(id);
        return u == null ? id : u.nameVi();
    }

    private static Map<String, Unit> load() {
        try (InputStream in = KnowledgeUnits.class.getResourceAsStream("/content/knowledge-units.json")) {
            if (in == null) {
                throw new IllegalStateException("thieu content/knowledge-units.json");
            }
            Map<String, Unit> out = new LinkedHashMap<>();
            for (Unit u : new ObjectMapper().readValue(in, new TypeReference<List<Unit>>() {})) {
                out.put(u.id(), u);
            }
            return Map.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("knowledge-units.json hong", e);
        }
    }
}
