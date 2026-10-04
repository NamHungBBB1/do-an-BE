package com.doan.game.web;

/**
 * The few HTML pages the backend serves itself: links opened from an e-mail land in a browser, not in
 * the FE, so they cannot answer with JSON. Kept out of the controller so the controller stays thin
 * (receive, call service, return) like every other controller.
 *
 * Every value that came from a user goes through {@link #escape(String)}.
 */
public final class HtmlPages {

    private HtmlPages() {
    }

    /** Plain result page. No redirect on purpose: the backend does not know the FE login URL yet. */
    public static String page(String title, String message) {
        return """
                <!doctype html>
                <html lang="vi"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s — FinTeen</title></head>
                <body style="font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem;line-height:1.6">
                <h1 style="font-size:1.4rem">%s</h1>
                <p>%s</p>
                </body></html>
                """.formatted(escape(title), escape(title), message);
    }

    /** Business error shown in a browser (expired link, password too short…). */
    public static String error(String message) {
        return page("Không hoàn tất được", escape(message)
                + " Mở link trong mail mới nhất, hoặc xin link mới ở trang đăng nhập.");
    }

    /**
     * Reset-password form. action is RELATIVE ("reset"): an absolute "/api/auth/password/reset"
     * drops the /finteen context path on the VPS and the browser gets 404.
     */
    public static String resetPasswordForm(String token) {
        return """
                <!doctype html>
                <html lang="vi"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Đặt lại mật khẩu — FinTeen</title></head>
                <body style="font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem;line-height:1.6">
                <h1 style="font-size:1.4rem">Đặt lại mật khẩu</h1>
                <form method="post" action="reset">
                <input type="hidden" name="token" value="%s">
                <p><label>Mật khẩu mới<br>
                <input type="password" name="newPassword" minlength="8" required
                       autocomplete="new-password" style="width:100%%;padding:.5rem"></label></p>
                <p><button type="submit" style="padding:.5rem 1rem">Đổi mật khẩu</button></p>
                </form>
                <p style="color:#666;font-size:.9rem">Link hết hạn sau 30 phút và chỉ dùng được một lần.</p>
                </body></html>
                """.formatted(escape(token));
    }

    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
