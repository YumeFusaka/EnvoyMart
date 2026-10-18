package yumefusaka.envoymart.agent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 有限医学验收的必要要点账本；只用于评测，不参与生产生成或医学事实审核。 */
final class MedicalCompletenessContract {
    private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();
    private static final String CURRENT_RELATION_OBJECT = "(?:磷虾油(?:软胶囊)?(?:与|和|及|、)阿司匹林|阿司匹林(?:与|和|及|、)磷虾油(?:软胶囊)?)(?:之间)?(?:的)?(?:具体)?(?:关系|相互作用|合用风险|风险|能否合用)";
    private static final String UNRESOLVED_DECISION = "(?:无法|不能)(?:根据(?:本次|当前)?(?:图谱检查|图谱|检查|资料|证据))?(?:判断|确认|确定|判定)";
    private static final Pattern POSTPOSED_RELATION_OBJECT = Pattern.compile(UNRESOLVED_DECISION + "(?:关于)?" + CURRENT_RELATION_OBJECT);
    private static final Pattern PREPOSED_RELATION_OBJECT = Pattern.compile(CURRENT_RELATION_OBJECT + "[，,：:]*(?:本次|当前)?"
            + UNRESOLVED_DECISION + "(?:[，,]*(?:请咨询(?:医师|医生|药师)(?:或(?:医师|医生|药师))?|请遵医嘱))?[。！？!?；;]*$");
    private MedicalCompletenessContract() { }

    private record Point(String id, String claim, Pattern population) { }

    static boolean hasFiniteClinicalContract(GroundingEvaluator.Sample sample) {
        return sample.id() != null && List.of("G-06", "G-13", "G-17", "KG-02", "KG-08", "KG-09", "KG-10", "KG-12").contains(sample.id());
    }

    static boolean hasCurrentInteractionContract(GroundingEvaluator.Sample sample) {
        return "KG-05".equals(sample.id()) && currentInteraction(sample) != null;
    }

    static List<String> interactionMissing(GroundingEvaluator.Sample sample) {
        Map<String, Boolean> found = currentInteraction(sample);
        if (found == null) return List.of("当前相互作用检查不可核验");
        String answer = sample.answer() == null ? "" : MedicalEvidenceVerifier.normalize(sample.answer());
        List<String> missing = new ArrayList<>();
        if (!answer.contains("磷虾油") || !answer.contains("阿司匹林")) missing.add("当前检查的两个对象");
        boolean unresolved = Pattern.compile("[^。！？!?；;\\n]+[。！？!?；;]*")
                .matcher(sample.answer() == null ? "" : sample.answer()).results()
                .map(match -> MedicalEvidenceVerifier.normalize(match.group()))
                .anyMatch(sentence -> sentence.matches(".*(?:本次|检查|本品).*" )
                        && (POSTPOSED_RELATION_OBJECT.matcher(sentence).find()
                        || PREPOSED_RELATION_OBJECT.matcher(sentence).find()));
        if (found.values().stream().allMatch(Boolean::booleanValue)) {
            String withoutReminder = answer.replaceAll("(?:没有收录|未收录)不等于安全", "");
            if (withoutReminder.matches(".*(?:没有收录|未收录|未命中).*")) missing.add("不得借先前未收录结论");
            boolean risk = MedicalEvidenceVerifier.factSentences(sample.answer()).stream()
                    .anyMatch(sentence -> currentPair(sentence)
                            && sentence.matches(".*(?:出血|风险).*" ));
            if (!risk && !unresolved) missing.add("当前产品风险或关系未决");
        } else {
            if (!unresolved) missing.add("未知对象关系不能据本次检查判断");
            if (!answer.matches(".*(?:没有收录|未收录)不等于(?:安全|无风险).*")) missing.add("未收录不等于安全");
        }
        return List.copyOf(missing);
    }

    private static boolean currentPair(String sentence) {
        return sentence.contains("磷虾油") && sentence.contains("阿司匹林");
    }

    private static Map<String, Boolean> currentInteraction(GroundingEvaluator.Sample sample) {
        if (!"KG-05".equals(sample.id())) return null;
        for (int index = sample.tools().size() - 1; index >= 0; index--) {
            var tool = sample.tools().get(index);
            if (!"interaction_check".equals(tool.getTool())) continue;
            if (!tool.isSuccess() || tool.isNoData() || tool.getRawData() == null) return null;
            Object decoded;
            try { decoded = MAPPER.convertValue(tool.getRawData(), Object.class); }
            catch (RuntimeException invalid) { return null; }
            if (!(decoded instanceof Map<?, ?> report) || !Boolean.TRUE.equals(report.get("available"))
                    || !(report.get("items") instanceof List<?> items) || items.size() != 2) return null;
            Map<String, Boolean> found = new java.util.LinkedHashMap<>();
            for (Object raw : items) {
                if (!(raw instanceof Map<?, ?> item) || !(item.get("input") instanceof String input)
                        || !(item.get("found") instanceof Boolean present)) return null;
                String label = item.get("label") instanceof String value ? value : input;
                String key = input.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
                if (key.equals("阿司匹林")) found.put("阿司匹林", present);
                else if (key.equals("磷虾油") || key.equals("磷虾油软胶囊")
                        || key.matches("spu\\d+") && label.replaceAll("\\s+", "").contains("磷虾油")) {
                    found.put("磷虾油", present);
                } else return null;
            }
            return found.size() == 2 ? Map.copyOf(found) : null;
        }
        return null;
    }

