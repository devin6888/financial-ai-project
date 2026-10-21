package com.finagent.scope;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 阶段 5 的默认实现：不做任何过滤。
 *
 * <p>阶段 8 接入真实 ACL 时，删掉这个类的 @Component 换成实现即可 ——
 * 调用方只依赖 {@link ScopeAuthorizer} 接口。
 */
@Component
public class AllowAllAuthorizer implements ScopeAuthorizer {

    @Override
    public List<Long> filterReadable(List<Long> documentIds) {
        return documentIds == null ? List.of() : documentIds;
    }
}
