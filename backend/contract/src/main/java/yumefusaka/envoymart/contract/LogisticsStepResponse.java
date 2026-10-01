package yumefusaka.envoymart.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 物流轨迹的一步。
 * <p>
 * {@code detail} 是给用户看的那句话，不是状态码；{@code status} 是给机器认的编码，
 * 两者都要有——只给编码用户看不懂，只给句子则谁也判断不了"到哪一步了"。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogisticsStepResponse {

    private String status;
    private String detail;
    /**
     * 节点所在地，可空。
     * <p>
     * 库表里这一列从建表第一天就有，但<b>此前没有任何一条链路把它取出来过</b>——
     * 用户问"货到哪了"，答的却是"已发往下一站"：有状态、没有地点，
     * 而"到哪了"问的恰恰是地点。补录的节点多半也填不出个所以然（客服手上只有
     * 承运商给的一句话），所以它是可空的，不是必填。
     */
    private String location;
    private LocalDateTime time;
}
