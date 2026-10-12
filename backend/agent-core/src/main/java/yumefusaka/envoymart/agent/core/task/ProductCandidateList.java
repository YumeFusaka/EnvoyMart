package yumefusaka.envoymart.agent.core.task;

import yumefusaka.envoymart.agent.llm.ToolExecution;
import yumefusaka.envoymart.agent.tool.ProductCandidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 商品卡片、会话候选和正文权威编号的同一份只读投影。 */
public final class ProductCandidateList {
    private ProductCandidateList() { }

    public record Item(String key, String name, List<String> specs) {
        public Item {
            specs = specs == null ? List.of() : List.copyOf(specs);
        }
    }

    /** 按真实工具执行轨迹顺序合并，商品键去重，第一次出现保留。 */
    public static List<Item> from(List<ToolExecution> executions) {
        Map<String, Item> candidates = new LinkedHashMap<>();
        if (executions == null) return List.of();
        for (ToolExecution execution : executions) {
            if (execution == null || !execution.isSuccess() || !"product_search".equals(execution.getTool())
                    || !(execution.getRawData() instanceof List<?> raw)) continue;
            for (Object value : raw) {
                if (!(value instanceof ProductCandidate candidate)
                        || candidate.candidateKey() == null || candidate.candidateName() == null
                        || candidate.candidateName().isBlank()) continue;
                candidates.putIfAbsent(candidate.candidateKey(), new Item(candidate.candidateKey(),
                        candidate.candidateName(), candidate.candidateSpecs()));
            }
        }
        return List.copyOf(candidates.values());
    }

    /** 只有主动找新候选时换表；单品规格查询、加购/确认等操作保留原始列表。 */
    public static boolean requestsNewList(String message) {
        if (message == null || message.isBlank()) return false;
        if (message.matches("(?s).*(重新(?:搜|搜索)|改搜|换关键词).*")) return true;
        // 回指旧列表或核对已选对象时，搜索只是取真实字段，不能发布成一份新列表。
        if (message.matches("(?s).*(原列表|刚才.{0,4}(列表|候选)|上次.{0,4}(列表|候选)).*")) return false;
        // 明确发现新候选的任务可以同时包含写意图（先搜两款再加购），写意图不取消新列表。
        if (message.matches("(?s).*(搜|找|推荐|展示|候选|有没有|查商品|换关键词).*")) return true;
        if (message.matches("(?s).*(加购|加入|下单|结算|删除|移除|待确认|第[一二三四五六七八九十\\d]+[个款件]).*")) return false;
        return false;
    }

    public static List<Map<String, Object>> snapshot(List<Item> items) {
        return items.stream().map(item -> Map.<String, Object>of(
                "key", item.key(), "name", item.name(), "specs", item.specs())).toList();
    }

    public static List<Item> restore(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Item> items = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map) || !(map.get("key") instanceof String key)
                    || !(map.get("name") instanceof String name)) continue;
            List<String> specs = map.get("specs") instanceof List<?> entries
                    ? entries.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
            items.add(new Item(key, name, specs));
        }
        return List.copyOf(items);
    }

    public static String render(List<Item> items) {
        StringBuilder text = new StringBuilder("\n\n本轮完整商品候选（序号以此列表与商品卡片为准）：\n");
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            text.append(i + 1).append(". ").append(item.name()).append("（").append(item.key()).append("）");
            if (!item.specs().isEmpty()) text.append("；规格：").append(String.join(" / ", item.specs()));
            text.append('\n');
        }
        return text.toString();
    }

    /** 仅去掉模型商品行的旧序号，保留已经过证据审核的说明与医学风险段落。 */
    public static String withoutProductOrdinals(String reply, List<Item> items) {
        if (reply == null) return "";
        StringBuilder text = new StringBuilder();
        for (String line : reply.split("\\R", -1)) {
            String compact = line.replaceAll("\\s", "");
            boolean productLine = items.stream().anyMatch(item -> compact.contains(item.name().replaceAll("\\s", ""))
                    || compact.matches("(?s).*\\b" + java.util.regex.Pattern.quote(item.key()) + "\\b.*"));
            if (productLine) {
                line = line.replaceFirst("^(\\s*(?:[-•]\\s*)?(?:\\*\\*)?)(?:\\d{1,2}[.、．)）]|[①②③④⑤⑥⑦⑧⑨⑩])\\s*", "$1");
                line = line.replaceFirst("^(\\s*\\|\\s*)(?:\\d{1,2}|[①②③④⑤⑥⑦⑧⑨⑩])\\s*\\|", "$1— |");
            }
            if (!text.isEmpty()) text.append('\n');
            text.append(line);
        }
        return text.toString();
    }
}
