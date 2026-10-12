package yumefusaka.envoymart.productservice.projection;

import org.apache.seata.tm.api.GlobalTransactionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.common.operation.BoundedGlobalDecisionProbe;

/** 商品投影只适配已有Seata客户端和商品库undo，复用通用有界核验。 */
@Component
public class SeataProductProjectionDecisionProbe extends BoundedGlobalDecisionProbe {
    public SeataProductProjectionDecisionProbe(javax.sql.DataSource source) {
        super(xid -> {
            var status = GlobalTransactionContext.reload(xid).getStatus();
            return Observation.fromStatusName(status == null ? null : status.name());
        }, undoReader(source));
    }
    private static java.util.function.ToLongFunction<String> undoReader(javax.sql.DataSource source) {
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(2);
        return xid -> jdbc.queryForObject("select count(*) from undo_log where xid=?", Long.class, xid);
    }
}
