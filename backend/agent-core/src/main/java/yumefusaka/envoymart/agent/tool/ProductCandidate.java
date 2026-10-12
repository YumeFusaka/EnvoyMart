package yumefusaka.envoymart.agent.tool;

import java.util.List;

/** 商品工具的只读候选契约；核心只认稳定标识、名称与真实规格，不依赖电商 DTO。 */
public interface ProductCandidate {
    String candidateKey();
    String candidateName();
    List<String> candidateSpecs();
}
