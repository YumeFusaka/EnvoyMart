package yumefusaka.envoymart.productservice.projection;

/** Redis版本用十进制字符串比较，避免Lua双精度丢失bigint低位。 */
public final class ProductProjectionCacheScripts {
    private ProductProjectionCacheScripts() { }
    private static final String COMPARE = "local function check(v) if not string.match(v,'^%d+$') or (#v>1 and string.sub(v,1,1)=='0') then error('invalid projection version') end end "
            + "local function older(a,b) check(a); check(b); return #a<#b or (#a==#b and a<b) end ";
    public static final String INVALIDATE = COMPARE
            + "local old=redis.call('GET',KEYS[2]) or '0'; if older(ARGV[1],old) then return 0 end; "
            + "redis.call('SET',KEYS[2],ARGV[1]); redis.call('DEL',KEYS[1]); return 1";
    public static final String FILL = COMPARE
            + "local old=redis.call('GET',KEYS[2]) or '0'; if older(ARGV[1],old) then return 0 end; "
            + "if ARGV[1]~='0' then redis.call('SET',KEYS[2],ARGV[1]) end; redis.call('SET',KEYS[1],ARGV[2],'PX',ARGV[3]); return 1";
}
