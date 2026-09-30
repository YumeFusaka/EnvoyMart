package yumefusaka.envoymart.agent.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回源失败时的读写行为 —— 一条会静默吃掉用户数据的路径。
 * <p>
 * {@code save} 是覆盖写：落盘的那份就是内存里那一份。于是「读不到」和「写得掉」组合起来
 * 会变成数据丢失——读取失败拿到空画像，更新一个槽位，回写时把 Redis 里原有的其余槽位全顶掉。
 * 而读取失败往往是持久的（值损坏、类型不对），一旦如此，每轮对话削一刀。
 */
class UserProfileStoreTest {

    /** 可切换成败的仓储桩；save 覆盖写，与真实实现一致 */
    private static final class FlakyRepository implements ProfileRepository {
        private final List<ProfileEntry> stored = new ArrayList<>();
        private boolean failLoad;

        @Override
        public List<ProfileEntry> load(String userId) {
            if (failLoad) {
                throw new IllegalStateException("连接超时");
            }
            return List.copyOf(stored);
        }

        @Override
        public void save(String userId, List<ProfileEntry> entries) {
            stored.clear();
            stored.addAll(entries);
        }
    }

    private static ProfileEntry entry(ProfileSlot slot, String value) {
        return ProfileEntry.builder().slot(slot).value(value).confidence(0.9).build();
    }

    @Test
    void 回源失败不落缓存下一次仍能读到真实画像() {
        FlakyRepository repository = new FlakyRepository();
        repository.save("u1001", List.of(entry(ProfileSlot.IDENTITY, "学生党")));
        UserProfileStore store = new UserProfileStore(repository);

        repository.failLoad = true;
        assertThat(store.get("u1001").slots())
                .as("回源失败时退化成空画像，本轮对话照常")
                .isEmpty();

        repository.failLoad = false;
        assertThat(store.get("u1001").slots())
                .as("空画像一旦进了缓存，之后每次 get 都命中它，Redis 里那份再也读不到")
                .containsKey(ProfileSlot.IDENTITY);
    }

    @Test
    void 回源失败时不回写以免覆盖已有画像() {
        FlakyRepository repository = new FlakyRepository();
        repository.save("u1001", List.of(
                entry(ProfileSlot.IDENTITY, "学生党"),
                entry(ProfileSlot.BUDGET, "100 元左右")));
        UserProfileStore store = new UserProfileStore(repository);

        repository.failLoad = true;
        store.update("u1001", List.of(entry(ProfileSlot.SERVICE_PREFERENCE, "偏好清淡")));

        assertThat(repository.stored)
                .as("拿一份已知不完整的画像当基准覆盖写，两个已有槽位会被这一个新槽位顶掉")
                .hasSize(2)
                .noneSatisfy(e -> assertThat(e.getSlot()).isEqualTo(ProfileSlot.SERVICE_PREFERENCE));
    }

    @Test
    void 回源正常时更新落盘且内存生效() {
        FlakyRepository repository = new FlakyRepository();
        repository.save("u1001", List.of(entry(ProfileSlot.IDENTITY, "学生党")));
        UserProfileStore store = new UserProfileStore(repository);

        store.update("u1001", List.of(entry(ProfileSlot.BUDGET, "100 元左右")));

        assertThat(store.get("u1001").slots()).containsKeys(ProfileSlot.IDENTITY, ProfileSlot.BUDGET);
        assertThat(repository.stored)
                .as("更新是读改写，原有槽位必须一起带上")
                .hasSize(2);
    }
}
