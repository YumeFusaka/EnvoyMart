package yumefusaka.envoymart.orderservice.service;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import yumefusaka.envoymart.common.operation.OperationReceiptSchema;
import javax.sql.DataSource;

@Component
public class OperationReceiptSchemaCheck implements ApplicationRunner {
    private final DataSource source;
    public OperationReceiptSchemaCheck(DataSource source) { this.source = source; }
    @Override public void run(ApplicationArguments args) { OperationReceiptSchema.verify(source, "order_operation_receipt"); }
}
