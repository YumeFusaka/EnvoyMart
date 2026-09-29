package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一篇文档的图谱贡献 —— <b>由 ai-service 发出，knowledge-service 消费</b>。
 * <p>
 * <b>调用即代表「这是这篇文档能抽出的全部关系」。</b>knowledge-service 会先删掉该文档
 * 在图上的所有旧边再写入本批，这是重建幂等的前提（否则每重建一次，改过措辞的旧边就多堆一份）。
 * 因此：<b>抽取失败时不要调本接口</b>。空列表与「没抽」在服务端看起来一模一样，
 * 而前者会把一篇文档辛苦建好的边全部清空，且不留任何痕迹。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphIngestPayload {

    /** 文档编号，形如 KB-0010。图谱按它做「先删后写」的归属单位 */
    private String docNo;
    /** 本次抽取的全部候选三元组，可为空列表（表示这篇确实没有可用关系） */
    private List<GraphTriplePayload> triples;
}