    static List<String> missing(GroundingEvaluator.Sample sample) {
        List<Point> points = new ArrayList<>();
        switch (sample.id()) {
            case null -> { }
            case "G-06" -> {
                if (sample.evidence() != null) for (DocumentChunk chunk : sample.evidence()) {
                    if (chunk == null || chunk.getTitle() == null || sample.evidence().stream().filter(other -> other != null
                            && chunk.getTitle().equals(other.getTitle())).count() < 2) continue;
                    var amount = Pattern.compile("每片含铁(\\d+(?:\\.\\d+)?毫克).*?叶酸(\\d+(?:\\.\\d+)?微克)")
                            .matcher(MedicalEvidenceVerifier.normalize(chunk.getContent()));
                    if (amount.find()) points.add(new Point("版本成分:" + amount.group(1) + ":" + amount.group(2),
                            "每片含铁" + amount.group(1) + "与叶酸" + amount.group(2), null));
                }
            }
            case "G-13" -> {
                points.add(new Point("铁与左旋多巴的吸收方向", "铁剂与左旋多巴合用会降低左旋多巴的吸收", null));
                points.add(new Point("铁与左旋多巴的有效间隔下界", "铁剂与左旋多巴至少间隔2小时", null));
            }
            case "G-17" -> {
                points.add(new Point("钙与四环素类的间隔下界", "钙与四环素类抗生素至少间隔2小时", null));
                points.add(new Point("钙与喹诺酮类的间隔下界", "钙与喹诺酮类抗生素至少间隔2小时", null));
            }
            case "KG-02" -> points.add(new Point("钙与喹诺酮类的间隔下界", "钙与喹诺酮类抗生素至少间隔2小时", null));
            case "KG-08" -> {
                points.add(new Point("DHA与抗凝药的出血风险", "DHA与抗凝药物合用可能增加出血风险", null));
                points.add(new Point("孕期人群", "孕期DHA与抗凝药物合用可能增加出血风险", Pattern.compile("孕期|孕妇|妊娠|产检|产科")));
            }
            case "KG-09" -> {
                points.add(new Point("儿童间隔下界", "儿童服用钙与四环素类抗生素应间隔至少2小时", null));
                points.add(new Point("儿童人群", "儿童服用钙与四环素类抗生素应间隔至少2小时", Pattern.compile("儿童|孩子|患儿")));
            }
            case "KG-10" -> points.add(new Point("抗生素先用与本品后用的时间约束", "在服用抗生素至少2小时后再食用益生菌", null));
            case "KG-12" -> points.add(new Point("EPA和DHA与氯吡格雷的出血风险", "EPA与DHA和氯吡格雷合用可能增加出血风险", null));
            default -> { }
        }
        List<Point> answerable = points.stream().distinct().filter(point -> supported(point.claim(), sample.evidence())).toList();
        if (answerable.isEmpty() && hasFiniteClinicalContract(sample)) return List.of("核心必要要点没有可核验强来源");
        List<String> missing = new ArrayList<>(answerable.stream()
                .filter(point -> point.population() == null ? !supportedByAnswer(point.claim(), sample.answer())
                        : sample.answer() == null || !point.population().matcher(sample.answer()).find())
                .map(Point::id).distinct().toList());
        if ("G-13".equals(sample.id()) && !supported("铁剂与左旋多巴至少间隔2小时", sample.evidence())) {
            missing.add("MISSING_RETRIEVAL_CONTEXT:铁与左旋多巴的至少2小时");
        }
        return List.copyOf(missing);
    }

    private static boolean supported(String claim, List<DocumentChunk> evidence) {
        if (evidence == null) return false;
        for (int ref = 1; ref <= evidence.size(); ref++) {
            // 契约检查只问「这一片原文能不能支撑这个要点」，不是复现整轮证据门。
            // 评测夹具里会混有低分但原文逐字支撑的切片（G-13 的 KB-0010_5 是图谱片、
            // 无重排分），若这里传默认阈值，会把「检索上下文是否具备」与
            // 「这一片是否过统一门槛」混成同一件事，缺的要点被记成 MISSING_RETRIEVAL_CONTEXT。
            if (MedicalEvidenceVerifier.verify(true, claim + " [" + ref + "]。", evidence, List.of(),
                    null, java.util.Set.of()).unsupported().isEmpty()) return true;
        }
        return false;
    }

    private static boolean supportedByAnswer(String claim, String answer) {
        if (answer == null || answer.isBlank()) return false;
        return supported(claim, List.of(DocumentChunk.builder().content(answer).title("已核验回答")
                .chunkId("answer-contract").score(0.9).build()));
    }
}
