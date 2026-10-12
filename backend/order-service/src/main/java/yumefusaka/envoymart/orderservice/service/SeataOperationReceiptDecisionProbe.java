package yumefusaka.envoymart.orderservice.service;

import org.apache.seata.core.model.GlobalStatus;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.common.operation.BoundedGlobalDecisionProbe;

import javax.sql.DataSource;
import java.util.function.ToLongFunction;

/** 服务仅适配Seata与本库undo，超时/取消/轮询共用同一实现。 */
@Component
public class SeataOperationReceiptDecisionProbe extends BoundedGlobalDecisionProbe {
    @FunctionalInterface
    interface StatusReader { GlobalStatus read(String xid) throws Exception; }

    @Autowired
    public SeataOperationReceiptDecisionProbe(DataSource source) {
        this(xid -> GlobalTransactionContext.reload(xid).getStatus(), undoReader(source));
    }

    SeataOperationReceiptDecisionProbe(StatusReader statuses, ToLongFunction<String> undo) {
        super(xid -> {
            GlobalStatus status = statuses.read(xid);
            return Observation.fromStatusName(status == null ? null : status.name());
        }, undo);
    }

    private static ToLongFunction<String> undoReader(DataSource source) {
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(2);
        return xid -> jdbc.queryForObject("select count(*) from undo_log where xid=?", Long.class, xid);
    }
}
