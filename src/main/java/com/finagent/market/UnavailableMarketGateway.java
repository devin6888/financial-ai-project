package com.finagent.market;

import java.util.List;
import java.util.Map;

/**
 * 行情不可用时的实现：任何取数都直接抛 {@link MarketUnavailableException}。
 *
 * <p>存在的意义是让图拓扑与节点代码保持一条路径 —— 不写 {@code if (gateway == null)}，
 * 关掉行情之后的行为与「服务挂了」完全一致，降级文案也只有一份。
 */
public class UnavailableMarketGateway implements MarketGateway {

    private final String reason;

    public UnavailableMarketGateway(String reason) {
        this.reason = reason;
    }

    @Override
    public Snapshot call(String tool, String code, int days) {
        throw new MarketUnavailableException(reason);
    }

    @Override
    public Snapshot execute(String tool, Map<String, Object> arguments, int days) {
        throw new MarketUnavailableException(reason);
    }

    @Override
    public List<Hit> search(String keyword) {
        return List.of();
    }
}
