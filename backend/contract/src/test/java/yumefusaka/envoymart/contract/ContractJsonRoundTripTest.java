package yumefusaka.envoymart.contract;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约类的 JSON 往返测试 —— <b>每个字段都要能序列化出去、再反序列化回来</b>。
 * <p>
 * 这个测试存在的唯一理由是它抓到过的那类 bug：{@code @Builder} 会消掉隐式的无参构造器，
 * Jackson 于是找不到可用的 creator，反序列化抛
 * {@code Type definition error: [simple type, class ...]}。
 * <p>
 * <b>为什么不在写契约类的时候靠评审发现</b>：这个错误在两侧的表现完全不同。
 * 生产侧序列化走 getter，一切正常；单元测试用 {@code builder()} 造对象，也一切正常；
 * 只有「另一个服务真的把这个类型收下来」这一步会炸。本项目就是这么漏掉的——
 * AI 的商品工具每次调用都在反序列化上失败，被 catch 之后回了一句
 * 「没有找到相关商品」，看起来像搜索没命中。
 * <p>
 * <b>为什么每个字段都塞值，而不是序列化一个空对象</b>：空对象只验证最外层能不能构造，
 * 字段为 null 时嵌套契约类根本不会被访问到。这里对每个字段按类型造一个非空值，
 * 嵌套的契约类因此也会走完往返，{@code OrderResponse → OrderItemResponse → SkuSnapshot}
 * 这种链子断在哪一环都能定位到具体字段名。
 * <p>
 * <b>为什么靠扫描而不是逐个列举</b>：逐个列举的版本会在「新增一个契约类但忘了加进列表」时
 * 静默失效，而那时它恰好是最该生效的。
 */
class ContractJsonRoundTripTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 造值时的递归深度上限。契约类之间没有循环引用，这个上限只是防御 */
    private static final int MAX_DEPTH = 6;

    @Test
    void 每个契约类都能完成JSON往返() throws Exception {
        List<Class<?>> types = contractTypes();
        assertThat(types)
                .as("契约模块应当能扫描到 DTO 类；扫不到说明测试的类发现逻辑坏了，"
                        + "而不是契约类都没问题")
                .hasSizeGreaterThanOrEqualTo(15);

        List<String> failures = new ArrayList<>();
        for (Class<?> type : types) {
            try {
                Object populated = instantiate(type, 0);
                String json = MAPPER.writeValueAsString(populated);
                Object back = MAPPER.readValue(json, type);
                assertThat(back).as("%s 反序列化后不应为 null", type.getSimpleName()).isNotNull();
            } catch (Exception e) {
                failures.add(type.getSimpleName() + " → " + rootCause(e));
            }
        }

        assertThat(failures)
                .as("下列契约类无法完成 JSON 往返。绝大多数情况是缺少 "
                        + "@NoArgsConstructor + @AllArgsConstructor —— "
                        + "@Builder 会消掉隐式无参构造器，Jackson 因此没有可用的 creator")
                .isEmpty();
    }

    // ==================== 类发现 ====================

    /**
     * 扫描本包下的所有契约类。
     * <p>
     * 判据是「能无参构造 + 有字段」而不是「名字以 Payload/Response 结尾」：
     * 命名约定会变，而这两条是 Jackson 反序列化真正要求的东西。
     * 枚举、接口、{@code package-info} 因此自然被排除。
     */
    private static List<Class<?>> contractTypes() throws Exception {
        String pkg = ContractJsonRoundTripTest.class.getPackageName();

        Set<Class<?>> types = new LinkedHashSet<>();
        for (File file : packageDir(pkg).listFiles((dir, name) -> name.endsWith(".class"))) {
            String name = file.getName().substring(0, file.getName().length() - ".class".length());
            Class<?> type = Class.forName(pkg + "." + name);
            if (isContractDto(type)) {
                types.add(type);
            }
        }
        return new ArrayList<>(types);
    }

    /**
     * 契约包在文件系统上的目录。
     * <p>
     * <b>用生产类做锚点，不能用测试类</b>：测试类也在同一个包下，而 {@code target/test-classes}
     * 在 classpath 上排在 {@code target/classes} 前面，按包名去 getResource 会命中测试输出目录——
     * 那里只有本测试自己一个文件，过滤后一个 DTO 都扫不到，测试会以「扫描逻辑坏了」的方式失败，
     * 看起来像反射写错了。
     */
    private static File packageDir(String pkg) throws Exception {
        URL anchor = ProductSummary.class.getProtectionDomain().getCodeSource().getLocation();
        assertThat(anchor.getProtocol()).as("契约类扫描需要文件系统上的 class 目录").isEqualTo("file");
        File dir = new File(new File(anchor.toURI()), pkg.replace('.', '/'));
        assertThat(dir).as("契约包的编译产物目录不存在：%s", dir).isDirectory();
        return dir;
    }

    private static boolean isContractDto(Class<?> type) {
        if (type.isEnum() || type.isInterface() || type.isPrimitive()) {
            return false;
        }
        if (Modifier.isAbstract(type.getModifiers())) {
            return false;
        }
        // Lombok 为每个 @Builder 生成的 <类名>Builder 也在同一个包里，
        // 但它们从不上线传输（走的是 getter），对它们要求可反序列化没有意义。
        // 嵌套的契约类（如 SpecGroup.Value）不在此列——它是真实的线上类型，
        // 由 instantiate 递归覆盖
        if (type.getSimpleName().endsWith("Builder")) {
            return false;
        }
        // 无参构造器是反序列化的入场券；没有它就一定往返不过，由测试本体去报错而不是在这里筛掉
        if (Arrays.stream(type.getDeclaredFields())
                .noneMatch(f -> !Modifier.isStatic(f.getModifiers()))) {
            return false;
        }
        return type.getPackageName().equals(ContractJsonRoundTripTest.class.getPackageName());
    }

    // ==================== 造值 ====================

    private static Object instantiate(Class<?> type, int depth) throws Exception {
        if (depth > MAX_DEPTH) {
            return null;
        }
        if (type.isRecord()) {
            return instantiateRecord(type, depth);
        }
        var ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object instance = ctor.newInstance();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) {
                continue;
            }
            Object value = valueFor(f.getName(), f.getType(), f.getGenericType(), depth);
            if (value != null) {
                f.setAccessible(true);
                f.set(instance, value);
            }
        }
        return instance;
    }

    /**
     * record 走<b>规范构造器</b>，不是无参构造器 + 反射写字段。
     * <p>
     * 两条路都必须走这条：record 的组件字段是 {@code final}，上面那个循环会跳过它们，
     * 于是对象能以「所有组件都是 null」的状态构造出来，JSON 往返一路绿灯——
     * <b>一个字段都没验证，测试却是绿的</b>。这正是本测试最不能出的那种错。
     * <p>
     * 走规范构造器还有第二个好处：参数类型与顺序由编译器钉死，
     * 少一个组件、顺序换了，这里立刻 {@code NoSuchMethodException}。
     */
    private static Object instantiateRecord(Class<?> type, int depth) throws Exception {
        var components = type.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            paramTypes[i] = components[i].getType();
            args[i] = valueFor(components[i].getName(), components[i].getType(),
                    components[i].getGenericType(), depth);
        }
        var ctor = type.getDeclaredConstructor(paramTypes);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    /**
     * 按字段类型造一个非空值。造不出来的类型返回 {@code null}——
     * 这时那个字段在 JSON 里是 null，测试对它的覆盖就少一层，但不该因此整条失败。
     * <p>
     * 形参是「名字 + 类型 + 泛型」而不是 {@code Field}：record 的组件不是
     * {@code Field}（通过 {@link Field} 拿不到泛型实参时才轮到这里），
     * 但造值规则必须与类字段完全一致，否则两类载体测的东西就不是一回事了。
     */
    private static Object valueFor(String name, Class<?> t, java.lang.reflect.Type generic, int depth)
            throws Exception {
        if (t == String.class) {
            return "值-" + name;
        }
        if (t == Long.class || t == long.class) {
            return 1L;
        }
        if (t == Integer.class || t == int.class) {
            return 1;
        }
        if (t == Boolean.class || t == boolean.class) {
            return true;
        }
        if (t == BigDecimal.class) {
            return new BigDecimal("1.00");
        }
        if (List.class.isAssignableFrom(t)) {
            // 列表里放一个元素：空列表会让元素类型完全不参与往返，
            // 而 List<OrderItemResponse> 里的那个类正是最容易漏测的
            Class<?> element = elementType(generic);
            Object item = element == null ? null : scalarOrInstance(element, depth + 1);
            List<Object> list = new ArrayList<>();
            if (item != null) {
                list.add(item);
            }
            return list;
        }
        if (t.isEnum()) {
            Object[] constants = t.getEnumConstants();
            return constants.length == 0 ? null : constants[0];
        }
        // 集合元素为 null（拿不到泛型实参）时仍然返回一个空列表而不是 null：
        // 字段为 null 时 JSON 里是 null，反序列化不会去碰元素类型，覆盖就白丢了
        if (java.util.Collection.class.isAssignableFrom(t)) {
            return new ArrayList<>();
        }
        if (t.getName().startsWith("java.")) {
            return null;
        }
        return instantiate(t, depth + 1);
    }

    private static Object scalarOrInstance(Class<?> type, int depth) throws Exception {
        if (type == String.class) {
            return "元素";
        }
        if (type == Long.class || type == long.class) {
            return 1L;
        }
        if (type == Integer.class || type == int.class) {
            return 1;
        }
        if (type == Boolean.class || type == boolean.class) {
            return true;
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants.length == 0 ? null : constants[0];
        }
        if (type.getName().startsWith("java.")) {
            return null;
        }
        return instantiate(type, depth);
    }

    /**
     * 取 {@code List<X>} 里的 {@code X}。
     * <p>
     * 优先读泛型签名；拿不到时（原始类型、通配符）退回 {@code null}，
     * 让调用方按「没有元素」处理，而不是抛异常。
     */
    private static Class<?> elementType(java.lang.reflect.Type generic) {
        try {
            if (generic instanceof java.lang.reflect.ParameterizedType pt
                    && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
                return c;
            }
        } catch (RuntimeException ignored) {
            // 泛型签名缺失不是错误，只是这一层少一份覆盖
        }
        return null;
    }

    private static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
