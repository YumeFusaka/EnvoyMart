package yumefusaka.envoymart.agent.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 画像槽位的更新语义 —— "新老信息如何分辨"与"过时如何处理"的回归防线。
 * <p>
 * 这些行为是纯非结构化存储做不到的：没有槽位就无法判断两条文本是"同一事实的两版"
 * 还是"两条不同的事实"，只能都留着，最后两条互相矛盾的记忆一起注入。
 */
class UserProfileTest {

    private static final String USER = "u1001";

    @Test
    void 同一槽位新值覆盖旧值且旧值留档() {
        UserProfile profile = new UserProfile(USER);

        profile.update(ProfileSlot.BUDGET, "100 元左右", 0.8);
        profile.update(ProfileSlot.BUDGET, "300 元左右", 0.8);

        ProfileEntry entry = profile.slots().get(ProfileSlot.BUDGET);
        assertThat(entry.getValue()).isEqualTo("300 元左右");
        assertThat(entry.getPreviousValue())
                .as("旧值移入 previous 供审计，但不参与注入")
                .isEqualTo("100 元左右");
        assertThat(profile.injectionEntries()).hasSize(1);
    }

    @Test
    void 同一事实重复出现不产生新条目只提升置信度() {
        UserProfile profile = new UserProfile(USER);

        profile.update(ProfileSlot.IDENTITY, "学生党", 0.8);
        profile.update(ProfileSlot.IDENTITY, "学生党", 0.8);
        profile.update(ProfileSlot.IDENTITY, "  学生党  ", 0.8);

        assertThat(profile.slots())
                .as("值相同是同一事实的又一次证据，不是新条目")
                .hasSize(1);
        assertThat(profile.slots().get(ProfileSlot.IDENTITY).getConfidence())
                .as("置信度随证据累积，且有上界")
                .isGreaterThan(0.8)
                .isLessThanOrEqualTo(1.0);
    }

    @Test
    void 低置信度槽位不注入但保留在存储中() {
        UserProfile profile = new UserProfile(USER);

        profile.update(ProfileSlot.PREFERRED_BRAND, "Sony", 0.3);

        assertThat(profile.injectionEntries()).isEmpty();
        assertThat(profile.slots())
                .as("不注入不等于删除，仍然可查")
                .containsKey(ProfileSlot.PREFERRED_BRAND);
    }

    @Test
    void 时间敏感槽位超期后不再注入() {
        UserProfile profile = new UserProfile(USER);
        profile.update(ProfileSlot.BUDGET, "300 元左右", 0.9);

        // 把更新时间推到 TTL 之外
        profile.slots().get(ProfileSlot.BUDGET)
                .setUpdatedAt(Instant.now().minus(200, ChronoUnit.DAYS));

        assertThat(profile.injectionEntries())
                .as("预算这类会变的槽位过期后不该再影响判断")
                .isEmpty();
        assertThat(profile.slots()).containsKey(ProfileSlot.BUDGET);
    }

    @Test
    void 长期有效的槽位不随时间失效() {
        UserProfile profile = new UserProfile(USER);
        profile.update(ProfileSlot.IDENTITY, "学生党", 0.9);

        profile.slots().get(ProfileSlot.IDENTITY)
                .setUpdatedAt(Instant.now().minus(200, ChronoUnit.DAYS));

        assertThat(profile.injectionEntries())
                .as("「是学生党」可能三年不变，不该跟预算用同一个衰减率")
                .hasSize(1);
    }

    @Test
    void 空槽位取值被忽略() {
        UserProfile profile = new UserProfile(USER);

        profile.update(ProfileSlot.BUDGET, null, 0.9);
        profile.update(ProfileSlot.BUDGET, "   ", 0.9);
        profile.update(null, "300 元", 0.9);

        assertThat(profile.isEmpty()).isTrue();
    }

    @Test
    void 画像按用户隔离() {
        UserProfileStore store = new UserProfileStore();

        store.update("u1001", java.util.List.of(
                ProfileEntry.builder().slot(ProfileSlot.BUDGET).value("100 元").confidence(0.9).build()));
        store.update("u1002", java.util.List.of(
                ProfileEntry.builder().slot(ProfileSlot.BUDGET).value("9999 元").confidence(0.9).build()));

        assertThat(store.get("u1001").injectionEntries().get(0).getValue()).isEqualTo("100 元");
        assertThat(store.get("u1002").injectionEntries().get(0).getValue()).isEqualTo("9999 元");
    }
}
