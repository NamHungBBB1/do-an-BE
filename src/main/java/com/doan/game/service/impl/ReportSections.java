package com.doan.game.service.impl;

import com.doan.game.DTO.response.SlotReportResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nhận xét dạng có cấu trúc cho FE vẽ thẻ (25/11, Hưng): cùng một hình cho bài AI và bản theo luật. Bài AI: lấy nguyên các
 * mục AI trả (AiJob.output, đã qua ReportAiOutput.check) rồi gắn tên / số đúng-tổng / phương án từ gói (AiJob.input) — số
 * luôn từ gói, không từ chữ AI. Bản theo luật: dựng từ gói, text = null (FE hiện thẻ không lời). Đọc lúc trả API, không lưu.
 */
final class ReportSections {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> LOW = Set.of("PRACTICE", "FAMILIAR");

    private ReportSections() {
    }

    /** Bài AI đã qua kiểm; đầu ra / gói hỏng → null (FE dùng text). */
    static SlotReportResponse.Sections fromAi(String pkgText, String raw) {
        ReportPackage.Pkg pkg = ReportPackage.read(pkgText);
        JsonNode out = read(raw);
        if (pkg == null || pkg.result() == null || out == null || !out.path("overview").isTextual()) {
            return null;
        }
        Ctx c = new Ctx(pkg);
        List<SlotReportResponse.DecisionNote> decisions = new ArrayList<>();
        out.path("decisions").forEach(n -> decisions.add(c.decision(n.path("scene").asText(null), text(n))));
        List<SlotReportResponse.ConceptNote> good = new ArrayList<>();
        out.path("doingWell").forEach(n -> good.add(c.concept(n.path("concept").asText(null), text(n))));
        List<SlotReportResponse.ConceptNote> low = new ArrayList<>();
        out.path("needsPractice").forEach(n -> low.add(c.concept(n.path("concept").asText(null), text(n))));
        List<SlotReportResponse.TipNote> tips = new ArrayList<>();
        out.path("tips").forEach(n -> {
            String id = n.path("concept").asText(null);
            tips.add(new SlotReportResponse.TipNote(id, id == null ? null : KnowledgeUnits.nameOf(id), text(n)));
        });
        return new SlotReportResponse.Sections(out.path("overview").asText().trim(), decisions, good, low, tips,
                c.notEnough());
    }

    /** Bản theo luật: cùng số liệu với ReportPackage.ruleText, không có lời (overview / text = null). */
    static SlotReportResponse.Sections fromPackage(ReportPackage.Pkg pkg) {
        if (pkg == null || pkg.result() == null) {
            return null;
        }
        Ctx c = new Ctx(pkg);
        ReportPackage.Result r = pkg.result();
        List<SlotReportResponse.DecisionNote> decisions = new ArrayList<>();
        if (r.classDecisions() != null) {
            r.classDecisions().forEach(d -> decisions.add(c.decision(d.scene(), null)));
        } else if (r.decisions() != null) {
            r.decisions().forEach(d -> decisions.add(c.decision(d.scene(), null)));
        }
        List<SlotReportResponse.ConceptNote> good = new ArrayList<>();
        List<SlotReportResponse.ConceptNote> low = new ArrayList<>();
        for (ReportPackage.ConceptResult cr : r.concepts() == null ? List.<ReportPackage.ConceptResult>of() : r.concepts()) {
            if ("GOOD".equals(cr.band())) {
                good.add(c.concept(cr.id(), null));
            } else if (LOW.contains(cr.band())) {
                low.add(c.concept(cr.id(), null));
            }
        }
        // Yếu nhất trước, như concepts[] của báo cáo lớp.
        low.sort(java.util.Comparator.comparingDouble(n -> n.total() == 0 ? 2.0 : (double) n.correct() / n.total()));
        return new SlotReportResponse.Sections(null, decisions, good, low, List.of(), c.notEnough());
    }

