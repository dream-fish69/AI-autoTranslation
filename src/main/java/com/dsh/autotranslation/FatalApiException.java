package com.dsh.autotranslation;

/**
 * A request the API rejected for a reason that retrying cannot fix:
 * bad key (401/403), no balance (402) or a malformed request (400).
 *
 * <p>Thrown through the pipeline so the retry loop stops immediately and the player sees a
 * readable reason instead of three timeouts and a stack trace.</p>
 */
public final class FatalApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;

    public FatalApiException(int status, String body) {
        super(describe(status, body));
        this.status = status;
    }

    public int status() {
        return status;
    }

    private static String describe(int status, String body) {
        String hint = switch (status) {
            case 401, 403 -> "API Key 无效或没有权限";
            case 402 -> "API 账户余额不足";
            case 400 -> "API 请求被拒绝（地址或模型名可能不对）";
            default -> "API 拒绝了请求";
        };
        String detail = body == null || body.isBlank() ? "" : "：" + body;
        return hint + "（HTTP " + status + "）" + detail;
    }
}
