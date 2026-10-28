package com.finagent.security;

/** 401：没有身份，或身份无效。
 * @author 67564
 */
public class UnauthorizedException extends RuntimeException {

    private final String code;

    public UnauthorizedException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** AUTH_REQUIRED（没带凭证）/ AUTH_INVALID（凭证无效）。 */
    public String code() {
        return code;
    }
}