    /** Dòng nhận xét đã lưu → sections: bài AI nếu có đầu ra, không thì từ gói của việc, cuối cùng từ gói tính sống. */
    static SlotReportResponse.Sections of(com.doan.game.entity.DailySummary row,
                                          java.util.function.Supplier<ReportPackage.Pkg> live) {
        com.doan.game.entity.AiJob job = row == null ? null : row.getAiJob();
        if (row != null && row.getSource() == com.doan.game.enums.SummarySource.LLM && job != null) {
            SlotReportResponse.Sections s = fromAi(job.getInput(), job.getOutput());
            if (s != null) {
                return s;
            }
        }
        ReportPackage.Pkg pkg = job == null ? null : ReportPackage.read(job.getInput());
        return fromPackage(pkg != null ? pkg : live == null ? null : live.get());
    }

    private static String text(JsonNode n) {
        String t = n.path("text").asText(null);
        return t == null ? null : t.trim();
    }

    private static JsonNode read(String raw) {
        try {
            return raw == null ? null : JSON.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** Tra cứu trong gói: khái niệm theo mã, quyết định theo cảnh. */
    private static final class Ctx {
        private final ReportPackage.Result r;
        private final Map<String, ReportPackage.ConceptResult> concepts = new HashMap<>();
        private final Map<String, ReportPackage.DecisionResult> decisions = new HashMap<>();
        private final Map<String, ReportPackage.ClassDecision> classDecisions = new HashMap<>();
        private final Map<String, Map<String, String>> optionLabels = new HashMap<>();

        Ctx(ReportPackage.Pkg pkg) {
            r = pkg.result();
            if (r.concepts() != null) {
                r.concepts().forEach(c -> concepts.put(c.id(), c));
            }
            if (r.decisions() != null) {
                r.decisions().forEach(d -> decisions.put(d.scene(), d));
            }
            if (r.classDecisions() != null) {
                r.classDecisions().forEach(d -> classDecisions.put(d.scene(), d));
            }
            if (pkg.frame() != null && pkg.frame().decisions() != null) {
                pkg.frame().decisions().forEach(f -> {
                    Map<String, String> m = new HashMap<>();
                    if (f.options() != null) {
                        f.options().forEach(o -> m.put(o.id(), o.label()));
                    }
                    optionLabels.put(f.scene(), m);
                });
            }
        }

        SlotReportResponse.ConceptNote concept(String id, String text) {
            ReportPackage.ConceptResult c = id == null ? null : concepts.get(id);
            return new SlotReportResponse.ConceptNote(id, id == null ? null : KnowledgeUnits.nameOf(id),
                    c == null ? null : c.band(), c == null ? 0 : c.correct(), c == null ? 0 : c.total(), text);
        }

        /** Em: phương án bé chọn + có cân nhắc. Lớp: phương án nhiều em chọn nhất + số em / tổng. */
        SlotReportResponse.DecisionNote decision(String scene, String text) {
            ReportPackage.DecisionResult d = scene == null ? null : decisions.get(scene);
            if (d != null) {
                return new SlotReportResponse.DecisionNote(d.label(), d.deliberate(), null, null, text);
            }
            ReportPackage.ClassDecision cd = scene == null ? null : classDecisions.get(scene);
            if (cd != null && cd.counts() != null && !cd.counts().isEmpty()) {
                Map.Entry<String, Integer> top = cd.counts().entrySet().stream().max(Map.Entry.comparingByValue())
                        .orElseThrow();
                int all = cd.counts().values().stream().mapToInt(Integer::intValue).sum();
                String label = optionLabels.getOrDefault(scene, Map.of()).get(top.getKey());
                return new SlotReportResponse.DecisionNote(label, null, top.getValue(), all, text);
            }
            return new SlotReportResponse.DecisionNote(null, null, null, null, text);
        }

        /** Như ReportPackage.noDataLine: khái niệm NO_DATA (kèm x/y) + mã lõi chưa có câu nào. */
        List<SlotReportResponse.ConceptCount> notEnough() {
            List<SlotReportResponse.ConceptCount> out = new ArrayList<>();
            if (r.concepts() != null) {
                r.concepts().stream().filter(c -> "NO_DATA".equals(c.band())).forEach(c -> out.add(
                        new SlotReportResponse.ConceptCount(c.id(), KnowledgeUnits.nameOf(c.id()), c.correct(), c.total())));
            }
            if (r.noData() != null) {
                r.noData().forEach(id -> out.add(new SlotReportResponse.ConceptCount(id, KnowledgeUnits.nameOf(id), 0, 0)));
            }
            return out;
        }
    }
}
