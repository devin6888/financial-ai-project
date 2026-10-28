package com.finagent.scope;

import java.util.List;

/**
 * 不做任何过滤的实现：只在「ACL 关闭」时生效（{@code finagent.tenant.acl-enabled=false}）。
 *
 * <p>为什么保留它：test profile 下有 300+ 个既有测试不关心身份，逐个造 token 会淹没被测逻辑；
 * 关掉 ACL 后它们仍然验「作用域解析 / 检索 / 答案」这些真正的被测点。代价是<b>测试环境不验 ACL</b>，
 * 所以 ACL 必须有自己的数据库级测试（{@code AclScopeAuthorizerTest}）与越权 e2e（阶段 8 Task 8）。
 *
 * <p>装配位置见 {@link ScopeAuthorizerConfig}：由配置类 new，不用 {@code @Component}，
 * 从结构上保证「任何 profile 下都只有一个 {@link ScopeAuthorizer}」。
 */
public class AllowAllAuthorizer implements ScopeAuthorizer {

    @Override
    public List<Long> filterReadable(List<Long> documentIds) {
        return documentIds == null ? List.of() : documentIds;
    }
}
